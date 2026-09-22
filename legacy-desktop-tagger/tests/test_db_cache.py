from pathlib import Path

from musictagger.persistence import db as db_module


def test_clear_query_cache_removes_all_entries_and_reports_count(tmp_path: Path):
    conn = db_module.get_connection(tmp_path / "test.db")
    db_module.set_cached_query(conn, "release|artist a|album a", '{"stale": true}')
    db_module.set_cached_query(conn, "recording|artist b|title b", '{"stale": true}')

    assert db_module.get_cached_query(conn, "release|artist a|album a") is not None

    removed = db_module.clear_query_cache(conn)

    assert removed == 2
    assert db_module.get_cached_query(conn, "release|artist a|album a") is None
    assert db_module.get_cached_query(conn, "recording|artist b|title b") is None


def test_clear_query_cache_on_empty_cache_returns_zero(tmp_path: Path):
    conn = db_module.get_connection(tmp_path / "test.db")
    assert db_module.clear_query_cache(conn) == 0
