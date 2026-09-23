/**
 * Helpers treating a `TrackMetadata.path` string as a POSIX-style path *relative to
 * the user's chosen library root folder*, e.g. "EDM/SVDDEN DEATH - ACT I.mp3".
 *
 * The legacy desktop tool used Python's `pathlib.Path` directly for both real
 * filesystem access and path-string logic (`.stem`, `.parent`, `.name`). On a phone,
 * a user-picked folder is only reachable through `expo-document-picker`/SAF
 * `content://` URIs, not a raw filesystem path - so `TrackMetadata.uri` carries the
 * real, openable URI for I/O, and these plain-string helpers carry the path-string
 * logic every ported algorithm (filename parser, album grouper) actually needs. This
 * keeps the ported logic almost line-for-line comparable to the Python original.
 */

/** Equivalent to Python's `Path.name`. */
export function pathName(path: string): string {
  const idx = path.lastIndexOf('/');
  return idx < 0 ? path : path.slice(idx + 1);
}

/** Equivalent to Python's `Path.stem`. */
export function pathStem(path: string): string {
  const name = pathName(path);
  const dot = name.lastIndexOf('.');
  return dot > 0 ? name.slice(0, dot) : name;
}

/** Equivalent to Python's `Path.suffix` (including the leading dot, lowercased). */
export function pathSuffix(path: string): string {
  const name = pathName(path);
  const dot = name.lastIndexOf('.');
  return dot > 0 ? name.slice(dot).toLowerCase() : '';
}

/** Equivalent to Python's `Path.parent`; undefined at the library root. */
export function pathParent(path: string): string | undefined {
  const idx = path.lastIndexOf('/');
  return idx < 0 ? undefined : path.slice(0, idx);
}

export function parentName(path: string): string {
  const parent = pathParent(path);
  return parent === undefined ? '' : pathName(parent);
}

export function grandparentName(path: string): string {
  const parent = pathParent(path);
  if (parent === undefined) return '';
  const grandparent = pathParent(parent);
  return grandparent === undefined ? '' : pathName(grandparent);
}

export function hasGrandparent(path: string): boolean {
  const parent = pathParent(path);
  return parent !== undefined && pathParent(parent) !== undefined;
}
