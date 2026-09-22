"""SQLite persistence: the undo log (primary reversibility mechanism), scan-session
bookkeeping, and a MusicBrainz query cache (used starting Phase 2).

The undo log is deliberately simple: every write batch (one "Apply"/"Accept" action
in the UI) gets a batch_id (a uuid4 string), and every field actually changed in that
batch gets one row recording the file, field, old value, and new value. Undoing a
batch replays the old values back onto disk via tags.writer.apply_raw_fields and
marks the rows undone.
"""

from __future__ import annotations

import json
import sqlite3
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Optional

from musictagger.tags.writer import apply_raw_fields

SCHEMA = """
CREATE TABLE IF NOT EXISTS scan_sessions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    root_folders TEXT NOT NULL,
    started_at TEXT NOT NULL,
    completed_at TEXT,
    status TEXT NOT NULL DEFAULT 'in_progress'
);

CREATE TABLE IF NOT EXISTS tag_changes (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id INTEGER,
    file_path TEXT NOT NULL,
    field_name TEXT NOT NULL,
    old_value TEXT,
    new_value TEXT,
    batch_id TEXT NOT NULL,
    applied_at TEXT NOT NULL,
    undone INTEGER NOT NULL DEFAULT 0,
    FOREIGN KEY (session_id) REFERENCES scan_sessions(id)
);

CREATE TABLE IF NOT EXISTS mb_query_cache (
    query_key TEXT PRIMARY KEY,
    response_json TEXT NOT NULL,
    cached_at TEXT NOT NULL
);
"""


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()


def get_connection(db_path: Path) -> sqlite3.Connection:
    db_path.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(str(db_path))
    conn.execute("PRAGMA foreign_keys = ON")
    conn.executescript(SCHEMA)
    return conn


def start_session(conn: sqlite3.Connection, root_folders: list[str]) -> int:
    cur = conn.execute(
        "INSERT INTO scan_sessions (root_folders, started_at, status) VALUES (?, ?, 'in_progress')",
        (json.dumps(root_folders), _now()),
    )
    conn.commit()
    return cur.lastrowid


def complete_session(conn: sqlite3.Connection, session_id: int) -> None:
    conn.execute(
        "UPDATE scan_sessions SET completed_at = ?, status = 'completed' WHERE id = ?",
        (_now(), session_id),
    )
    conn.commit()


def new_batch_id() -> str:
    return uuid.uuid4().hex


def record_tag_changes(
    conn: sqlite3.Connection,
    session_id: Optional[int],
    file_path: Path,
    changes: list[tuple[str, Optional[str], Optional[str]]],
    batch_id: str,
) -> None:
    if not changes:
        return
    now = _now()
    conn.executemany(
        """INSERT INTO tag_changes
           (session_id, file_path, field_name, old_value, new_value, batch_id, applied_at)
           VALUES (?, ?, ?, ?, ?, ?, ?)""",
        [(session_id, str(file_path), field, old, new, batch_id, now) for field, old, new in changes],
    )
    conn.commit()


def get_last_batch_id(conn: sqlite3.Connection) -> Optional[str]:
    row = conn.execute(
        "SELECT batch_id FROM tag_changes WHERE undone = 0 ORDER BY id DESC LIMIT 1"
    ).fetchone()
    return row[0] if row else None


def get_recent_batch_ids(conn: sqlite3.Connection, limit: int = 20) -> list[str]:
    rows = conn.execute(
        """SELECT DISTINCT batch_id FROM tag_changes WHERE undone = 0
           ORDER BY id DESC LIMIT ?""",
        (limit,),
    ).fetchall()
    return [r[0] for r in rows]


def undo_batch(conn: sqlite3.Connection, batch_id: str) -> tuple[int, list[str]]:
    """Restores every non-cover-art field change in batch_id back to its old value.

    Returns (files_restored_count, error_messages). A file that fails to restore is
    reported in error_messages but does not stop the rest of the batch from undoing.
    """
    rows = conn.execute(
        """SELECT file_path, field_name, old_value FROM tag_changes
           WHERE batch_id = ? AND undone = 0 AND field_name != 'cover_art'""",
        (batch_id,),
    ).fetchall()

    by_file: dict[str, dict[str, Optional[str]]] = {}
    for file_path, field_name, old_value in rows:
        by_file.setdefault(file_path, {})[field_name] = old_value

    errors: list[str] = []
    restored = 0
    for file_path, field_values in by_file.items():
        success, error = apply_raw_fields(Path(file_path), field_values)
        if success:
            restored += 1
        else:
            errors.append(f"{file_path}: {error}")

    conn.execute("UPDATE tag_changes SET undone = 1 WHERE batch_id = ?", (batch_id,))
    conn.commit()
    return restored, errors


def get_cached_query(conn: sqlite3.Connection, query_key: str) -> Optional[str]:
    row = conn.execute(
        "SELECT response_json FROM mb_query_cache WHERE query_key = ?", (query_key,)
    ).fetchone()
    return row[0] if row else None


def set_cached_query(conn: sqlite3.Connection, query_key: str, response_json: str) -> None:
    conn.execute(
        """INSERT INTO mb_query_cache (query_key, response_json, cached_at) VALUES (?, ?, ?)
           ON CONFLICT(query_key) DO UPDATE SET response_json = excluded.response_json,
                                                 cached_at = excluded.cached_at""",
        (query_key, response_json, _now()),
    )
    conn.commit()


def clear_query_cache(conn: sqlite3.Connection) -> int:
    """Wipes every cached MusicBrainz query result and returns how many were
    removed. A cached result is a verbatim replay of whatever the matching logic
    decided at the time it was stored — if that logic later changes (a scoring fix,
    say), old cache entries keep serving the pre-fix answer for any query already
    seen, silently masking the fix until they're cleared. There's no automatic
    invalidation tied to code changes, so this is exposed as a manual action."""
    cursor = conn.execute("DELETE FROM mb_query_cache")
    conn.commit()
    return cursor.rowcount
