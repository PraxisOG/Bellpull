"""One Bellpull package, registered through Hermes' supported API handler factory."""

VERSION = "0.8.2-canvas-v1-preview"


def register(ctx):
    from .http import wire
    from .sharing import register as register_sharing
    register_sharing(ctx)
    from .canvas import register as register_canvas
    register_canvas(ctx)
    from .planning import register as register_planning
    register_planning(ctx)
    from .phone_context import register as register_context
    register_context(ctx)
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
