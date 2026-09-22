"""Entry point: launches the Qt application and main window."""

from __future__ import annotations

import sys

from PySide6.QtGui import QColor, QPalette
from PySide6.QtWidgets import QApplication

from musictagger.ui.main_window import MainWindow


def _apply_light_palette(app: QApplication) -> None:
    """Forces a plain white-background/black-text look regardless of the OS theme.

    Without this, a system in dark mode leaks its dark palette into Qt's default
    widget colors — the table's per-status background colors (light pastels) are set
    explicitly, but the *text* color was left to inherit the OS palette, so dark-mode
    Windows rendered white text on those light backgrounds (unreadable "white on
    white" in the worst cases, like the plain white table background before any scan).
    """
    app.setStyle("Fusion")
    palette = QPalette()
    white = QColor("#ffffff")
    black = QColor("#000000")
    light_gray = QColor("#f0f0f0")
    mid_gray = QColor("#d0d0d0")
    highlight = QColor("#3874d8")

    palette.setColor(QPalette.Window, white)
    palette.setColor(QPalette.WindowText, black)
    palette.setColor(QPalette.Base, white)
    palette.setColor(QPalette.AlternateBase, light_gray)
    palette.setColor(QPalette.ToolTipBase, white)
    palette.setColor(QPalette.ToolTipText, black)
    palette.setColor(QPalette.Text, black)
    palette.setColor(QPalette.Button, light_gray)
    palette.setColor(QPalette.ButtonText, black)
    palette.setColor(QPalette.BrightText, black)
    palette.setColor(QPalette.Link, highlight)
    palette.setColor(QPalette.Highlight, highlight)
    palette.setColor(QPalette.HighlightedText, white)
    palette.setColor(QPalette.PlaceholderText, QColor("#707070"))
    palette.setColor(QPalette.Disabled, QPalette.Text, QColor("#a0a0a0"))
    palette.setColor(QPalette.Disabled, QPalette.WindowText, QColor("#a0a0a0"))
    palette.setColor(QPalette.Disabled, QPalette.Button, mid_gray)
    app.setPalette(palette)


def main() -> int:
    app = QApplication(sys.argv)
    _apply_light_palette(app)
    window = MainWindow()
    window.show()
    return app.exec()


if __name__ == "__main__":
    sys.exit(main())
