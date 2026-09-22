"""QAbstractTableModel exposing scanned tracks, with status-colored rows."""

from __future__ import annotations

from PySide6.QtCore import QAbstractTableModel, QModelIndex, Qt
from PySide6.QtGui import QColor

from musictagger.models import FileStatus, TrackMetadata

COLUMNS = ["File", "Folder", "Artist", "Album", "Title", "Track#", "Year", "Status"]

_STATUS_COLORS = {
    FileStatus.APPLIED: QColor("#c6f6c6"),
    FileStatus.TAGS_READ: QColor("#eef2ff"),
    FileStatus.AUTO_MATCHED: QColor("#c6f0e6"),
    FileStatus.NEEDS_REVIEW: QColor("#fff6c6"),
    FileStatus.MANUAL_PENDING: QColor("#fff6c6"),
    FileStatus.INSUFFICIENT_INFO: QColor("#ffe3c6"),
    FileStatus.NO_MATCH: QColor("#f0e6c6"),
    FileStatus.LOOKUP_FAILED: QColor("#e6c6f6"),
    FileStatus.UNREADABLE: QColor("#f6c6c6"),
    FileStatus.ERROR: QColor("#f6c6c6"),
    FileStatus.SKIPPED: QColor("#e4e4e4"),
    FileStatus.PENDING: QColor("#ffffff"),
}


class FileTableModel(QAbstractTableModel):
    def __init__(self, tracks: list[TrackMetadata] | None = None, parent=None):
        super().__init__(parent)
        self._tracks: list[TrackMetadata] = tracks or []

    def set_tracks(self, tracks: list[TrackMetadata]) -> None:
        self.beginResetModel()
        self._tracks = tracks
        self.endResetModel()

    def add_track(self, track: TrackMetadata) -> None:
        row = len(self._tracks)
        self.beginInsertRows(QModelIndex(), row, row)
        self._tracks.append(track)
        self.endInsertRows()

    def track_at(self, row: int) -> TrackMetadata:
        return self._tracks[row]

    def row_for_path(self, path) -> int | None:
        for i, track in enumerate(self._tracks):
            if track.path == path:
                return i
        return None

    def refresh_row(self, row: int) -> None:
        top_left = self.index(row, 0)
        bottom_right = self.index(row, len(COLUMNS) - 1)
        self.dataChanged.emit(top_left, bottom_right)

    def update_track(self, row: int, track: TrackMetadata) -> None:
        self._tracks[row] = track
        self.refresh_row(row)

    def rowCount(self, parent: QModelIndex = QModelIndex()) -> int:
        return 0 if parent.isValid() else len(self._tracks)

    def columnCount(self, parent: QModelIndex = QModelIndex()) -> int:
        return 0 if parent.isValid() else len(COLUMNS)

    def headerData(self, section: int, orientation, role=Qt.DisplayRole):
        if role == Qt.DisplayRole and orientation == Qt.Horizontal:
            return COLUMNS[section]
        return None

    def data(self, index: QModelIndex, role: int = Qt.DisplayRole):
        if not index.isValid():
            return None
        track = self._tracks[index.row()]
        col = index.column()
        if role == Qt.DisplayRole:
            values = [
                track.display_name(),
                str(track.path.parent),
                track.artist or "",
                track.album or "",
                track.title or "",
                str(track.track_number) if track.track_number else "",
                str(track.year) if track.year else "",
                track.status.value,
            ]
            return values[col]
        if role == Qt.BackgroundRole:
            return _STATUS_COLORS.get(track.status, QColor("#ffffff"))
        return None
