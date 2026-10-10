"""Hermes native API plugin. No model tool, remote login, or background sampler."""

from .telemetry import Sampler


def register(ctx):
    def wire(app, adapter):
        from aiohttp import web

        sampler = Sampler()

        async def temperatures(request):
            # Native profile middleware scopes the expected key for /p/{profile}.
            # Never inherit the adapter's historical unauthenticated-test fallback.
            if not adapter._expected_api_key():
                return web.json_response({"error": "GPU telemetry requires API authentication"}, status=401)
            error = adapter._check_auth(request)
            if error is not None:
                return error
            return web.json_response(await sampler.read(), headers={"Cache-Control": "no-store"})

        for prefix in ("", "/p/{profile}"):
            app.router.add_get(prefix + "/api/bellpull/plugins/gpu", temperatures)

    ctx.register_platform_handler("api_server", wire)
