
import psycopg2
from qdrant_client import QdrantClient
from minio import Minio
import sys

def check_postgres():
    try:
        # Assuming local connection to the 'decode' db using current user
        conn = psycopg2.connect(
            dbname="decode",
            user="kothuparotta", # Using prompt user, usually works nicely on macOS dev
            host="localhost",
            port=5432
        )
        cur = conn.cursor()
        cur.execute("SELECT 1")
        print("✅ Postgres (Native): Connected")
        cur.close()
        conn.close()
        return True
    except Exception as e:
        print(f"❌ Postgres (Native): Failed - {e}")
        return False

def check_qdrant():
    try:
        client = QdrantClient("localhost", port=6333)
        cols = client.get_collections()
        print(f"✅ Qdrant (Docker): Connected (Collections: {len(cols.collections)})")
        return True
    except Exception as e:
        print(f"❌ Qdrant (Docker): Failed - {e}")
        return False

def check_minio():
    try:
        client = Minio(
            "localhost:9000",
            access_key="minioadmin",
            secret_key="minioadmin",
            secure=False
        )
        buckets = client.list_buckets()
        print(f"✅ MinIO (Docker): Connected (Buckets: {len(buckets)})")
        return True
    except Exception as e:
        print(f"❌ MinIO (Docker): Failed - {e}")
        return False

if __name__ == "__main__":
    print("Starting Hybrid Infrastructure Health Check...")
    pg = check_postgres()
    qd = check_qdrant()
    mn = check_minio()
    
    if pg and qd and mn:
        print("ALL SYSTEMS OPERATIONAL")
        sys.exit(0)
    else:
        print("SYSTEM CHECK FAILED")
        sys.exit(1)
