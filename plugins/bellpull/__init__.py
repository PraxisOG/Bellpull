"""One Bellpull package, registered through Hermes' supported API handler factory."""

VERSION = "0.1.0"


def register(ctx):
    from .http import wire
    ctx.register_platform_handler("api_server", wire)
