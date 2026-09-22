"""Background scan: walks the library folders and reads tags without blocking the UI."""

from __future__ import annotations

from pathlib import Path

from PySide6.QtCore import QObject, QRunnable, Signal, Slot

from musictagger.models import TrackMetadata
from musictagger.scanner.filesystem import scan_folders
from musictagger.tags.reader import read_tags


class ScanWorkerSignals(QObject):
    file_scanned = Signal(object)  # TrackMetadata
    progress = Signal(int, int)  # (files scanned so far, total files found)
    finished = Signal(list)  # list[TrackMetadata]
    error = Signal(str)


class ScanWorker(QRunnable):
    def __init__(self, root_folders: list[Path]):
        super().__init__()
        self.root_folders = root_folders
        self.signals = ScanWorkerSignals()
        self._cancelled = False

    def cancel(self) -> None:
        self._cancelled = True

    @Slot()
    def run(self) -> None:
        results: list[TrackMetadata] = []
        try:
            # A fast first pass — just a directory walk plus an extension check, no
            # tag parsing — counts files up front so the progress bar gets a real
            # total instead of an indeterminate spinner. Parsing tags is what
            # actually takes time, so materializing the path list first only adds
            # a small fraction of overhead.
            all_paths = list(scan_folders(self.root_folders))
            total = len(all_paths)
            for count, path in enumerate(all_paths, start=1):
                if self._cancelled:
                    break
                meta = read_tags(path)
                results.append(meta)
                self.signals.file_scanned.emit(meta)
                self.signals.progress.emit(count, total)
        except Exception as exc:  # a scan-level failure shouldn't crash the app
            self.signals.error.emit(str(exc))
        self.signals.finished.emit(results)
