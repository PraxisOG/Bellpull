"""One Bellpull package, registered through Hermes' supported API handler factory."""

VERSION = "0.5.2-integration-preview"


def register(ctx):
    from .http import wire
    from .sharing import register as register_sharing
    register_sharing(ctx)
    from .planning import register as register_planning
    register_planning(ctx)
    instances = []
    def factory(app, adapter):
        instances.append(wire(app, adapter))
    def observe_tool(**kwargs):
        from .coding import dispatch_tool
        dispatch_tool(**kwargs)
    ctx.register_hook("post_tool_call", observe_tool)
    def unload():
        for stop in instances:
            stop()
        instances.clear()
    ctx.register_platform_handler("api_server", factory)
    ctx.on_unload(unload)
