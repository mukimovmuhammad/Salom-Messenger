"""Create the SALOM schema in the empty database on the new VPS only."""
import os
from urllib.parse import urlparse

import psycopg

from migrate_sqlite_to_postgres import DDL


def initialize() -> int:
    dsn = os.environ.get("SALOM_DATABASE_URL", "")
    database = urlparse(dsn).path.lstrip("/")
    if not dsn or database != "salom_prod":
        raise SystemExit("Refusing schema creation: SALOM_DATABASE_URL must target salom_prod")

    with psycopg.connect(dsn) as connection:
        existing = connection.execute(
            "SELECT count(*) FROM information_schema.tables "
            "WHERE table_schema = 'public' AND table_type = 'BASE TABLE'"
        ).fetchone()[0]
        if existing:
            raise SystemExit(f"Refusing to modify non-empty salom_prod schema ({existing} tables found)")
        connection.execute(DDL)
        connection.commit()
        return connection.execute(
            "SELECT count(*) FROM information_schema.tables "
            "WHERE table_schema = 'public' AND table_type = 'BASE TABLE'"
        ).fetchone()[0]


if __name__ == "__main__":
    print(f"Created fresh SALOM PostgreSQL schema ({initialize()} tables)")
