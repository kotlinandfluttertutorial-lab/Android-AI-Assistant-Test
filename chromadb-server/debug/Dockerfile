FROM python:3.11-slim
RUN pip install --no-cache-dir chromadb==0.5.20
ENV IS_PERSISTENT=true
ENV PERSIST_DIRECTORY=/tmp/chroma
ENV ANONYMIZED_TELEMETRY=false
RUN mkdir -p /tmp/chroma
COPY debug_routes.py /debug_routes.py
CMD ["python", "/debug_routes.py"]
