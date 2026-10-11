"""One Bellpull package, registered through Hermes' supported API handler factory."""

VERSION = "0.1.0"


def register(ctx):
    from .http import wire
    instances = []
    def factory(app, adapter):
        instances.append(wire(app, adapter))
    def unload():
        for stop in instances:
            stop()
        instances.clear()
    ctx.register_platform_handler("api_server", factory)
    ctx.on_unload(unload)
