import os
os.environ["IS_PERSISTENT"] = "true"
os.environ["PERSIST_DIRECTORY"] = "/tmp/chroma"
os.environ["ANONYMIZED_TELEMETRY"] = "false"

import fastapi
print("fastapi version:", fastapi.__version__)

import chromadb
print("chromadb version:", chromadb.__version__)

from chromadb.app import app as chroma_app

def list_routes(app_or_router, indent=0):
    for route in app_or_router.routes:
        path = getattr(route, "path", None)
        methods = getattr(route, "methods", None)
        if path:
            print(" " * indent + f"{methods} {path}")
        # recurse into nested routers
        sub = getattr(route, "routes", None)
        if sub:
            list_routes(route, indent + 2)

print("=== app.routes ===")
list_routes(chroma_app)
print("=== done ===")
