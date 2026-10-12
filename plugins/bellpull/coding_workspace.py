"""Bounded read-only Git workspace views. Client supplies opaque file IDs only."""
import difflib
import hashlib
import os
from pathlib import Path
import selectors
import stat
import subprocess
import time

from .artifacts import ArtifactError

MAX_BYTES=262144
MAX_FILES=200


def git(root, *args, limit=MAX_BYTES):
    environment={k:v for k,v in os.environ.items() if not k.startswith("GIT_")}
    environment.update(GIT_CONFIG_NOSYSTEM="1",GIT_CONFIG_GLOBAL=os.devnull,GIT_TERMINAL_PROMPT="0")
    command=["git","--no-optional-locks","-c","core.fsmonitor=false","-c",f"core.hooksPath={os.devnull}",
             "-c",f"core.attributesFile={os.devnull}","-c","core.pager=cat","-c","diff.external=",*args]
    proc=subprocess.Popen(command,cwd=root,env=environment,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL)
    result=bytearray(); selector=selectors.DefaultSelector(); selector.register(proc.stdout,selectors.EVENT_READ)
    deadline=time.monotonic()+5
    try:
        while time.monotonic()<deadline:
            ready=selector.select(min(.1,max(0,deadline-time.monotonic())))
            if not ready:
                if proc.poll() is not None: break
                continue
            chunk=os.read(proc.stdout.fileno(),min(65536,limit+1-len(result)))
            if not chunk: break
            result.extend(chunk)
            if len(result)>limit: raise ArtifactError("Workspace output exceeds the preview limit.",413)
        else: raise ArtifactError("Workspace inspection timed out.",503)
        code=proc.wait(timeout=max(.1,deadline-time.monotonic()))
        return code,bytes(result)
    except subprocess.TimeoutExpired as exc:
        raise ArtifactError("Workspace inspection timed out.",503) from exc
    finally:
        selector.close(); proc.stdout.close()
        if proc.poll() is None: proc.kill()
        proc.wait()


def owned_bytes(root, relative):
    """Walk from an open directory FD; never follow swapped symlink components."""
    parts=Path(relative).parts
    if not parts or any(p in ("..",".") for p in parts) or Path(relative).is_absolute():
        raise ArtifactError("Unsafe workspace path.",400)
    fd=os.open(root,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW)
    try:
        for part in parts[:-1]:
            child=os.open(part,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW,dir_fd=fd)
            os.close(fd); fd=child
        try: child=os.open(parts[-1],os.O_RDONLY|os.O_NOFOLLOW,dir_fd=fd)
        except FileNotFoundError: return b""
        with os.fdopen(child,"rb") as source:
            info=os.fstat(source.fileno())
            if not stat.S_ISREG(info.st_mode): raise ArtifactError("Only regular text files have previews.",409)
            if info.st_size>MAX_BYTES: raise ArtifactError("File exceeds the preview limit.",413)
            data=source.read(MAX_BYTES+1)
            if len(data)>MAX_BYTES: raise ArtifactError("File exceeds the preview limit.",413)
            return data
    except FileNotFoundError:
        return b""
    except OSError as exc:
        raise ArtifactError("File is missing, inaccessible or a symlink; preview is unavailable.",409) from exc
    finally: os.close(fd)


def inspect_workspace(directory, file_id=None):
    root=Path(directory)
    # Do not silently follow a replaced workspace root or expand to its parent repo.
    if not root.is_absolute() or root.is_symlink() or root.resolve()!=root or not root.is_dir():
        raise ArtifactError("The recorded workspace is no longer available.",410)
    code,top=git(root,"rev-parse","--show-toplevel",limit=8192)
    if code or top.decode(errors="replace").strip()!=str(root):
        return dict(version=1,label="Workspace changes",files=[],note="Read-only changes require the recorded workspace to be a Git repository root.",truncated=False)
    code,tracked=git(root,"diff","--no-ext-diff","--no-textconv","--no-renames","--name-only","-z","HEAD","--",limit=1048576)
    if code:
        # An unborn repository has no HEAD; ls-files lists tracked paths directly.
        code,tracked=git(root,"ls-files","-z",limit=1048576)
    code,untracked=git(root,"ls-files","--others","--exclude-standard","-z",limit=1048576)
    if code: raise ArtifactError("Workspace changes could not be inspected.",503)
    names=sorted(set(p for p in (tracked+untracked).split(b"\0") if p))
    files=[]
    for raw in names[:MAX_FILES]:
        try: name=raw.decode("utf-8")
        except UnicodeDecodeError: continue
        if Path(name).is_absolute() or ".." in Path(name).parts: continue
        files.append(dict(id=hashlib.sha256(raw).hexdigest(),path=name))
    result=dict(version=1,label="Workspace changes",files=files,note="Changes may include edits made outside this OpenCode task.",truncated=len(names)>MAX_FILES)
    if file_id is None: return result
    entry=next((f for f in files if f["id"]==file_id),None)
    if entry is None: raise ArtifactError("Changed file not found. Refresh the workspace list.",404)
    try:
        after=owned_bytes(root,entry["path"])
        code,before=git(root,"show",f"HEAD:{entry['path']}")
        if code: before=b""
        if b"\0" in before or b"\0" in after: raise ArtifactError("Binary files have no text diff preview.",409)
        old,new=before.decode("utf-8"),after.decode("utf-8")
        diff="".join(difflib.unified_diff(old.splitlines(keepends=True),new.splitlines(keepends=True),fromfile="a/"+entry["path"],tofile="b/"+entry["path"]))
        result.update(file=entry,diff=diff[:MAX_BYTES],truncated=result["truncated"] or len(diff)>MAX_BYTES)
    except UnicodeDecodeError as exc: raise ArtifactError("This file is not UTF-8 text; preview is unavailable.",409) from exc
    return result
