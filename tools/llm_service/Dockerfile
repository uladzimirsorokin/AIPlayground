FROM python:3.11-slim

WORKDIR /app
COPY gateway.py /app/gateway.py

ENV GATEWAY_HOST=0.0.0.0 \
    GATEWAY_PORT=8080 \
    UPSTREAM=http://ollama:11434

EXPOSE 8080
CMD ["python", "gateway.py"]
