"""Observe documented Codex exec and Claude Code print-mode JSON events."""
import json
import re

UUID = re.compile(r"[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")
EXTERNAL_ID = re.compile(r"[A-Za-z0-9._:-]{1,240}")
MAX_DETAIL = 16384
MAX_CLAUDE_COMMANDS = 32
MAX_COMMAND_PREVIEW = 2048


def identifier(value):
    return isinstance(value, str) and EXTERNAL_ID.fullmatch(value) is not None


def public_text(value):
    if not isinstance(value, str) or not value:
        return ""
    try:
        from agent.redact import redact_for_egress
    except ImportError:
        return ""
    return redact_for_egress(value)


def row(key, kind, label, detail="", outcome=None):
    text = public_text(detail)
    value = dict(key=key, kind=kind, label=label[:256], detail=text[:MAX_DETAIL],
                 timestamp_ms=0, truncated=len(text) > MAX_DETAIL)
    if outcome is not None:
        value["outcome"] = outcome
    return value


def bind(work, session):
    if not isinstance(session, str) or not UUID.fullmatch(session):
        return False
    previous = work.get("executor_session_id")
    if previous and previous != session:
        work["gap"] = True
        return False
    work["executor_session_id"] = session
    return True


def codex_rows(work, event, position):
    kind = event.get("type")
    if kind == "thread.started":
        valid = bind(work, event.get("thread_id"))
        work["awaiting_thread"] = not valid
        return [row("thread", "step_started", "Codex thread started")] if valid else []
    if not work.get("executor_session_id") or work.get("awaiting_thread"):
        return []
    if kind in ("turn.started", "turn.completed", "turn.failed"):
        error = event.get("error")
        detail = error.get("message") if isinstance(error, dict) else ""
        return [row(f"turn:{position}", "error" if kind == "turn.failed" else "step",
                    "Codex " + kind.replace(".", " "), detail)]
    if kind not in ("item.started", "item.updated", "item.completed"):
        return []
    item = event.get("item")
    if not isinstance(item, dict) or not identifier(item.get("id")):
        return []
    key = "item:" + item["id"]
    category = item.get("type")
    if category == "error":
        return [row(key, "error", "Codex diagnostic", item.get("message"))]
    if category == "agent_message":
        return [row(key, "text", "Codex response", item.get("text"))]
    if category == "command_execution":
        code = item.get("exit_code")
        outcome = f"exit {code}" if type(code) is int else None
        result = row(key, "tool", "Command execution", item.get("aggregated_output"), outcome)
        command = public_text(item.get("command"))
        if command:
            result["command"] = command[:MAX_DETAIL]
            result["truncated"] |= len(command) > MAX_DETAIL
        return [result]
    if category == "file_change":
        changes = item.get("changes")
        if isinstance(changes, list):
            paths = [c["path"] for c in changes[:100] if isinstance(c, dict) and isinstance(c.get("path"), str)]
            return [row(key, "tool", "Reported file changes", "\n".join(paths))]
    return []


def claude_rows(work, event, position):
    kind, session = event.get("type"), event.get("session_id")
    if kind == "system" and event.get("subtype") == "init":
        return [row("session", "step_started", "Claude Code session started")] if bind(work, session) else []
    if not session or session != work.get("executor_session_id"):
        return []
    if kind == "result":
        if not isinstance(event.get("is_error"), bool):
            return []
        problem = event["is_error"]
        return [row(f"result:{position}", "error" if problem else "step_finished",
                    "Claude Code turn failed" if problem else "Claude Code turn finished")]
    message = event.get("message")
    if kind not in ("assistant", "user") or not isinstance(message, dict):
        return []
    blocks = message.get("content")
    if not isinstance(blocks, list):
        return []
    rows = []
    for i, block in enumerate(blocks[:128]):
        if not isinstance(block, dict):
            continue
        block_type = block.get("type")
        if kind == "assistant" and block_type == "text" and identifier(message.get("id")):
            rows.append(row(f"message:{message['id']}:{i}", "text", "Claude Code response", block.get("text")))
        elif kind == "assistant" and block_type == "tool_use" and identifier(block.get("id")) and identifier(block.get("name")):
            tool_id, name = block["id"], block["name"]
            request = row("tool:" + tool_id, "tool", name, outcome="requested")
            commands = work.setdefault("claude_commands", {})
            commands.pop(tool_id, None)
            if name == "Bash":
                arguments = block.get("input")
                command = public_text(arguments.get("command")) if isinstance(arguments, dict) else ""
                if len(commands) >= MAX_CLAUDE_COMMANDS:
                    del commands[next(iter(commands))]
                commands[tool_id] = [command[:MAX_COMMAND_PREVIEW], len(command) > MAX_COMMAND_PREVIEW]
                if command:
                    request["command"], request["truncated"] = commands[tool_id]
            rows.append(request)
        elif kind == "user" and block_type == "tool_result" and identifier(block.get("tool_use_id")):
            error = block.get("is_error", False)
            if not isinstance(error, bool):
                continue
            tool_id = block["tool_use_id"]
            outcome = "failed" if error else "returned"
            previous = work.get("claude_commands", {}).get(tool_id)
            if previous is None:
                rows.append(row("result:" + tool_id, "tool", "Tool result", outcome=outcome))
                continue
            # Only the result of a previously observed Bash call can supply shell output.
            native = event.get("tool_use_result")
            detail = block.get("content")
            if len(blocks) == 1 and isinstance(native, dict) and all(isinstance(native.get(k), str) for k in ("stdout", "stderr")):
                detail = "\n".join(part for part in (native["stdout"], native["stderr"]) if part)
            result = row("tool:" + tool_id, "tool", "Bash", detail, outcome)
            command, truncated = previous
            if command:
                result["command"] = command
                result["truncated"] |= truncated
            rows.append(result)
    return rows


def observed_rows(work, line, position):
    try:
        event = json.loads(line)
    except (ValueError, RecursionError):
        return []
    if not isinstance(event, dict):
        return []
    if work["transport"] == "codex_json":
        return codex_rows(work, event, position)
    return claude_rows(work, event, position)


def consume_frames(work, total, tail, exited, emit, limit):
    """Checkpoint complete JSON lines so a restarted observer can reread a partial frame."""
    position = work["observed_total"]
    earliest = total - len(tail)
    if position < earliest or position > total:
        position = earliest
        work["discard_frame"] = True
        work["gap"] = True
        if work["transport"] == "codex_json":
            work["awaiting_thread"] = True

    while position < total:
        start = position - earliest
        newline = tail.find("\n", start)
        complete = newline >= 0 or exited
        end = newline + 1 if newline >= 0 else len(tail)
        if end - start > limit:
            work["gap"] = True
            work["discard_frame"] = True
            if work["transport"] == "codex_json":
                work["awaiting_thread"] = True
        if work.get("discard_frame"):
            work["discard_frame"] = not complete
        elif complete:
            for value in observed_rows(work, tail[start:end], position):
                emit(work, value)
        else:
            break
        position = earliest + end
    work["observed_total"] = position
