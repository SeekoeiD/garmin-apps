# Build from the repository root, not from server/:
#   docker build -f server/Dockerfile -t treadmill-collector .
FROM python:3.12-slim

WORKDIR /app

RUN pip install --no-cache-dir flask gunicorn garminconnect

COPY server/app.py server/fit_builder.py tools/fitlib.py /app/

ENV DATA_DIR=/data
VOLUME /data
EXPOSE 8080

CMD ["gunicorn", "-b", "0.0.0.0:8080", "-w", "2", "--timeout", "120", "app:app"]
