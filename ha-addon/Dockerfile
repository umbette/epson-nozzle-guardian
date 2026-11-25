ARG BUILD_FROM
FROM ${BUILD_FROM}

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PIP_NO_CACHE_DIR=1 \
    DATA_DIR="/data/nozzle-guardian"

WORKDIR /app

RUN apk add --no-cache \
    gcc \
    musl-dev \
    zlib-dev \
    jpeg-dev \
    libwebp-dev

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY src/ /app/src/
COPY config.example.yaml /app/config.example.yaml

RUN mkdir -p /data/nozzle-guardian /app/assets && chmod -R 755 /app

CMD ["uvicorn", "src.main:app", "--host", "0.0.0.0", "--port", "8086"]
