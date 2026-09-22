"""Manual metadata entry form: the fallback for files with no confident online match
(Phase 2+) and the primary editing tool in Phase 1, before online lookup exists.
"""

from __future__ import annotations

from typing import Optional

from PySide6.QtCore import QBuffer, Qt
from PySide6.QtGui import QPixmap
from PySide6.QtWidgets import (
    QApplication,
    QCheckBox,
    QDialog,
    QDialogButtonBox,
    QFileDialog,
    QFormLayout,
    QHBoxLayout,
    QLabel,
    QLineEdit,
    QPushButton,
    QSpinBox,
    QVBoxLayout,
)

from musictagger.models import MetadataSource, TrackMetadata


class ManualEntryForm(QDialog):
    def __init__(self, track: TrackMetadata, allow_apply_to_album: bool = False, parent=None):
        super().__init__(parent)
        self.setWindowTitle(f"Edit metadata — {track.display_name()}")
        self.track = track

        self.artist_edit = QLineEdit(track.artist or "")
        self.album_artist_edit = QLineEdit(track.album_artist or "")
        self.album_edit = QLineEdit(track.album or "")
        self.title_edit = QLineEdit(track.title or "")
        self.genre_edit = QLineEdit(track.genre or "")

        self.track_spin = QSpinBox()
        self.track_spin.setRange(0, 999)
        self.track_spin.setValue(track.track_number or 0)

        self.year_spin = QSpinBox()
        self.year_spin.setRange(0, 2100)
        self.year_spin.setValue(track.year or 0)

        self.cover_preview = QLabel("No cover art")
        self.cover_preview.setFixedSize(150, 150)
        self.cover_preview.setAlignment(Qt.AlignCenter)
        self._cover_bytes: Optional[bytes] = track.cover_art_bytes
        self._cover_mime: Optional[str] = track.cover_art_mime
        self._refresh_cover_preview()

        upload_btn = QPushButton("Upload image...")
        upload_btn.clicked.connect(self._on_upload_cover)
        paste_btn = QPushButton("Paste from clipboard")
        paste_btn.clicked.connect(self._on_paste_cover)
        cover_buttons = QHBoxLayout()
        cover_buttons.addWidget(upload_btn)
        cover_buttons.addWidget(paste_btn)

        form = QFormLayout()
        form.addRow("Artist", self.artist_edit)
        form.addRow("Album Artist", self.album_artist_edit)
        form.addRow("Album", self.album_edit)
        form.addRow("Title", self.title_edit)
        form.addRow("Genre", self.genre_edit)
        form.addRow("Track #", self.track_spin)
        form.addRow("Year", self.year_spin)

        self.apply_to_album_check = None
        if allow_apply_to_album:
            self.apply_to_album_check = QCheckBox(
                "Apply artist/album artist/album/year/genre/cover to every track in this folder"
            )

        buttons = QDialogButtonBox(QDialogButtonBox.Ok | QDialogButtonBox.Cancel)
        buttons.accepted.connect(self.accept)
        buttons.rejected.connect(self.reject)

        layout = QVBoxLayout(self)
        layout.addLayout(form)
        layout.addWidget(self.cover_preview)
        layout.addLayout(cover_buttons)
        if self.apply_to_album_check is not None:
            layout.addWidget(self.apply_to_album_check)
        layout.addWidget(buttons)

    def _refresh_cover_preview(self) -> None:
        if self._cover_bytes:
            pixmap = QPixmap()
            pixmap.loadFromData(self._cover_bytes)
            self.cover_preview.setPixmap(
                pixmap.scaled(150, 150, Qt.KeepAspectRatio, Qt.SmoothTransformation)
            )
        else:
            self.cover_preview.setPixmap(QPixmap())
            self.cover_preview.setText("No cover art")

    def _on_upload_cover(self) -> None:
        path, _ = QFileDialog.getOpenFileName(
            self, "Choose cover image", "", "Images (*.png *.jpg *.jpeg)"
        )
        if not path:
            return
        with open(path, "rb") as f:
            self._cover_bytes = f.read()
        self._cover_mime = "image/png" if path.lower().endswith(".png") else "image/jpeg"
        self._refresh_cover_preview()

    def _on_paste_cover(self) -> None:
        clipboard = QApplication.clipboard()
        image = clipboard.image()
        if image.isNull():
            return
        buffer = QBuffer()
        buffer.open(QBuffer.WriteOnly)
        image.save(buffer, "PNG")
        self._cover_bytes = bytes(buffer.data())
        self._cover_mime = "image/png"
        self._refresh_cover_preview()

    def result_metadata(self) -> TrackMetadata:
        return TrackMetadata(
            path=self.track.path,
            file_format=self.track.file_format,
            artist=self.artist_edit.text().strip() or None,
            album_artist=self.album_artist_edit.text().strip() or None,
            album=self.album_edit.text().strip() or None,
            title=self.title_edit.text().strip() or None,
            genre=self.genre_edit.text().strip() or None,
            track_number=self.track_spin.value() or None,
            year=self.year_spin.value() or None,
            cover_art_bytes=self._cover_bytes,
            cover_art_mime=self._cover_mime,
            has_cover_art=self._cover_bytes is not None,
            source=MetadataSource.MANUAL_ENTRY,
        )

    def should_apply_to_album(self) -> bool:
        return bool(self.apply_to_album_check and self.apply_to_album_check.isChecked())
