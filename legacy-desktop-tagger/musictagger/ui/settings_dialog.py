"""Settings screen: library folders, auto-fill field toggles, safety toggles, and
the MusicBrainz contact string.

There's no "only fill blanks / overwrite" write-policy choice here — every action
that actually writes tags (manual entry, review-panel acceptance, "Accept All
Auto-Matches") is already an explicit user decision to apply a specific value, so
it always overwrites. Uncheck a field in "Fields to auto-fill" below if you don't
want that field touched at all, ever.
"""

from __future__ import annotations

from PySide6.QtWidgets import (
    QCheckBox,
    QDialog,
    QDialogButtonBox,
    QFileDialog,
    QFormLayout,
    QGroupBox,
    QHBoxLayout,
    QLineEdit,
    QListWidget,
    QPushButton,
    QVBoxLayout,
)

from musictagger.config import Settings


class SettingsDialog(QDialog):
    def __init__(self, settings: Settings, parent=None):
        super().__init__(parent)
        self.setWindowTitle("Settings")
        self.settings = settings

        self.folder_list = QListWidget()
        self.folder_list.addItems(settings.library_folders)
        add_folder_btn = QPushButton("Add Folder...")
        add_folder_btn.clicked.connect(self._on_add_folder)
        remove_folder_btn = QPushButton("Remove Selected")
        remove_folder_btn.clicked.connect(self._on_remove_folder)
        folder_buttons = QHBoxLayout()
        folder_buttons.addWidget(add_folder_btn)
        folder_buttons.addWidget(remove_folder_btn)

        folders_box = QGroupBox("Library folders")
        folders_layout = QVBoxLayout()
        folders_layout.addWidget(self.folder_list)
        folders_layout.addLayout(folder_buttons)
        folders_box.setLayout(folders_layout)

        self.field_checks: dict[str, QCheckBox] = {}
        fields_box = QGroupBox("Fields to auto-fill")
        fields_layout = QVBoxLayout()
        for field_name, enabled in settings.fields_to_autofill.items():
            cb = QCheckBox(field_name.replace("_", " ").title())
            cb.setChecked(enabled)
            self.field_checks[field_name] = cb
            fields_layout.addWidget(cb)
        fields_box.setLayout(fields_layout)

        self.dry_run_check = QCheckBox("Dry run (preview only, never write to disk)")
        self.dry_run_check.setChecked(settings.dry_run)
        self.backup_check = QCheckBox("Keep a backup copy of each file before its first edit")
        self.backup_check.setChecked(settings.keep_backup_before_write)
        safety_box = QGroupBox("Safety")
        safety_layout = QVBoxLayout()
        safety_layout.addWidget(self.dry_run_check)
        safety_layout.addWidget(self.backup_check)
        safety_box.setLayout(safety_layout)

        self.contact_edit = QLineEdit(settings.musicbrainz_contact)
        self.contact_edit.setPlaceholderText(
            "Contact info shown to MusicBrainz per their API etiquette (Phase 2)"
        )
        mb_form = QFormLayout()
        mb_form.addRow("MusicBrainz contact info", self.contact_edit)

        buttons = QDialogButtonBox(QDialogButtonBox.Ok | QDialogButtonBox.Cancel)
        buttons.accepted.connect(self.accept)
        buttons.rejected.connect(self.reject)

        layout = QVBoxLayout(self)
        layout.addWidget(folders_box)
        layout.addWidget(fields_box)
        layout.addWidget(safety_box)
        layout.addLayout(mb_form)
        layout.addWidget(buttons)

    def _on_add_folder(self) -> None:
        path = QFileDialog.getExistingDirectory(self, "Choose a library folder")
        if path:
            self.folder_list.addItem(path)

    def _on_remove_folder(self) -> None:
        for item in self.folder_list.selectedItems():
            self.folder_list.takeItem(self.folder_list.row(item))

    def result_settings(self) -> Settings:
        self.settings.library_folders = [
            self.folder_list.item(i).text() for i in range(self.folder_list.count())
        ]
        self.settings.fields_to_autofill = {
            name: cb.isChecked() for name, cb in self.field_checks.items()
        }
        self.settings.dry_run = self.dry_run_check.isChecked()
        self.settings.keep_backup_before_write = self.backup_check.isChecked()
        self.settings.musicbrainz_contact = self.contact_edit.text().strip()
        return self.settings
