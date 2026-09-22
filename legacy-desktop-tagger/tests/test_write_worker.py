from pathlib import Path

from musictagger.models import TrackMetadata
from musictagger.workers.write_worker import WriteWorker


def _worker(fields_to_write, release_id_by_path):
    return WriteWorker(
        db_path=Path("unused.db"),
        session_id=None,
        items=[],
        fields_to_write=fields_to_write,
        overwrite=True,
        dry_run=True,
        release_id_by_path=release_id_by_path,
    )


def test_ensure_cover_art_fetches_when_missing_and_release_id_known(monkeypatch):
    calls = []

    def spy_fetch(release_id):
        calls.append(release_id)
        return (b"cover-bytes", "image/jpeg")

    monkeypatch.setattr("musictagger.workers.write_worker.cover_art_client.fetch_full_image", spy_fetch)

    proposed = TrackMetadata(path=Path("song.mp3"), title="Song")
    worker = _worker({"cover_art"}, {Path("song.mp3"): "release-123"})
    worker._ensure_cover_art(proposed)

    assert calls == ["release-123"]
    assert proposed.cover_art_bytes == b"cover-bytes"
    assert proposed.cover_art_mime == "image/jpeg"
    assert proposed.has_cover_art is True


def test_ensure_cover_art_skips_when_already_present(monkeypatch):
    calls = []
    monkeypatch.setattr(
        "musictagger.workers.write_worker.cover_art_client.fetch_full_image",
        lambda release_id: calls.append(release_id) or (b"x", "image/jpeg"),
    )

    proposed = TrackMetadata(path=Path("song.mp3"), title="Song", cover_art_bytes=b"already-have-one")
    worker = _worker({"cover_art"}, {Path("song.mp3"): "release-123"})
    worker._ensure_cover_art(proposed)

    assert calls == []


def test_ensure_cover_art_skips_when_field_not_selected(monkeypatch):
    calls = []
    monkeypatch.setattr(
        "musictagger.workers.write_worker.cover_art_client.fetch_full_image",
        lambda release_id: calls.append(release_id) or (b"x", "image/jpeg"),
    )

    proposed = TrackMetadata(path=Path("song.mp3"), title="Song")
    worker = _worker({"artist", "title"}, {Path("song.mp3"): "release-123"})
    worker._ensure_cover_art(proposed)

    assert calls == []


def test_ensure_cover_art_skips_when_no_release_id_known(monkeypatch):
    calls = []
    monkeypatch.setattr(
        "musictagger.workers.write_worker.cover_art_client.fetch_full_image",
        lambda release_id: calls.append(release_id) or (b"x", "image/jpeg"),
    )

    proposed = TrackMetadata(path=Path("song.mp3"), title="Song")
    worker = _worker({"cover_art"}, {})
    worker._ensure_cover_art(proposed)

    assert calls == []
