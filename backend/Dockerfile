# Stage 1: grab the github-mcp-server binary (multi-arch: arm64 on Jetson, amd64 elsewhere)
FROM ghcr.io/github/github-mcp-server AS mcp-binary

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

# github-mcp-server binary for stdio-based GitHub tool calls (no OAuth needed)
COPY --from=mcp-binary /server/github-mcp-server /usr/local/bin/github-mcp-server

EXPOSE 8000

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD curl -fsS http://localhost:8000/healthz || exit 1

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
