import shutil
from pathlib import Path

import pytest

from musictagger.models import TrackMetadata
from musictagger.persistence import db as db_module
from musictagger.scanner.filesystem import scan_folder
from musictagger.tags.reader import read_tags
from musictagger.tags.writer import write_tags

MUSIC_DIR = Path(__file__).resolve().parent.parent / "Music"


def _first_real_mp3() -> Path | None:
    if not MUSIC_DIR.exists():
        return None
    return next((p for p in scan_folder(MUSIC_DIR) if p.suffix.lower() == ".mp3"), None)


@pytest.fixture
def sample_mp3(tmp_path: Path) -> Path:
    source = _first_real_mp3()
    if source is None:
        pytest.skip("no real .mp3 file available under Music/ to copy for this test")
    dest = tmp_path / source.name
    shutil.copy2(source, dest)
    return dest


def test_write_then_undo_round_trips_exactly(sample_mp3: Path):
    before = read_tags(sample_mp3)

    proposed = TrackMetadata(
        path=sample_mp3, artist="Test Artist", album="Test Album", title="Test Title", year=2020
    )
    success, changes, error = write_tags(
        sample_mp3, proposed, {"artist", "album", "title", "year"}, overwrite=True
    )
    assert success, error
    assert changes  # at least one field actually changed

    after = read_tags(sample_mp3)
    assert after.artist == "Test Artist"
    assert after.album == "Test Album"
    assert after.title == "Test Title"
    assert after.year == 2020

    conn = db_module.get_connection(sample_mp3.parent / "test.db")
    batch_id = db_module.new_batch_id()
    db_module.record_tag_changes(conn, None, sample_mp3, changes, batch_id)
    restored, errors = db_module.undo_batch(conn, batch_id)
    assert restored == 1
    assert errors == []

    reverted = read_tags(sample_mp3)
    assert reverted.artist == before.artist
    assert reverted.album == before.album
    assert reverted.title == before.title


def test_wav_writing_is_refused_with_a_clear_message():
    success, changes, error = write_tags(
        Path("nonexistent.wav"),
        TrackMetadata(path=Path("nonexistent.wav"), artist="X"),
        {"artist"},
        overwrite=True,
    )
    assert not success
    assert changes == []
    assert "not supported" in error.lower()


def test_no_changes_when_nothing_differs(sample_mp3: Path):
    before = read_tags(sample_mp3)
    success, changes, error = write_tags(sample_mp3, before, {"artist", "album", "title"}, overwrite=True)
    assert success
    assert changes == []
    assert error == ""


def test_dry_run_does_not_touch_the_file(sample_mp3: Path):
    mtime_before = sample_mp3.stat().st_mtime
    proposed = TrackMetadata(path=sample_mp3, artist="Dry Run Artist")
    success, changes, error = write_tags(sample_mp3, proposed, {"artist"}, overwrite=True, dry_run=True)
    assert success
    assert changes  # would have changed something
    assert sample_mp3.stat().st_mtime == mtime_before
    assert read_tags(sample_mp3).artist != "Dry Run Artist"


def test_backup_root_preserves_the_original_before_first_edit(sample_mp3: Path, tmp_path: Path):
    before = read_tags(sample_mp3)
    backup_root = tmp_path / "backups"

    proposed = TrackMetadata(path=sample_mp3, artist="Backed Up Artist")
    success, changes, error = write_tags(
        sample_mp3, proposed, {"artist"}, overwrite=True, backup_root=backup_root
    )
    assert success, error
    assert changes

    backups = list(backup_root.rglob(sample_mp3.name))
    assert len(backups) == 1
    assert read_tags(backups[0]).artist == before.artist  # backup holds the pre-edit value

    # A second edit must not overwrite the backup with the now-modified file.
    second_proposed = TrackMetadata(path=sample_mp3, artist="Second Edit Artist")
    write_tags(sample_mp3, second_proposed, {"artist"}, overwrite=True, backup_root=backup_root)
    assert read_tags(backups[0]).artist == before.artist
