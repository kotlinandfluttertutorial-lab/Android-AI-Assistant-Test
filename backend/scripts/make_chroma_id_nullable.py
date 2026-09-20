"""One-shot script: ALTER document_chunks.chroma_id to allow NULLs."""
import os
import psycopg2

raw_url = os.environ["DATABASE_URL"]
# Convert asyncpg URL to psycopg2 format
url = raw_url.replace("postgresql+asyncpg://", "postgresql://")
if "?ssl=require" in url:
    url = url.replace("?ssl=require", "")
    url += "?sslmode=require"

print(f"Connecting...", flush=True)
conn = psycopg2.connect(url)
conn.autocommit = True
cur = conn.cursor()

# Check current nullability
cur.execute("""
    SELECT is_nullable FROM information_schema.columns
    WHERE table_name='document_chunks' AND column_name='chroma_id'
""")
row = cur.fetchone()
print(f"chroma_id is_nullable={row[0]}", flush=True)

if row[0] == "NO":
    cur.execute("ALTER TABLE document_chunks ALTER COLUMN chroma_id DROP NOT NULL")
    print("ALTER TABLE done — chroma_id is now nullable", flush=True)
else:
    print("chroma_id already nullable — nothing to do", flush=True)

conn.close()
print("Done.", flush=True)
