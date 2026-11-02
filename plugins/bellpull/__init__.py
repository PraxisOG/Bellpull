"""One Bellpull package, registered through Hermes' supported API handler factory."""

VERSION = "0.8.3-reload-preview"


def register(ctx):
    from .http import wire
    from .lifecycle import bindings, bundle_identity
    instances = bindings(ctx)
    identity = bundle_identity()
    from .sharing import register as register_sharing
    register_sharing(ctx)
    from .canvas import register as register_canvas
    register_canvas(ctx)
    from .planning import register as register_planning
    register_planning(ctx)
    from .phone_context import register as register_context
    register_context(ctx)
    def factory(app, adapter):
        stop = wire(app, adapter)
        stop.bundle_identity = identity
        instances.add(stop)
    def observe_tool(**kwargs):
        from .coding import dispatch_tool
        dispatch_tool(**kwargs)
    ctx.register_hook("post_tool_call", observe_tool)
    def unload():
        for stop in tuple(instances):
            stop()
    ctx.register_platform_handler("api_server", factory)
    ctx.on_unload(unload)
    # Factory deduplication prevents another invocation on a live router. Only
    # a successful registration can reactivate this manager's existing bindings.
    for stop in tuple(instances):
        stop.resume(identity == stop.bundle_identity)
