"""Cover Art Archive lookups, keyed by MusicBrainz release ID.

A 404 here is common and expected — most releases, especially older or obscure
ones, simply have no art archived — so it's treated as "no cover art available",
not an error. Thumbnails (front-500) are fetched for UI previews; the full-res image
is only fetched once the user actually commits a match, to avoid downloading large
images for candidates that get rejected.
"""

from __future__ import annotations

from typing import Optional

import requests

_TIMEOUT_SECONDS = 15

# In-memory only (cleared on restart) — the same release can otherwise be fetched
# twice in one session, e.g. once for a review-panel preview and again when the
# user's pick is resolved into a proposed write.
_cache: dict[str, Optional[tuple[bytes, str]]] = {}


def _fetch(url: str) -> Optional[tuple[bytes, str]]:
    if url in _cache:
        return _cache[url]
    try:
        response = requests.get(url, timeout=_TIMEOUT_SECONDS, allow_redirects=True)
    except requests.RequestException:
        return None
    if response.status_code == 404:
        _cache[url] = None
        return None
    try:
        response.raise_for_status()
    except requests.RequestException:
        return None
    mime = response.headers.get("Content-Type", "image/jpeg").split(";")[0].strip()
    result = (response.content, mime)
    _cache[url] = result
    return result


def fetch_thumbnail(release_id: str) -> Optional[tuple[bytes, str]]:
    return _fetch(f"https://coverartarchive.org/release/{release_id}/front-500")


def fetch_full_image(release_id: str) -> Optional[tuple[bytes, str]]:
    return _fetch(f"https://coverartarchive.org/release/{release_id}/front")
