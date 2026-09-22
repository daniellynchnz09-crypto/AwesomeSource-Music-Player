"""Main application window: scan progress, the file table, and the actions that tie
the scanner, MusicBrainz lookup, manual-entry form, duplicate finder, and
write/undo path together.
"""

from __future__ import annotations

import time
from pathlib import Path

from PySide6.QtCore import QSortFilterProxyModel, QThreadPool
from PySide6.QtWidgets import (
    QAbstractItemView,
    QFileDialog,
    QHeaderView,
    QMainWindow,
    QMessageBox,
    QProgressBar,
    QStatusBar,
    QTableView,
    QToolBar,
)

from musictagger.config import DATA_DIR, DB_PATH, Settings, load_settings, save_settings
from musictagger.dedup.duplicate_finder import find_exact_duplicates, find_metadata_duplicates
from musictagger.grouping.album_grouper import group_into_albums
from musictagger.metadata_sources.musicbrainz_client import MusicBrainzError
from musictagger.metadata_sources.release_resolver import resolve_group_to_proposed
from musictagger.models import AlbumGroup, FileStatus, TrackMetadata
from musictagger.persistence import db as db_module
from musictagger.tags.filename_parser import parse_filename
from musictagger.tags.reader import read_tags
from musictagger.ui.duplicates_dialog import DuplicatesDialog
from musictagger.ui.file_table_model import FileTableModel
from musictagger.ui.manual_entry_form import ManualEntryForm
from musictagger.ui.review_panel import ReviewPanel
from musictagger.ui.settings_dialog import SettingsDialog
from musictagger.workers.query_worker import QueryWorker
from musictagger.workers.scan_worker import ScanWorker
from musictagger.workers.write_worker import WriteWorker

# Statuses that mean "we already know what to do here" — a double-click on one of
# these still opens plain manual entry rather than a review flow.
_NO_LOOKUP_STATUSES = {FileStatus.APPLIED, FileStatus.SKIPPED}


def _seed_with_filename_guess(track: TrackMetadata) -> TrackMetadata:
    """Returns a copy of track with blank artist/album/title fields pre-filled from
    the filename/folder guess, purely so the manual entry form has sensible defaults.
    Never touches the file on disk and never overrides a value that's already set."""
    if track.artist and track.title:
        return track
    guess = parse_filename(track.path)
    seeded = TrackMetadata(**{**track.__dict__})
    seeded.artist = seeded.artist or guess.artist
    seeded.album = seeded.album or guess.album
    seeded.title = seeded.title or guess.title
    seeded.track_number = seeded.track_number or guess.track_number
    return seeded


class MainWindow(QMainWindow):
    def __init__(self):
        super().__init__()
        self.setWindowTitle("Music Details Generator")
        self.resize(1100, 650)

        self.settings: Settings = load_settings()
        self.db_conn = db_module.get_connection(DB_PATH)
        self.thread_pool = QThreadPool.globalInstance()
        self.session_id: int | None = None
        self._active_workers: list = []
        self._operation_start_time: float | None = None

        self.groups: list[AlbumGroup] = []
        self.group_by_path: dict[Path, AlbumGroup] = {}

        self.model = FileTableModel()
        # A proxy model is what actually makes the header-arrow click-to-sort
        # behavior work — QTableView's setSortingEnabled draws the arrows either
        # way, but a plain QAbstractTableModel has no sort() implementation of its
        # own, so nothing would actually reorder without this in between.
        self.proxy_model = QSortFilterProxyModel()
        self.proxy_model.setSourceModel(self.model)
        self.table = QTableView()
        self.table.setModel(self.proxy_model)
        self.table.setSortingEnabled(True)
        self.table.setSelectionBehavior(QAbstractItemView.SelectRows)
        self.table.horizontalHeader().setSectionResizeMode(QHeaderView.Interactive)
        self.table.horizontalHeader().setStretchLastSection(True)
        # Qt's default is ScrollPerItem, which jumps a whole column's width per wheel
        # tick/drag — with columns of very different widths (especially after
        # resizing one) that reads as jittery, uneven scrolling. Pixel-based
        # scrolling is smooth regardless of column width.
        self.table.setHorizontalScrollMode(QAbstractItemView.ScrollPerPixel)
        self.table.setVerticalScrollMode(QAbstractItemView.ScrollPerPixel)
        self.table.doubleClicked.connect(self._on_row_double_clicked)
        self.setCentralWidget(self.table)

        self.status_bar = QStatusBar()
        self.setStatusBar(self.status_bar)
        self.progress_bar = QProgressBar()
        self.progress_bar.setFixedWidth(200)
        self.progress_bar.hide()
        self.status_bar.addPermanentWidget(self.progress_bar)

        self._build_toolbar()

    def _show_progress(self, done: int, total: int) -> None:
        self.progress_bar.setRange(0, total)
        self.progress_bar.setValue(done)
        self.progress_bar.show()

    def _hide_progress(self) -> None:
        self.progress_bar.hide()
        self._operation_start_time = None

    def _format_eta(self, done: int, total: int) -> str:
        """A rough "time remaining" estimate from the rate seen so far this run —
        deliberately simple (no smoothing/averaging) since done/total here means
        "files scanned" or "groups looked up", not bytes, and the per-item cost is
        fairly uniform within one operation."""
        if self._operation_start_time is None or done <= 0 or done >= total:
            return "estimating..." if done < total else "almost done"
        elapsed = time.monotonic() - self._operation_start_time
        rate = done / elapsed if elapsed > 0 else 0
        if rate <= 0:
            return "estimating..."
        seconds_left = (total - done) / rate
        if seconds_left < 1:
            return "almost done"
        if seconds_left < 60:
            return f"~{int(seconds_left)}s left"
        minutes, seconds = divmod(int(seconds_left), 60)
        return f"~{minutes}m {seconds}s left"

    def _on_scan_progress(self, count: int, total: int) -> None:
        if self._operation_start_time is None:
            self._operation_start_time = time.monotonic()
        self.progress_bar.setRange(0, total)
        self.progress_bar.setValue(count)
        eta = self._format_eta(count, total)
        self.progress_bar.setFormat(f"%v/%m — {eta}")
        self.progress_bar.show()
        self.status_bar.showMessage(f"Scanning... {count}/{total} files read ({eta})")

    def _on_lookup_progress(self, done: int, total: int) -> None:
        if self._operation_start_time is None:
            self._operation_start_time = time.monotonic()
        self.progress_bar.setRange(0, total)
        self.progress_bar.setValue(done)
        # QProgressBar's built-in %p format is integer-only — the decimal comes
        # from computing it directly and embedding it as literal text instead.
        percent = (done / total * 100) if total else 0.0
        eta = self._format_eta(done, total)
        self.progress_bar.setFormat(f"%v/%m ({percent:.1f}%) — {eta}")
        self.progress_bar.show()
        self.status_bar.showMessage(f"Looking up MusicBrainz... {done}/{total} groups ({eta})")

    def _build_toolbar(self) -> None:
        toolbar = QToolBar("Main")
        self.addToolBar(toolbar)

        toolbar.addAction("Add Folder...", self._on_add_folder)
        toolbar.addAction("Start Scan", self._on_start_scan)
        toolbar.addSeparator()
        toolbar.addAction("Look Up Online (MusicBrainz)", self._on_lookup_online)
        toolbar.addAction("Accept All Auto-Matches", self._on_accept_all_auto_matches)
        toolbar.addAction("Retry Failed Lookups", self._on_retry_failed_lookups)
        toolbar.addAction("Clear Lookup Cache", self._on_clear_query_cache)
        toolbar.addSeparator()
        toolbar.addAction("Find Duplicates", self._on_find_duplicates)
        toolbar.addAction("Undo Last Batch", self._on_undo_last_batch)
        toolbar.addSeparator()
        toolbar.addAction("Settings...", self._on_open_settings)

    # -- Folder / scan -----------------------------------------------------

    def _on_add_folder(self) -> None:
        path = QFileDialog.getExistingDirectory(self, "Choose a library folder")
        if path and path not in self.settings.library_folders:
            self.settings.library_folders.append(path)
            save_settings(self.settings)
            self.status_bar.showMessage(f"Added folder: {path}", 4000)

    def _on_start_scan(self) -> None:
        if not self.settings.library_folders:
            QMessageBox.information(self, "No folders", "Add a library folder first.")
            return

        roots = [Path(p) for p in self.settings.library_folders]
        missing = [r for r in roots if not r.exists()]
        if missing:
            names = "\n".join(str(r) for r in missing)
            QMessageBox.warning(
                self,
                "Folder not found",
                f"These configured library folder(s) don't exist:\n{names}\n\n"
                "Use Settings to add or fix your library folder(s).",
            )
            roots = [r for r in roots if r.exists()]
            if not roots:
                return
        self.model.set_tracks([])
        self.groups = []
        self.group_by_path = {}
        self.session_id = db_module.start_session(self.db_conn, self.settings.library_folders)
        self._operation_start_time = None

        worker = ScanWorker(roots)
        worker.signals.file_scanned.connect(self._on_file_scanned)
        worker.signals.progress.connect(self._on_scan_progress)
        worker.signals.finished.connect(self._on_scan_finished)
        worker.signals.error.connect(lambda msg: self.status_bar.showMessage(f"Scan error: {msg}", 6000))
        self._track_worker(worker)
        self.thread_pool.start(worker)
        self.status_bar.showMessage("Scanning...")

    def _on_file_scanned(self, track: TrackMetadata) -> None:
        if not track.artist or not track.title:
            guess = parse_filename(track.path)
            if guess.confidence == "loose" and not guess.search_text:
                track.status = FileStatus.INSUFFICIENT_INFO
                track.status_detail = "no usable tag or filename info"
            else:
                track.status = FileStatus.MANUAL_PENDING
                track.status_detail = "missing artist/title — needs manual entry"
        self.model.add_track(track)

    def _on_scan_finished(self, tracks: list[TrackMetadata]) -> None:
        self._hide_progress()
        if self.session_id is not None:
            db_module.complete_session(self.db_conn, self.session_id)
        self.status_bar.showMessage(f"Scan complete: {len(tracks)} files", 6000)

    # -- MusicBrainz lookup (Phase 2) ---------------------------------------

    def _on_lookup_online(self) -> None:
        tracks = [self.model.track_at(r) for r in range(self.model.rowCount())]
        lookup_candidates = [t for t in tracks if t.status not in _NO_LOOKUP_STATUSES]
        if not lookup_candidates:
            QMessageBox.information(self, "Nothing to look up", "Run a scan first.")
            return

        self.groups = group_into_albums(lookup_candidates)
        self.group_by_path = {t.path: g for g in self.groups for t in g.files}
        self._run_query_worker(self.groups)

    def _on_retry_failed_lookups(self) -> None:
        failed = [g for g in self.groups if g.status == FileStatus.LOOKUP_FAILED]
        if not failed:
            QMessageBox.information(self, "Nothing to retry", "There are no failed lookups.")
            return
        self._run_query_worker(failed)

    def _on_clear_query_cache(self) -> None:
        # A cached result is a verbatim replay of an earlier lookup — if the
        # matching/scoring logic has since been fixed or updated, groups whose
        # exact query was already cached will keep getting the old (pre-fix)
        # answer until the cache is cleared, which looks exactly like "the fix
        # isn't applied" even though the code is correct. There's no automatic
        # invalidation tied to app updates, so this is a manual escape hatch.
        confirm = QMessageBox.question(
            self,
            "Clear lookup cache",
            "Clear all cached MusicBrainz search results? Future lookups will "
            "re-query fresh instead of reusing past answers.",
        )
        if confirm != QMessageBox.Yes:
            return
        removed = db_module.clear_query_cache(self.db_conn)
        self.status_bar.showMessage(f"Cleared {removed} cached lookup result(s).", 6000)

    def _run_query_worker(self, groups: list[AlbumGroup]) -> None:
        self._operation_start_time = None
        worker = QueryWorker(DB_PATH, self.settings.musicbrainz_contact, groups)
        worker.signals.group_processed.connect(self._on_group_processed)
        worker.signals.progress.connect(self._on_lookup_progress)
        worker.signals.finished.connect(self._on_lookup_finished)
        self._track_worker(worker)
        self.thread_pool.start(worker)

    def _on_group_processed(self, group: AlbumGroup) -> None:
        for track in group.files:
            row = self.model.row_for_path(track.path)
            if row is None:
                continue
            updated = self.model.track_at(row)
            updated.status = group.status
            updated.status_detail = group.status_detail
            # For an auto-matched group there's one definitive proposal — preview
            # it in the table immediately, not just the status text. Without this,
            # every column but Status kept showing the original scanned data (the
            # raw, uncorrected artist/title) right up until the match was actually
            # written, which reads as "the match is wrong" when it's really just
            # unrendered — the correction was already found, only invisible.
            if group.status == FileStatus.AUTO_MATCHED:
                proposed = group.proposed.get(track.path)
                if proposed is not None:
                    updated.artist = proposed.artist
                    updated.album_artist = proposed.album_artist
                    updated.album = proposed.album
                    updated.title = proposed.title
                    updated.track_number = proposed.track_number
                    updated.year = proposed.year
                    updated.genre = proposed.genre
                    updated.has_cover_art = proposed.has_cover_art
                    updated.cover_art_bytes = proposed.cover_art_bytes
                    updated.cover_art_mime = proposed.cover_art_mime
            self.model.refresh_row(row)

    def _on_lookup_finished(self) -> None:
        self._hide_progress()
        counts: dict[str, int] = {}
        for group in self.groups:
            counts[group.status.value] = counts.get(group.status.value, 0) + 1
        summary = ", ".join(f"{v} {k}" for k, v in counts.items())
        self.status_bar.showMessage(f"Lookup complete: {summary}", 8000)

    def _on_accept_all_auto_matches(self) -> None:
        auto_groups = [g for g in self.groups if g.status == FileStatus.AUTO_MATCHED]
        if not auto_groups:
            QMessageBox.information(self, "Nothing to accept", "There are no auto-matched groups yet.")
            return

        items: list[tuple[TrackMetadata, TrackMetadata]] = []
        release_id_by_path: dict[Path, str] = {}
        for group in auto_groups:
            for track in group.files:
                proposed = group.proposed.get(track.path)
                if proposed is not None:
                    items.append((track, proposed))
                    if group.chosen_release_id:
                        release_id_by_path[track.path] = group.chosen_release_id

        if not items:
            return

        fields_to_write = {name for name, on in self.settings.fields_to_autofill.items() if on}
        worker = WriteWorker(
            DB_PATH,
            self.session_id,
            items,
            fields_to_write,
            # Clicking "Accept All Auto-Matches" is just as explicit an acceptance
            # as confirming one match in the manual/review form (which already
            # always overwrites) — these are confidently-scored matches the user is
            # deliberately choosing to apply, not a passive "fill in gaps" scan.
            # Gating this behind a separate "overwrite" setting meant a file that
            # already had *some* tag value (even a wrong one, e.g. a YouTube
            # channel name as artist) never actually got corrected by this button,
            # which defeats the entire point of accepting a match.
            overwrite=True,
            dry_run=self.settings.dry_run,
            backup_root=self._backup_root(),
            release_id_by_path=release_id_by_path,
        )
        worker.signals.file_applied.connect(self._on_file_applied)
        worker.signals.progress.connect(self._show_progress)
        worker.signals.finished.connect(self._on_write_finished)
        self._track_worker(worker)
        self.thread_pool.start(worker)

    # -- Manual entry / review -------------------------------------------

    def _on_row_double_clicked(self, index) -> None:
        if not index.isValid():
            return
        # index is in the (possibly sorted/reordered) proxy model's coordinates —
        # must be mapped back to the underlying data model's row before indexing it.
        source_index = self.proxy_model.mapToSource(index)
        current = self.model.track_at(source_index.row())
        group = self.group_by_path.get(current.path)

        if group is not None and group.status == FileStatus.NEEDS_REVIEW:
            self._handle_review(current, group)
            return

        if group is not None and group.status == FileStatus.AUTO_MATCHED and current.path in group.proposed:
            seeded = group.proposed[current.path]
        else:
            seeded = _seed_with_filename_guess(current)

        self._open_manual_entry(current, seeded)

    def _handle_review(self, current: TrackMetadata, group: AlbumGroup) -> None:
        panel = ReviewPanel(group, parent=self)
        if panel.exec() != ReviewPanel.Accepted:
            return

        if panel.want_manual_entry:
            self._open_manual_entry(current, _seed_with_filename_guess(current))
            return

        if panel.chosen is None:
            return

        try:
            proposed_map = resolve_group_to_proposed(group, panel.chosen)
        except MusicBrainzError as exc:
            QMessageBox.warning(self, "Lookup failed", f"Could not fetch full release details: {exc}")
            return

        group.proposed = proposed_map
        group.status = FileStatus.AUTO_MATCHED
        group.chosen_release_id = panel.chosen.release_id
        for track in group.files:
            row = self.model.row_for_path(track.path)
            if row is None:
                continue
            updated = self.model.track_at(row)
            updated.status = FileStatus.AUTO_MATCHED
            self.model.refresh_row(row)

        seeded = proposed_map.get(current.path, current)
        self._open_manual_entry(current, seeded)

    def _open_manual_entry(self, current: TrackMetadata, seeded: TrackMetadata) -> None:
        form = ManualEntryForm(seeded, allow_apply_to_album=True, parent=self)
        if form.exec() != ManualEntryForm.Accepted:
            return

        proposed = form.result_metadata()
        items: list[tuple[TrackMetadata, TrackMetadata]] = [(current, proposed)]

        if form.should_apply_to_album():
            folder = current.path.parent
            for row in range(self.model.rowCount()):
                other = self.model.track_at(row)
                if other.path == current.path or other.path.parent != folder:
                    continue
                album_fields = TrackMetadata(
                    path=other.path,
                    file_format=other.file_format,
                    artist=proposed.artist,
                    album_artist=proposed.album_artist,
                    album=proposed.album,
                    genre=proposed.genre,
                    year=proposed.year,
                    cover_art_bytes=proposed.cover_art_bytes,
                    cover_art_mime=proposed.cover_art_mime,
                    has_cover_art=proposed.has_cover_art,
                    title=other.title,
                    track_number=other.track_number,
                )
                items.append((other, album_fields))

        fields_to_write = {name for name, on in self.settings.fields_to_autofill.items() if on}
        worker = WriteWorker(
            DB_PATH,
            self.session_id,
            items,
            fields_to_write,
            overwrite=True,  # a manual/reviewed edit is an explicit, authoritative user decision
            dry_run=self.settings.dry_run,
            backup_root=self._backup_root(),
        )
        worker.signals.file_applied.connect(self._on_file_applied)
        worker.signals.progress.connect(self._show_progress)
        worker.signals.finished.connect(self._on_write_finished)
        self._track_worker(worker)
        self.thread_pool.start(worker)

    def _on_file_applied(self, current: TrackMetadata, success: bool, error: str) -> None:
        row = self.model.row_for_path(current.path)
        if row is None:
            return
        refreshed = read_tags(current.path)
        if not success:
            refreshed.status = FileStatus.ERROR
            refreshed.status_detail = error
        elif self.settings.dry_run:
            refreshed.status_detail = "dry run: not written"
        else:
            refreshed.status = FileStatus.APPLIED
        self.model.update_track(row, refreshed)

    def _on_write_finished(self, applied: int, failed: int) -> None:
        self._hide_progress()
        suffix = " (dry run — nothing written)" if self.settings.dry_run else ""
        self.status_bar.showMessage(f"Applied {applied}, failed {failed}{suffix}", 6000)

    # -- Duplicates ------------------------------------------------------

    def _on_find_duplicates(self) -> None:
        tracks = [self.model.track_at(r) for r in range(self.model.rowCount())]
        if not tracks:
            QMessageBox.information(self, "No files", "Run a scan first.")
            return
        paths = [t.path for t in tracks]
        groups = find_exact_duplicates(paths) + find_metadata_duplicates(tracks)
        dialog = DuplicatesDialog(groups, parent=self)
        dialog.exec()
        if dialog.deleted_paths:
            remaining = [t for t in tracks if t.path not in dialog.deleted_paths]
            self.model.set_tracks(remaining)
            self.status_bar.showMessage(f"Deleted {len(dialog.deleted_paths)} duplicate file(s)", 6000)

    # -- Undo --------------------------------------------------------------

    def _on_undo_last_batch(self) -> None:
        batch_id = db_module.get_last_batch_id(self.db_conn)
        if not batch_id:
            QMessageBox.information(self, "Nothing to undo", "There is no applied batch to undo.")
            return
        confirm = QMessageBox.question(self, "Undo last batch", "Restore the previous tag values?")
        if confirm != QMessageBox.Yes:
            return
        restored, errors = db_module.undo_batch(self.db_conn, batch_id)
        for row in range(self.model.rowCount()):
            track = self.model.track_at(row)
            refreshed = read_tags(track.path)
            self.model.update_track(row, refreshed)
        message = f"Restored {restored} file(s)"
        if errors:
            message += f", {len(errors)} error(s)"
        self.status_bar.showMessage(message, 6000)
        if errors:
            QMessageBox.warning(self, "Some files could not be restored", "\n".join(errors))

    # -- Settings ------------------------------------------------------

    def _on_open_settings(self) -> None:
        dialog = SettingsDialog(self.settings, parent=self)
        if dialog.exec() == SettingsDialog.Accepted:
            self.settings = dialog.result_settings()
            save_settings(self.settings)

    def _backup_root(self) -> Path | None:
        return (DATA_DIR / "backups") if self.settings.keep_backup_before_write else None

    # -- Worker lifetime -----------------------------------------------

    def _track_worker(self, worker) -> None:
        # QThreadPool doesn't keep a Python reference alive on its own; hold one here
        # until the worker's terminal signal fires so it isn't garbage-collected mid-run.
        self._active_workers.append(worker)
        finished_signal = getattr(worker.signals, "finished", None)
        if finished_signal is not None:
            finished_signal.connect(lambda *_, w=worker: self._active_workers.remove(w))

    def closeEvent(self, event) -> None:
        # A worker (most likely a MusicBrainz lookup, the slowest one) may still be
        # running in a background thread. Letting the window close and the app tear
        # down QObjects out from under it crashes with "Signal source has been
        # deleted" when the worker's next emit() fires — waiting here for the pool to
        # drain avoids that, at the cost of a short pause on quit if a lookup is active.
        if self._active_workers:
            self.status_bar.showMessage("Finishing background work before closing...")
            self.thread_pool.waitForDone(5000)
        super().closeEvent(event)
