import json
import sqlite3
import re
from pathlib import Path
from contextlib import contextmanager
from datetime import datetime, timezone

from .configuration import builtin_profiles, initial_config


def utc():
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


class Store:
    def __init__(self, root):
        self.root = root
        self.path = root / "manager.sqlite3"
        root.mkdir(mode=0o700, parents=True, exist_ok=True)
        with self.db() as db:
            db.execute("PRAGMA journal_mode=WAL")
            db.executescript("""
                CREATE TABLE IF NOT EXISTS configuration (
                    id INTEGER PRIMARY KEY CHECK (id=1), revision INTEGER NOT NULL,
                    config TEXT NOT NULL, profiles TEXT NOT NULL, secrets TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS previous (id INTEGER PRIMARY KEY CHECK (id=1), payload TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS jobs (
                    id TEXT PRIMARY KEY, kind TEXT NOT NULL, state TEXT NOT NULL,
                    created TEXT NOT NULL, updated TEXT NOT NULL, phase TEXT NOT NULL,
                    input TEXT NOT NULL, result TEXT, stop INTEGER NOT NULL DEFAULT 0);
                CREATE UNIQUE INDEX IF NOT EXISTS only_one_active
                    ON jobs ((1)) WHERE state IN ('queued', 'running');
                CREATE TABLE IF NOT EXISTS events (
                    id INTEGER PRIMARY KEY AUTOINCREMENT, time TEXT NOT NULL,
                    component TEXT NOT NULL, level TEXT NOT NULL,
                    job TEXT, code TEXT NOT NULL, detail TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS install_events (identity TEXT PRIMARY KEY);
                CREATE TABLE IF NOT EXISTS push_devices (
                    id TEXT PRIMARY KEY, endpoint TEXT NOT NULL, secret TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS push_outbox (
                    run TEXT NOT NULL, device TEXT NOT NULL, created INTEGER NOT NULL,
                    next INTEGER NOT NULL, attempts INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(run,device));
                CREATE TABLE IF NOT EXISTS schedule (
                    id INTEGER PRIMARY KEY CHECK (id=1), spec TEXT NOT NULL, next REAL);
                CREATE TABLE IF NOT EXISTS source_history (
                    source TEXT PRIMARY KEY, status TEXT NOT NULL,
                    checked TEXT NOT NULL, last_success TEXT);
            """)
            db.execute("INSERT OR IGNORE INTO configuration VALUES (1, 1, ?, ?, '{}')",
                       (json.dumps(initial_config()), json.dumps(builtin_profiles())))
            db.execute("INSERT OR IGNORE INTO schedule VALUES (1, '{\"mode\":\"off\"}', NULL)")

    @contextmanager
    def db(self):
        con = sqlite3.connect(self.path, timeout=8)
        con.row_factory = sqlite3.Row
        try:
            with con:
                yield con
        finally:
            con.close()

    def settings(self, db):
        row = db.execute("SELECT * FROM configuration WHERE id=1").fetchone()
        return {"revision": row["revision"], **{key: json.loads(row[key]) for key in ("config", "profiles", "secrets")}}

    def put_settings(self, db, current, updated):
        db.execute("INSERT OR REPLACE INTO previous VALUES (1, ?)", (json.dumps(current),))
        db.execute("UPDATE configuration SET revision=?, config=?, profiles=?, secrets=? WHERE id=1",
                   (current["revision"] + 1, *(json.dumps(updated[key]) for key in ("config", "profiles", "secrets"))))

    def import_installer_events(self, source=Path("/var/lib/horizon-manager-install/events")):
        if not source.is_file():
            return
        lines = source.read_text(encoding="ascii", errors="replace").splitlines()
        if len(lines) > 650:
            lines = lines[-600:]
        with self.db() as db:
            for offset in range(0, len(lines) - 5, 6):
                fields = dict(line.split("=", 1) for line in lines[offset:offset + 6] if "=" in line)
                status, phase, code, operation, updated = (fields.get(k, "") for k in ("install", "step", "code", "operation", "updated"))
                if not (status in {"running", "success", "failed"}
                        and phase in {"preflight", "packages", "files", "runtime", "service", "health"}
                        and code.isdecimal() and len(code) <= 3
                        and re.fullmatch(r"[a-f0-9-]{36}", operation)
                        and re.fullmatch(r"\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ", updated)):
                    continue
                identity = f"{operation}:{status}:{phase}:{updated}"
                if db.execute("INSERT OR IGNORE INTO install_events VALUES (?)", (identity,)).rowcount:
                    db.execute("INSERT INTO events(time,component,level,job,code,detail) VALUES (?,?,?,?,?,?)",
                               (updated, "vps", "ERROR" if status == "failed" else "INFO", operation,
                                "INSTALL_" + status.upper(), f"{phase} · exit {code}"))
            db.execute("DELETE FROM install_events WHERE rowid <= (SELECT max(rowid) - 1000 FROM install_events)")

    @staticmethod
    def event(db, code, level="INFO", component="agent", job=None, detail=""):
        db.execute("INSERT INTO events(time,component,level,job,code,detail) VALUES (?,?,?,?,?,?)",
                   (utc(), component, level, job, code, str(detail)[:1000]))
        db.execute("DELETE FROM events WHERE id <= (SELECT max(id) - 10000 FROM events)")

    @staticmethod
    def queue(db, job_id, kind, payload):
        ts = utc()
        db.execute("INSERT INTO jobs(id,kind,state,created,updated,phase,input) VALUES (?,?,'queued',?,?,'queued',?)",
                   (job_id, kind, ts, ts, json.dumps(payload)))
        Store.event(db, "JOB_QUEUED", job=job_id, detail=kind)
