"""Review/diff panel for a "needs review" album group: lets the user pick among
MusicBrainz candidates, re-run the search with corrected text, or bail out to the
manual entry form instead. Nothing is written to disk from here — accepting just
hands the chosen candidate back to the caller, which resolves and writes it exactly
like an auto-matched group.
"""

from __future__ import annotations

from PySide6.QtWidgets import (
    QButtonGroup,
    QDialog,
    QHBoxLayout,
    QLabel,
    QLineEdit,
    QMessageBox,
    QPushButton,
    QRadioButton,
    QScrollArea,
    QVBoxLayout,
    QWidget,
)

from musictagger.matching.scorer import score_candidates
from musictagger.metadata_sources import musicbrainz_client
from musictagger.metadata_sources.musicbrainz_client import MusicBrainzError
from musictagger.models import AlbumGroup, MBCandidate

_MAX_CANDIDATES_SHOWN = 8


class ReviewPanel(QDialog):
    def __init__(self, group: AlbumGroup, parent=None):
        super().__init__(parent)
        self.group = group
        self.chosen: MBCandidate | None = None
        self.want_manual_entry = False

        subject = group.best_guess_album or (group.files[0].title or group.files[0].display_name())
        self.setWindowTitle(f"Review match — {group.best_guess_artist or 'Unknown'} / {subject}")
        self.resize(600, 480)

        layout = QVBoxLayout(self)
        info = QLabel(
            f"{len(group.files)} file(s) in this group. Pick the correct release below, "
            f"search again with corrected text, or edit manually instead."
        )
        info.setWordWrap(True)
        layout.addWidget(info)

        self.scroll = QScrollArea()
        self.scroll.setWidgetResizable(True)
        layout.addWidget(self.scroll)
        self.button_group: QButtonGroup | None = None
        self._radio_by_id: dict[int, MBCandidate] = {}
        self._populate_candidates(group.candidates)

        search_row = QHBoxLayout()
        self.artist_edit = QLineEdit(group.best_guess_artist or "")
        self.query_edit = QLineEdit(group.best_guess_album or (group.files[0].title or ""))
        search_btn = QPushButton("Search Again")
        search_btn.clicked.connect(self._on_search_again)
        search_row.addWidget(QLabel("Artist:"))
        search_row.addWidget(self.artist_edit)
        search_row.addWidget(QLabel("Album/Title:"))
        search_row.addWidget(self.query_edit)
        search_row.addWidget(search_btn)
        layout.addLayout(search_row)

        button_row = QHBoxLayout()
        accept_btn = QPushButton("Accept Selected")
        accept_btn.clicked.connect(self._on_accept)
        manual_btn = QPushButton("Edit Manually Instead")
        manual_btn.clicked.connect(self._on_manual)
        cancel_btn = QPushButton("Cancel")
        cancel_btn.clicked.connect(self.reject)
        button_row.addWidget(accept_btn)
        button_row.addWidget(manual_btn)
        button_row.addWidget(cancel_btn)
        layout.addLayout(button_row)

    def _populate_candidates(self, candidates: list[MBCandidate]) -> None:
        container = QWidget()
        container_layout = QVBoxLayout(container)
        self.button_group = QButtonGroup(container)
        self._radio_by_id = {}

        if not candidates:
            container_layout.addWidget(QLabel("No candidates. Try 'Search Again' with different text."))
        for i, candidate in enumerate(candidates[:_MAX_CANDIDATES_SHOWN]):
            label = f"{candidate.title}  —  {candidate.artist_credit}"
            if candidate.first_release_date:
                label += f"  ({candidate.first_release_date[:4]})"
            if candidate.track_count:
                label += f"  [{candidate.track_count} tracks]"
            label += f"   score={candidate.score:.0f}"
            radio = QRadioButton(label)
            self.button_group.addButton(radio, i)
            self._radio_by_id[i] = candidate
            container_layout.addWidget(radio)
        container_layout.addStretch(1)
        self.scroll.setWidget(container)

    def _on_search_again(self) -> None:
        artist = self.artist_edit.text().strip()
        query = self.query_edit.text().strip()
        if not artist or not query:
            return
        try:
            if self.group.is_singleton:
                candidates = musicbrainz_client.search_recording_candidates(artist, query)
            else:
                candidates = musicbrainz_client.search_release_candidates(artist, query)
        except MusicBrainzError as exc:
            QMessageBox.warning(self, "Search failed", str(exc))
            return
        ranked = score_candidates(artist, query, len(self.group.files), None, candidates)
        self.group.candidates = ranked
        self._populate_candidates(ranked)

    def _on_accept(self) -> None:
        if self.button_group is None:
            return
        checked_id = self.button_group.checkedId()
        if checked_id == -1:
            QMessageBox.information(self, "No selection", "Select a candidate first, or use manual entry.")
            return
        self.chosen = self._radio_by_id.get(checked_id)
        self.accept()

    def _on_manual(self) -> None:
        self.want_manual_entry = True
        self.accept()
