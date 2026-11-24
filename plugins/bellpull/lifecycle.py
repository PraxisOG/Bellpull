"""Keep native HTTP bindings scoped to their manager across plugin discovery.

Hermes deduplicates a handler factory by name on an already-frozen router.
Its manager survives discovery, while directory plugin modules do not. Retain
only weak references on that manager; never share bindings across profiles.
"""
import hashlib
import os
import weakref


def bundle_identity():
    from .version import ROOT, source_digest
    digest = hashlib.sha256(source_digest(ROOT).encode())
    # Changed service configuration also requires a fresh gateway. Never expose
    # these values (which may contain credentials) in the readiness response.
    for key, value in sorted(os.environ.items()):
        if key.startswith("BELLPULL_"):
            digest.update(key.encode() + b"\0" + value.encode() + b"\0")
    return digest.hexdigest()


def bindings(ctx):
    manager = ctx._manager
    name = "_bellpull_http_bindings"
    existing = getattr(manager, name, None)
    if existing is None:
        existing = weakref.WeakSet()
        setattr(manager, name, existing)
    return existing
