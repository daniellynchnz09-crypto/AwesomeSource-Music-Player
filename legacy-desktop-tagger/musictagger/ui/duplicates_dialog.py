"""Duplicates view: shows detected duplicate groups and lets the user choose which
copies to send to the Recycle Bin. Nothing is deleted without explicit confirmation,
and deletion always goes through send2trash so it stays recoverable outside the app.
"""

from __future__ import annotations

from pathlib import Path

from PySide6.QtWidgets import (
    QCheckBox,
    QDialog,
    QGroupBox,
    QHBoxLayout,
    QLabel,
    QMessageBox,
    QPushButton,
    QScrollArea,
    QVBoxLayout,
    QWidget,
)
from send2trash import send2trash

from musictagger.dedup.duplicate_finder import DuplicateGroup


class DuplicatesDialog(QDialog):
    def __init__(self, groups: list[DuplicateGroup], parent=None):
        super().__init__(parent)
        self.setWindowTitle("Duplicate songs")
        self.resize(650, 500)
        self._checkboxes: dict[Path, QCheckBox] = {}
        self.deleted_paths: list[Path] = []

        outer = QVBoxLayout(self)
        if not groups:
            outer.addWidget(QLabel("No duplicate songs were found."))
        else:
            outer.addWidget(
                QLabel(
                    "Review each group below and check any copies you want sent to the "
                    "Recycle Bin. Nothing is deleted until you click 'Delete checked'."
                )
            )
            scroll = QScrollArea()
            scroll.setWidgetResizable(True)
            container = QWidget()
            container_layout = QVBoxLayout(container)
            for i, group in enumerate(groups, start=1):
                container_layout.addWidget(self._build_group_box(i, group))
            container_layout.addStretch(1)
            scroll.setWidget(container)
            outer.addWidget(scroll)

        button_row = QHBoxLayout()
        delete_btn = QPushButton("Delete checked (to Recycle Bin)")
        delete_btn.clicked.connect(self._on_delete_checked)
        close_btn = QPushButton("Close")
        close_btn.clicked.connect(self.accept)
        button_row.addWidget(delete_btn)
        button_row.addWidget(close_btn)
        outer.addLayout(button_row)

    def _build_group_box(self, index: int, group: DuplicateGroup) -> QGroupBox:
        box = QGroupBox(f"Group {index} — {group.reason} match, {len(group.files)} copies")
        layout = QVBoxLayout()
        sizes: dict[Path, int] = {}
        for path in group.files:
            try:
                sizes[path] = path.stat().st_size
            except OSError:
                sizes[path] = 0
        likely_keeper = max(sizes, key=sizes.get) if sizes else None
        for path in group.files:
            size_kb = sizes.get(path, 0) // 1024
            label_text = f"{path}  ({size_kb} KB)"
            if path == likely_keeper:
                label_text += "   [suggested keep: largest file]"
            cb = QCheckBox(label_text)
            self._checkboxes[path] = cb
            layout.addWidget(cb)
        box.setLayout(layout)
        return box

    def _on_delete_checked(self) -> None:
        to_delete = [path for path, cb in self._checkboxes.items() if cb.isChecked()]
        if not to_delete:
            return
        confirm = QMessageBox.question(
            self,
            "Confirm deletion",
            f"Send {len(to_delete)} file(s) to the Recycle Bin?",
        )
        if confirm != QMessageBox.Yes:
            return

        errors = []
        for path in to_delete:
            try:
                send2trash(str(path))
                self.deleted_paths.append(path)
            except Exception as exc:
                errors.append(f"{path}: {exc}")

        for path in self.deleted_paths:
            cb = self._checkboxes.pop(path, None)
            if cb is not None:
                cb.setParent(None)

        if errors:
            QMessageBox.warning(self, "Some deletions failed", "\n".join(errors))
