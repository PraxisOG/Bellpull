"""Read-only compression identity, bounded and separate from ordinary forks."""
import json


def _continuation(child, parent):
    if not parent or parent.get("end_reason") != "compression" or parent.get("ended_at") is None:
        return False
    config = child.get("model_config") or {}
    if isinstance(config, str):
        try:
            config = json.loads(config)
        except ValueError:
            return False
    return (isinstance(config, dict) and child.get("source") != "tool" and
            child.get("parent_session_id") == parent["id"] and
            not any(config.get(key) == parent["id"] for key in ("_branched_from", "_delegate_from", "_reset_from")))


def compression_ids(database, session_id):
    current = database.get_session(session_id)
    if not current:
        return [session_id]
    seen = set()
    for _ in range(1000):
        if current["id"] in seen:
            return [session_id]
        seen.add(current["id"])
        parent = database.get_session(current.get("parent_session_id")) if current.get("parent_session_id") else None
        if not _continuation(current, parent):
            break
        current = parent
    else:
        return [session_id]
    chain, seen = [], set()
    for _ in range(1000):
        if current["id"] in seen:
            return [session_id]
        chain.append(current["id"])
        seen.add(current["id"])
        if current.get("end_reason") != "compression":
            break
        # Older Hermes rejects any inherited fork marker, even when it points
        # elsewhere. Bind markers to the actual parent, as current Hermes does.
        rows = database._read_all("SELECT * FROM sessions WHERE parent_session_id = ? LIMIT 1001", (current["id"],))
        children = [dict(row) for row in rows if _continuation(dict(row), current)]
        if len(rows) > 1000 or len(children) > 1:
            return [session_id] # Ambiguous ancestry must never confer membership.
        if not children:
            break
        current = children[0]
    else:
        return [session_id]
    return chain if session_id in chain else [session_id]
