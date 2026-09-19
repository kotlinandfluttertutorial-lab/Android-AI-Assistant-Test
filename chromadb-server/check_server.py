from chromadb.config import Settings
from chromadb.server.fastapi import FastAPI
import sys

s = Settings(is_persistent=False, anonymized_telemetry=False)
try:
    srv = FastAPI(s)
    print("FastAPI type:", type(srv))
    print("Has .app:", hasattr(srv, 'app'))
    print("Attrs with app:", [x for x in dir(srv) if 'app' in x.lower()])
    if hasattr(srv, 'app'):
        print("app type:", type(srv.app))
except Exception as e:
    print("Error:", e)
    sys.exit(1)
