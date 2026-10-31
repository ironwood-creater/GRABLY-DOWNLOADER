# Grably website + download server
FROM python:3.12-slim

# ffmpeg merges separate video/audio streams and converts audio to MP3
RUN apt-get update \
 && apt-get install -y --no-install-recommends ffmpeg \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY server/requirements.txt server/requirements.txt
RUN pip install --no-cache-dir -r server/requirements.txt

COPY index.html ./
COPY assets ./assets
COPY server ./server

RUN useradd --create-home grably
USER grably

ENV PORT=8000 \
    GRABLY_TRUST_PROXY=1 \
    PYTHONUNBUFFERED=1
EXPOSE 8000

# Visitor IPs for rate limiting come from X-Forwarded-For (see GRABLY_TRUST_PROXY / GRABLY_PROXY_HOPS)
CMD ["sh", "-c", "exec uvicorn server.app:app --host 0.0.0.0 --port ${PORT} --no-server-header"]
