FROM python:3.13-slim

WORKDIR /app
RUN apt-get update \
    && apt-get install -y --no-install-recommends tesseract-ocr tesseract-ocr-chi-sim tesseract-ocr-eng tesseract-ocr-rus \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 kitai

COPY backend/pyproject.toml backend/uv.lock ./
COPY backend/kitai ./kitai
RUN pip install --no-cache-dir uv==0.11.8 \
    && uv --no-cache sync --frozen --no-dev --no-editable --no-python-downloads

ENV KITAI_TESSERACT=/usr/bin/tesseract
USER 10001
EXPOSE 8000
CMD ["/app/.venv/bin/uvicorn", "kitai.main:app", "--host", "0.0.0.0", "--port", "8000", "--no-access-log"]
