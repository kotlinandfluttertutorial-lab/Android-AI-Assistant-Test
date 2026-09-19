import chromadb
print("chromadb version:", chromadb.__version__)

from chromadb.app import app as chroma_app
print("app type:", type(chroma_app))
print("app routes:")
for route in chroma_app.routes:
    print(f"  {getattr(route, 'methods', '?')} {route.path}")