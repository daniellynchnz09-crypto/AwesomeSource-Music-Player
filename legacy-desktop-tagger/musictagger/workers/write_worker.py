"""Background commit: writes user-approved metadata to disk and logs an undo batch."""

from __future__ import annotations

from pathlib import Path
from typing import Optional

from PySide6.QtCore import QObject, QRunnable, Signal, Slot

from musictagger.metadata_sources import cover_art_client
from musictagger.models import TrackMetadata
from musictagger.persistence import db as db_module
from musictagger.tags.writer import write_tags


class WriteWorkerSignals(QObject):
    file_applied = Signal(object, bool, str)  # (current TrackMetadata, success, error)
    progress = Signal(int, int)  # (done, total)
    finished = Signal(int, int)  # (applied_count, failed_count)


class WriteWorker(QRunnable):
    def __init__(
        self,
        db_path: Path,
        session_id: Optional[int],
        items: list[tuple[TrackMetadata, TrackMetadata]],
        fields_to_write: set[str],
        overwrite: bool,
        dry_run: bool,
        backup_root: Optional[Path] = None,
        release_id_by_path: Optional[dict[Path, str]] = None,
    ):
        """items: (current_metadata, approved_new_metadata) pairs the user accepted.
        backup_root, when set, copies each file's pre-edit original there (mirroring
        its full path) before its first write — see tags/writer.py.
        release_id_by_path, when given, fetches cover art just-in-time (here, in the
        background) for any proposed value that doesn't already carry it — the bulk
        "Accept All Auto-Matches" path deliberately skips cover art during the
        rate-limited MusicBrainz search phase (see release_resolver.py), so it's
        fetched here instead, right before it's actually needed. Multiple tracks
        sharing one release (e.g. every file in an album) hit the same URL, which
        cover_art_client caches in-memory, so only the first one per release
        actually pays the network cost.
        """
        super().__init__()
        self.db_path = db_path
        self.session_id = session_id
        self.items = items
        self.fields_to_write = fields_to_write
        self.overwrite = overwrite
        self.dry_run = dry_run
        self.backup_root = backup_root
        self.release_id_by_path = release_id_by_path or {}
        self.signals = WriteWorkerSignals()

    def _ensure_cover_art(self, proposed: TrackMetadata) -> None:
        if proposed.cover_art_bytes is not None or "cover_art" not in self.fields_to_write:
            return
        release_id = self.release_id_by_path.get(proposed.path)
        if not release_id:
            return
        cover = cover_art_client.fetch_full_image(release_id)
        if cover:
            proposed.cover_art_bytes, proposed.cover_art_mime = cover
            proposed.has_cover_art = True

    @Slot()
    def run(self) -> None:
        conn = db_module.get_connection(self.db_path)
        batch_id = db_module.new_batch_id()
        applied = 0
        failed = 0
        total = len(self.items)

        for i, (current, proposed) in enumerate(self.items, start=1):
            self._ensure_cover_art(proposed)
            success, changes, error = write_tags(
                current.path,
                proposed,
                self.fields_to_write,
                self.overwrite,
                dry_run=self.dry_run,
                backup_root=self.backup_root,
            )
            if success and changes and not self.dry_run:
                db_module.record_tag_changes(conn, self.session_id, current.path, changes, batch_id)
            if success:
                applied += 1
            else:
                failed += 1
            self.signals.file_applied.emit(current, success, error)
            self.signals.progress.emit(i, total)

        conn.close()
        self.signals.finished.emit(applied, failed)
