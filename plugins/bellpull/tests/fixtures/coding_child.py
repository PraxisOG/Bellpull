#!/usr/bin/env python3
"""Deterministic coding CLI fixture exercising a real edit, commit and test runs."""
import json
from pathlib import Path
import subprocess
import sys
import time

session = "6181c478-e0f6-4b19-acaa-986b2b6e06ce"
codex = Path(sys.argv[0]).name == "codex"


def emit(event):
    print(json.dumps(event), flush=True)


def check(key):
    result = subprocess.run([sys.executable, "-B", "-m", "unittest", "-q", "test_answer"],
                            capture_output=True, text=True)
    if codex:
        emit(dict(type="item.completed", item=dict(id=key, type="command_execution",
                  command="python -B -m unittest -q test_answer", exit_code=result.returncode,
                  aggregated_output=result.stderr)))
    else:
        emit(dict(type="assistant", session_id=session, message=dict(id=key, content=[
            dict(type="tool_use", id=key, name="Bash", input={"command":"python -B -m unittest -q test_answer"})])))
        emit(dict(type="user", session_id=session, message=dict(content=[
            dict(type="tool_result", tool_use_id=key, is_error=result.returncode != 0, content=result.stderr)])))
    return result.returncode


if codex:
    emit(dict(type="thread.started", thread_id=session))
else:
    emit(dict(type="system", subtype="init", session_id=session))
deadline = time.monotonic() + 20
while not (Path(__file__).parent / "release-edit").exists():
    if time.monotonic() > deadline:
        raise SystemExit("Timed out waiting for the fixture client")
    time.sleep(0.05)

assert check("test_before") == 1
Path("answer.py").write_text("value = 2\n")
assert check("test_after") == 0
subprocess.run(["git","add","answer.py"], check=True)
subprocess.run(["git","commit","-qm","Fixture edit"], check=True)
if codex:
    emit(dict(type="item.completed", item=dict(id="item_0", type="agent_message", text="Observed fixture output")))
else:
    emit(dict(type="assistant", session_id=session, message=dict(id="msg_fixture", content=[
        dict(type="text", text="Observed fixture output")])))
