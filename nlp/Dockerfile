FROM python:3.12-slim

# Dependencies in their own layer: sudachidict-core is ~70MB and changes far
# less often than the application code, so it stays cached across rebuilds.
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY app/ ./app/

# Non-root. Nothing here writes to disk.
RUN useradd --create-home --uid 1000 kotanoba
USER kotanoba

EXPOSE 8000

# One worker: the Sudachi dictionary is ~100MB resident, and this service is
# called by the import worker, not by users. Scale by adding containers if the
# import queue ever backs up — but per claude.md, that day is a long way off.
#
# PORT defaults to 8000 for Compose/local dev, where it's fixed. Render
# assigns its own PORT per service (default 10000) and expects the container
# to bind to it — shell-form CMD so ${PORT:-8000} actually expands; exec-form
# CMD (a JSON array) would pass the literal string through unexpanded.
ENV PORT=8000
CMD uvicorn app.main:app --host 0.0.0.0 --port ${PORT} --workers 1
