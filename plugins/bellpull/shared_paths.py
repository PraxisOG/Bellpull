"""Read-only documents under named directories configured by the profile owner."""
import json
import os
from pathlib import Path, PurePosixPath
import re

from .artifacts import ArtifactError
from .live_files_store import MAX_TEXT, digest, opened, read_text


def roots(home=None):
    if home is None:
        from hermes_constants import get_hermes_home
        home = get_hermes_home()
    try:
        with opened(str(Path(home) / 'bellpull' / 'file-roots.json')) as (_, fd, _, info):
            if info.st_size > 16384:
                return {}
            value = json.loads(os.read(fd, 16385))
    except (ArtifactError, ValueError, OSError):
        return {}
    if not isinstance(value, dict) or len(value) > 32:
        return {}
    for alias, path in value.items():
        if not re.fullmatch(r'[A-Za-z0-9_-]{1,48}', alias) or not isinstance(path, str):
            return {}
        parts = PurePosixPath(path).parts
        if not parts or parts[0] != '/' or len(parts) < 2 or '..' in parts or '\0' in path:
            return {}
    return value


def snapshot(configured, path, offset=0, limit=MAX_TEXT):
    if not configured:
        raise ArtifactError('Shared document paths are unavailable.', 404)
    parts = path.split('/')
    if len(parts) < 2 or any(part in {'', '.', '..'} for part in parts) or len(path) > 4096 or '\0' in path:
        raise ArtifactError('Enter a shared directory name followed by a relative file path.')
    if parts[0] not in configured:
        raise ArtifactError('Unknown shared directory.', 404)
    if not 0 <= offset <= MAX_TEXT or not 4 <= limit <= MAX_TEXT:
        raise ArtifactError('Invalid document byte range.')
    with opened(str(Path(configured[parts[0]]).joinpath(*parts[1:]))) as (_, fd, _, info):
        text = read_text(fd, info)
    raw = text.encode('utf-8')
    if offset > len(raw) or (offset < len(raw) and raw[offset] & 0xc0 == 0x80):
        raise ArtifactError('Offset must be a UTF-8 character boundary.', 416)
    end = min(len(raw), offset + limit)
    while end < len(raw) and raw[end] & 0xc0 == 0x80:
        end -= 1
    return {'version': 1, 'id': digest('path:' + path)[:32], 'source_path': path, 'access': 'read',
            'text': raw[offset:end].decode('utf-8'), 'etag': '"' + digest(text) + '"', 'size': len(raw),
            'mtime_ns': info.st_mtime_ns, 'offset': offset, 'next_offset': end if end < len(raw) else None}
