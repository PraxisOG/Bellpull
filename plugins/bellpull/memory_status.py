"""Record the serving provider and snapshot time on an existing authorized memory check."""
from functools import wraps
import json
import math
import time


def observe(agent, enabled):
    session = getattr(agent, "session_id", None)
    if not isinstance(session, str) or not session:
        return
    manager = getattr(agent, "_memory_manager", None)
    find = getattr(manager, "get_provider", None)
    if not callable(find):
        return
    provider = find("memory-subsystem")
    if getattr(provider, "name", None) != "memory-subsystem":
        return
    handle = getattr(provider, "handle_tool_call", None)
    if not callable(handle):
        return
    # Cached agents may be seen again after rediscovery. Reuse our original
    # handler without adding a second observer around the same provider.
    native = getattr(handle, "_bellpull_memory_native", handle)

    @wraps(native)
    def observed(tool_name, args, **kwargs):
        result = native(tool_name, args, **kwargs)
        if tool_name != "memory_status" or not enabled() or not isinstance(result, str):
            return result
        try:
            if getattr(provider, "session_id", None) != session:
                return result
            status = json.loads(result)
            if not isinstance(status, dict) or status.get("error") is not None or status.get("status") in ("error", "blocked", "pending_approval"):
                return result
            access = status.get("access")
            if not isinstance(access, dict) or access.get("owner_memory") not in ("available", "unavailable"):
                return result
            now = time.time()
            receipt = {"version": 1, "provider": provider.name, "observed_at": now}
            # The provider has already supplied this block to the current
            # session. Read only its timestamp; never build or read memory here.
            block = getattr(provider, "block", None)
            created = block.get("created") if isinstance(block, dict) else None
            if type(created) in (float, int) and math.isfinite(created) and 0 < created <= now:
                receipt["context_created_at"] = created
            status["bellpull_memory"] = receipt
            return json.dumps(status, ensure_ascii=False, separators=(",", ":"))
        except Exception:
            # Provider attributes and result formats vary across host versions.
            return result

    observed._bellpull_memory_native = native
    provider.handle_tool_call = observed
