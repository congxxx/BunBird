FROM python:3.12.13-slim
COPY --from=ghcr.io/astral-sh/uv:0.11.15 /uv /usr/local/bin/uv
WORKDIR /app
ENV UV_COMPILE_BYTECODE=1 UV_LINK_MODE=copy PYTHONUNBUFFERED=1
COPY pyproject.toml uv.lock ./
COPY bunbird ./bunbird
RUN uv sync --frozen --no-dev && mkdir -p /app/data && chown -R 10001:10001 /app
USER 10001:10001
ENV PATH="/app/.venv/bin:$PATH"
ENTRYPOINT ["bunbird"]
CMD ["worker"]
