"""Safety guard tests for the one-shot production snapshot importer."""
import unittest
from pathlib import Path
import sqlite3
import tempfile
from unittest.mock import patch

import app
from migrate_sqlite_to_postgres import validate_source
from migrate_sqlite_to_postgres_prod import PRODUCTION_DATABASE, validate_target_name


class ProductionMigrationGuardTests(unittest.TestCase):
    def test_requires_the_named_production_target_and_matching_confirmation(self):
        validate_target_name(PRODUCTION_DATABASE, PRODUCTION_DATABASE)

    def test_refuses_other_databases_and_mismatched_confirmation(self):
        cases = (("salom_test", "salom_prod"), ("salom_prod", "salom_test"), ("postgres", "salom_prod"))
        for actual_name, confirmation in cases:
            with self.subTest(actual_name=actual_name, confirmation=confirmation):
                with self.assertRaises(ValueError):
                    validate_target_name(actual_name, confirmation)

    def test_snapshot_validation_refuses_unrecognized_user_tables(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "snapshot.sqlite3"
            with patch.object(app, "DB", source), patch.object(app, "PG_DSN", ""):
                app.initialize()
            db = sqlite3.connect(source)
            try:
                db.execute("CREATE TABLE unrecognized_user_data (id TEXT)")
                db.commit()
            finally:
                db.close()
            with self.assertRaisesRegex(ValueError, "Unexpected SQLite table set"):
                validate_source(source)

    def test_snapshot_validation_accepts_complete_current_schema(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "snapshot.sqlite3"
            with patch.object(app, "DB", source), patch.object(app, "PG_DSN", ""):
                app.initialize()
            db, found_tables = validate_source(source)
            try:
                self.assertTrue(found_tables)
            finally:
                db.close()


if __name__ == "__main__":
    unittest.main()
