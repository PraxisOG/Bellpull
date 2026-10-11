FROM nousresearch/hermes-agent:v2026.9.7@sha256:63bfb6d732f49a55d453e801057273785cc61e0f6ee43db3fa2f2a79846301b7
COPY server-host /tmp/bellpull-host
RUN python /tmp/bellpull-host/apply.py --apply
COPY plugins/bellpull /opt/hermes/plugins/bellpull
