FROM python:3.12-slim

# Install system dependencies: CUPS, client utilities, raster filters, and ghostscript
RUN apt-get update && apt-get install -y --no-install-recommends \
    cups \
    cups-client \
    cups-filters \
    cups-filters-core-drivers \
    ghostscript \
    poppler-utils \
    curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Copy dependency definition and install
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Create directories for assets and data
RUN mkdir -p /app/assets /app/data

# Copy application source code
COPY config.yaml .
COPY src/ /app/src/

EXPOSE 8085

CMD ["python", "-m", "uvicorn", "src.main:app", "--host", "0.0.0.0", "--port", "8085"]
