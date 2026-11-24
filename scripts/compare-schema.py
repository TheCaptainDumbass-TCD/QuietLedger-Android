#!/usr/bin/env python3
"""
Compares the Room schema against the desktop app's SQLite schema.

The two apps hand databases to each other through the .ftbackup format, so the
schemas have to stay identical. This builds a database using the desktop app's
own code, then diffs it against the schema Room exports at build time.

Run it after changing either side:

    python3 scripts/compare-schema.py [path-to-desktop-repo]

Exits non-zero if they disagree, so it can be wired into a check later.
"""

import glob
import json
import os
import sqlite3
import sys
import tempfile
from pathlib import Path

DEFAULT_DESKTOP_REPO = Path.home() / "QuietLedger"


def desktop_schema(repo: Path) -> dict:
    """Build a fresh database using the desktop app's own DatabaseManager."""
    if not (repo / "src" / "database" / "db_manager.py").exists():
        raise SystemExit(f"Desktop repo not found at {repo}")

    sys.path.insert(0, str(repo))
    from src.database.db_manager import DatabaseManager  # noqa: E402

    tmp = tempfile.mkdtemp()
    db_path = Path(tmp) / "finance_data.db"
    DatabaseManager(db_path)

    conn = sqlite3.connect(db_path)
    tables = {}
    query = "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'"
    for (name,) in conn.execute(query):
        tables[name] = {
            row[1]: row[2].upper() for row in conn.execute(f"PRAGMA table_info('{name}')")
        }
    return tables


def room_schema() -> dict:
    here = Path(__file__).resolve().parent.parent
    matches = sorted(glob.glob(str(here / "app" / "schemas" / "*" / "*.json")))
    if not matches:
        raise SystemExit("No exported Room schema found. Run a build first.")

    # Highest schema version wins.
    latest = max(matches, key=lambda p: int(os.path.basename(p).split(".")[0]))
    data = json.load(open(latest))
    return {
        entity["tableName"]: {
            field["columnName"]: field["affinity"].upper() for field in entity["fields"]
        }
        for entity in data["database"]["entities"]
    }


def main() -> int:
    repo = Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_DESKTOP_REPO
    desktop = desktop_schema(repo)
    room = room_schema()

    problems = 0

    only_desktop = sorted(set(desktop) - set(room))
    only_room = sorted(set(room) - set(desktop))
    if only_desktop:
        print(f"Tables only in desktop: {only_desktop}")
        problems += len(only_desktop)
    if only_room:
        print(f"Tables only in Room:    {only_room}")
        problems += len(only_room)

    for table in sorted(set(desktop) & set(room)):
        d, r = desktop[table], room[table]
        missing = sorted(set(d) - set(r))
        extra = sorted(set(r) - set(d))
        mismatched = [(c, d[c], r[c]) for c in sorted(set(d) & set(r)) if d[c] != r[c]]

        ok = not (missing or extra or mismatched)
        print(f"{table:<20} {'OK' if ok else 'MISMATCH'}   ({len(d)} columns)")
        if missing:
            print(f"    missing in Room : {missing}")
        if extra:
            print(f"    extra in Room   : {extra}")
        if mismatched:
            print(f"    type differs    : {mismatched}")
        if not ok:
            problems += 1

    print()
    if problems:
        print(f"FAIL: {problems} difference(s)")
        return 1
    print("PASS: schemas agree")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
