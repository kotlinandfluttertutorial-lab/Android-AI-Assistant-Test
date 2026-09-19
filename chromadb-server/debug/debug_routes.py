import os
os.environ["IS_PERSISTENT"] = "true"
os.environ["PERSIST_DIRECTORY"] = "/tmp/chroma"
os.environ["ANONYMIZED_TELEMETRY"] = "false"

from chromadb.app import app as chroma_app
from fastapi.routing import APIRoute, Mount
import chromadb
print("chromadb version:", chromadb.__version__)
print("app type:", type(chroma_app))

def print_routes(app_or_router, prefix=""):
    for route in app_or_router.routes:
        if hasattr(route, "path"):
            methods = getattr(route, "methods", "?")
            print(f"  {methods} {prefix}{route.path}")
        if hasattr(route, "routes"):
            print_routes(route, prefix)

print_routes(chroma_app)
print("---")
# Also check the router
if hasattr(chroma_app, "router"):
    print("router routes:")
    for route in chroma_app.router.routes:
        if hasattr(route, "path"):
            print(f"  {getattr(route, 'methods', '?')} {route.path}")
        elif hasattr(route, "routes"):
            for r in route.routes:
                if hasattr(r, "path"):
                    print(f"    (sub) {getattr(r, 'methods', '?')} {r.path}")
