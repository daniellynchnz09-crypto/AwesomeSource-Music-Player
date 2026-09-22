"""Application settings, persisted as JSON under the project's data/ directory."""

from __future__ import annotations

import json
import sys
from dataclasses import asdict, dataclass, field
from pathlib import Path


def _project_root() -> Path:
    """The stable, persistent directory settings/data/backups live next to.

    Under PyInstaller's onefile mode, `sys.frozen` is set and `__file__` resolves
    inside the ephemeral per-launch extraction temp directory (`_MEIxxxxxx`) — using
    that would silently create a fresh, empty settings/data folder (and a
    default library folder that doesn't exist) on every single launch. The actual
    built .exe, by contrast, sits in one stable place, so that's what frozen builds
    must anchor to instead.
    """
    if getattr(sys, "frozen", False):
        return Path(sys.executable).resolve().parent
    return Path(__file__).resolve().parent.parent


PROJECT_ROOT = _project_root()
DATA_DIR = PROJECT_ROOT / "data"
SETTINGS_PATH = DATA_DIR / "settings.json"
DB_PATH = DATA_DIR / "app.db"


@dataclass
class Settings:
    # Deliberately empty, not a guessed "Music" subfolder next to the app — that
    # guess is right for this project's own dev setup but wrong for a packaged exe
    # (which almost never has a Music/ folder next to it), and since Add Folder only
    # appends, a wrong guess stuck around forever as a permanent "folder not found"
    # warning on every scan even after the user added their real folder alongside it.
    library_folders: list[str] = field(default_factory=list)
    fields_to_autofill: dict[str, bool] = field(
        default_factory=lambda: {
            "artist": True,
            "album_artist": True,
            "album": True,
            "title": True,
            "track_number": True,
            "year": True,
            "genre": True,
            "cover_art": True,
        }
    )
    dry_run: bool = False
    keep_backup_before_write: bool = False
    musicbrainz_contact: str = ""

    def to_dict(self) -> dict:
        return asdict(self)

    @classmethod
    def from_dict(cls, data: dict) -> "Settings":
        defaults = cls()
        merged = defaults.to_dict()
        merged.update({k: v for k, v in data.items() if k in merged})
        return cls(**merged)


def load_settings() -> Settings:
    if not SETTINGS_PATH.exists():
        settings = Settings()
        _auto_detect_music_folder(settings)
        save_settings(settings)
        return settings
    try:
        with open(SETTINGS_PATH, "r", encoding="utf-8") as f:
            data = json.load(f)
        settings = Settings.from_dict(data)
    except (json.JSONDecodeError, OSError):
        settings = Settings()
    _auto_detect_music_folder(settings)
    return settings


def _auto_detect_music_folder(settings: Settings) -> None:
    """Dev convenience only, never persisted here: if no library folder is
    configured yet, but a "Music" folder genuinely exists next to wherever this
    app is rooted (the project directory in dev, the .exe's own folder once
    packaged), add it automatically so a fresh dev checkout doesn't need an
    "Add Folder" click just to start testing.

    Requiring the folder to actually exist is what keeps this safe for a real
    packaged/distributed build: a random user's machine essentially never has a
    "Music" folder sitting next to the .exe, so the check simply does nothing
    there and library_folders stays empty, exactly as intended for a shipped app.
    """
    if settings.library_folders:
        return
    music_folder = PROJECT_ROOT / "Music"
    if music_folder.exists():
        settings.library_folders.append(str(music_folder))


def save_settings(settings: Settings) -> None:
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    with open(SETTINGS_PATH, "w", encoding="utf-8") as f:
        json.dump(settings.to_dict(), f, indent=2)
