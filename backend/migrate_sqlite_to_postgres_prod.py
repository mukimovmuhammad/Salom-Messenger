"""One-shot import of a verified SQLite snapshot into an empty SALOM production DB.

This tool never reads the live SQLite DB directly, never creates/drops a database,
and refuses any PostgreSQL database except the explicitly confirmed `salom_prod`.
Run it only during a controlled cutover from a consistent offline-window snapshot.
"""
import argparse
import json
import os
from pathlib import Path
import sys

import psycopg

from migrate_sqlite_to_postgres import DDL, ORDER_COLUMNS, TABLES, digest_rows, validate_source


PRODUCTION_DATABASE = "salom_prod"


def validate_target_name(actual_name: str, confirmation: str) -> None:
    if actual_name != PRODUCTION_DATABASE or confirmation != PRODUCTION_DATABASE:
        raise ValueError("Target must be salom_prod and explicitly confirmed")


def migrate(source_path: Path, dsn: str, confirmation: str) -> dict[str, dict[str, object]]:
    source, _source_tables = validate_source(source_path)
    target = None
    try:
        target = psycopg.connect(dsn, autocommit=True)
        with target.transaction():
            actual_name = target.execute("SELECT current_database()").fetchone()[0]
            validate_target_name(actual_name, confirmation)
            existing_objects = target.execute(
                """SELECT count(*) FROM pg_class c
                   JOIN pg_namespace n ON n.oid = c.relnamespace
                   WHERE n.nspname = 'public' AND c.relkind IN ('r','p','v','m','S','f')"""
            ).fetchone()[0]
            if existing_objects:
                raise ValueError("Target database is not empty; refusing to overwrite it")

            for statement in DDL.strip().split(";\n"):
                if statement.strip():
                    target.execute(statement)

            for table, columns in TABLES.items():
                quoted_columns = ",".join(f'"{name}"' for name in columns)
                ordering = ",".join(f'"{name}"' for name in ORDER_COLUMNS[table])
                cursor = source.execute(f'SELECT {quoted_columns} FROM "{table}" ORDER BY {ordering}')
                insert = f'INSERT INTO "{table}" ({quoted_columns}) VALUES ({",".join("%s" for _ in columns)})'
                while rows := cursor.fetchmany(500):
                    with target.cursor() as target_cursor:
                        target_cursor.executemany(insert, rows)

            for table in ("messages", "message_events"):
                target.execute(
                    f"SELECT setval(pg_get_serial_sequence('{table}','id'), COALESCE(MAX(id),1), MAX(id) IS NOT NULL) FROM {table}"
                )

            report: dict[str, dict[str, object]] = {}
            for table, columns in TABLES.items():
                quoted_columns = ",".join(f'"{name}"' for name in columns)
                ordering = ",".join(f'"{name}"' for name in ORDER_COLUMNS[table])
                source_result = digest_rows(source.execute(f'SELECT {quoted_columns} FROM "{table}" ORDER BY {ordering}'))
                target_result = digest_rows(target.execute(f'SELECT {quoted_columns} FROM "{table}" ORDER BY {ordering}'))
                if source_result != target_result:
                    raise ValueError(f"Count/checksum mismatch in {table}")
                report[table] = {"rows": source_result[0], "sha256": source_result[1]}
        return report
    finally:
        if target is not None:
            target.close()
        source.close()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", required=True, type=Path, help="Path to a consistent SQLite backup, never the live DB")
    parser.add_argument("--confirm-target", required=True, help="Must be exactly salom_prod")
    args = parser.parse_args()
    dsn = os.environ.get("SALOM_DATABASE_URL", "")
    if not dsn:
        parser.error("Set SALOM_DATABASE_URL in the protected environment; credentials are never printed")
    try:
        result = migrate(args.source, dsn, args.confirm_target)
    except Exception as error:
        print(f"Production snapshot import failed: {type(error).__name__}", file=sys.stderr)
        return 1
    print(json.dumps(result, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
