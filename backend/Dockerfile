# Stage 1: grab the github-mcp-server binary (multi-arch: arm64 on Jetson, amd64 elsewhere)
FROM ghcr.io/github/github-mcp-server AS mcp-binary

# Stage 1b: fetch the gog (gogcli) binary for Google Workspace / Gmail access.
# Auth is NOT baked in — it comes from a mounted, read-only gogcli config at
# runtime (see docker-compose). TARGETARCH is provided automatically by the
# builder (arm64 on the Jetson).
FROM alpine:3.20 AS gog-binary
ARG TARGETARCH=arm64
ARG GOG_VERSION=0.31.1
RUN apk add --no-cache curl tar \
    && curl -fsSL -o /tmp/gog.tgz \
       "https://github.com/openclaw/gogcli/releases/download/v${GOG_VERSION}/gogcli_${GOG_VERSION}_linux_${TARGETARCH}.tar.gz" \
    && tar -xzf /tmp/gog.tgz -C /tmp gog \
    && install -m 0755 /tmp/gog /usr/local/bin/gog

# Read the OAuth client secret from the same encrypted file keyring as gog.
# The helper writes only to its caller's private process pipe; it never persists
# or logs the secret. The keyring library version matches gog v0.31.1.
FROM golang:1.26-alpine AS gog-keyring-helper
WORKDIR /src
COPY tools/gogkeyring/go.mod ./
COPY tools/gogkeyring/main.go ./
RUN go mod tidy
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /gog-keyring-secret .

# Stage 2: LifeOS backend
FROM python:3.12-slim

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PIP_NO_CACHE_DIR=1

WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends gcc libpq-dev curl \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN pip install --upgrade pip && pip install -r requirements.txt

COPY app ./app
COPY scripts ./scripts
COPY migrations ./migrations

# github-mcp-server binary for stdio-based GitHub tool calls (no OAuth needed)
COPY --from=mcp-binary /server/github-mcp-server /usr/local/bin/github-mcp-server

# gog (gogcli) binary for read-only Gmail access in the expense collector.
COPY --from=gog-binary /usr/local/bin/gog /usr/local/bin/gog
COPY --from=gog-keyring-helper /gog-keyring-secret /usr/local/bin/gog-keyring-secret

EXPOSE 8000

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD curl -fsS http://localhost:8000/healthz || exit 1

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
