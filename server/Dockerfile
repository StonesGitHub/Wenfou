FROM python:3.12-slim-bookworm
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 PIP_NO_CACHE_DIR=1
WORKDIR /app
COPY requirements.txt .
RUN pip install --require-hashes -r requirements.txt && useradd --uid 10001 --create-home app
COPY wenfou ./wenfou
COPY migrations ./migrations
COPY alembic.ini .
USER 10001:10001
EXPOSE 8000
CMD ["uvicorn", "wenfou.api:create_app", "--factory", "--host", "0.0.0.0", "--port", "8000", "--workers", "2", "--limit-concurrency", "64", "--timeout-keep-alive", "5", "--proxy-headers", "--forwarded-allow-ips", "*", "--no-access-log"]
