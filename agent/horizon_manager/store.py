import json
import sqlite3
import re
from pathlib import Path
from contextlib import contextmanager
from datetime import datetime, timezone

from .router_catalog import ROUTERS
from .configuration import (DEFAULT_HEADLINE_RANGE, DEFAULT_HEADLINE_WORDS,
                            DIGEST_LANGUAGES, ProfileDocument, builtin_profiles, initial_config)

DEFAULT_ARTICLE_OPTIONS = {"card_text_mode": "summary", "show_score_reason": False}
PROFILE_TEXT_FIELDS = {"summary_mode", "summary_instruction"}


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
                CREATE TABLE IF NOT EXISTS ai_provider_settings (provider TEXT PRIMARY KEY, config TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS previous (id INTEGER PRIMARY KEY CHECK (id=1), payload TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS jobs (
                    id TEXT PRIMARY KEY, kind TEXT NOT NULL, state TEXT NOT NULL,
                    created TEXT NOT NULL, updated TEXT NOT NULL, phase TEXT NOT NULL,
                    input TEXT NOT NULL, result TEXT, stop INTEGER NOT NULL DEFAULT 0);
                CREATE UNIQUE INDEX IF NOT EXISTS one_active_pipeline
                    ON jobs ((1)) WHERE kind != 'article_context' AND state IN ('queued', 'running');
                CREATE UNIQUE INDEX IF NOT EXISTS one_running_article
                    ON jobs ((1)) WHERE kind = 'article_context' AND state = 'running';
                CREATE TABLE IF NOT EXISTS article_context_jobs (
                    run TEXT NOT NULL, article TEXT NOT NULL, job TEXT NOT NULL REFERENCES jobs(id),
                    PRIMARY KEY(run,article));
                CREATE TABLE IF NOT EXISTS enrichment_options (
                    id INTEGER PRIMARY KEY CHECK(id=1), mode TEXT NOT NULL
                        CHECK(mode IN ('on_demand', 'automatic')));
                CREATE TABLE IF NOT EXISTS article_display_options (
                    id INTEGER PRIMARY KEY CHECK(id=1), payload TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS profile_text_options (
                    profile TEXT PRIMARY KEY, payload TEXT NOT NULL);
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
                CREATE TABLE IF NOT EXISTS headline_options (
                    id INTEGER PRIMARY KEY CHECK(id=1), words INTEGER NOT NULL CHECK(words BETWEEN 2 AND 30));
                CREATE TABLE IF NOT EXISTS headline_ranges (
                    id INTEGER PRIMARY KEY CHECK(id=1), minimum INTEGER NOT NULL CHECK(minimum >= 1),
                    maximum INTEGER NOT NULL CHECK(maximum BETWEEN minimum AND 240));
                CREATE TABLE IF NOT EXISTS media_options (id INTEGER PRIMARY KEY CHECK(id=1), enabled INTEGER NOT NULL);
                CREATE TABLE IF NOT EXISTS schedule (
                    id INTEGER PRIMARY KEY CHECK (id=1), spec TEXT NOT NULL, next REAL);
                CREATE TABLE IF NOT EXISTS source_history (
                    source TEXT PRIMARY KEY, status TEXT NOT NULL,
                    checked TEXT NOT NULL, last_success TEXT);
            """)
            db.execute("BEGIN IMMEDIATE")
            self.migrate_digest_options(db)
            self.migrate_article_options(db)
            db.execute("DROP INDEX IF EXISTS only_one_active")
            db.execute("INSERT OR IGNORE INTO configuration VALUES (1, 1, ?, ?, '{}')",
                       (json.dumps(initial_config()), json.dumps(self.split_profiles(builtin_profiles())[0])))
            db.execute("INSERT OR IGNORE INTO schedule VALUES (1, '{\"mode\":\"off\"}', NULL)")

    @staticmethod
    def migrate_article_options(db):
        # Import preferences from the 0.6.6 test agent while keeping its tables readable.
        if db.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='profile_summary_instructions'").fetchone():
            for profile, instruction in db.execute("SELECT profile, instruction FROM profile_summary_instructions"):
                payload = json.dumps({"summary_mode": "news", "summary_instruction": instruction})
                db.execute("INSERT OR IGNORE INTO profile_text_options VALUES (?, ?)", (profile, payload))
        if db.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='article_options'").fetchone():
            row = db.execute("SELECT show_reason FROM article_options WHERE id=1").fetchone()
            if row:
                db.execute("INSERT OR IGNORE INTO article_display_options VALUES (1, ?)",
                           (json.dumps({"show_score_reason": bool(row[0])}),))

    @staticmethod
    def migrate_digest_options(db):
        languages = ", ".join(repr(code) for code in DIGEST_LANGUAGES)
        definition = (f"CREATE TABLE digest_options (id INTEGER PRIMARY KEY CHECK(id=1), "
                      f"language TEXT NOT NULL CHECK(language IN ({languages})))")
        previous = db.execute("SELECT sql FROM sqlite_master WHERE type='table' AND name='digest_options'").fetchone()
        if previous is None:
            db.execute(definition)
        elif previous[0] != definition:
            db.execute("ALTER TABLE digest_options RENAME TO prior_digest_options")
            db.execute(definition)
            db.execute("INSERT INTO digest_options SELECT id,language FROM prior_digest_options "
                       f"WHERE language IN ({languages})")
            db.execute("DROP TABLE prior_digest_options")

    @contextmanager
    def db(self):
        con = sqlite3.connect(self.path, timeout=8)
        con.row_factory = sqlite3.Row
        try:
            with con:
                yield con
        finally:
            con.close()

    @staticmethod
    def media_search(db):
        row = db.execute("SELECT enabled FROM media_options WHERE id=1").fetchone()
        return bool(row and row[0])

    @staticmethod
    def enrichment_mode(db):
        row = db.execute("SELECT mode FROM enrichment_options WHERE id=1").fetchone()
        return row[0] if row else "on_demand"

    @staticmethod
    def set_enrichment_mode(db, mode):
        db.execute("INSERT OR REPLACE INTO enrichment_options VALUES (1, ?)", (mode,))

    @staticmethod
    def headline_words(db):
        row = db.execute("SELECT words FROM headline_options WHERE id=1").fetchone()
        return row[0] if row else DEFAULT_HEADLINE_WORDS

    @staticmethod
    def set_headline_words(db, words):
        db.execute("INSERT OR REPLACE INTO headline_options VALUES (1, ?)", (words,))

    @staticmethod
    def headline_range(db):
        row = db.execute("SELECT minimum, maximum FROM headline_ranges WHERE id=1").fetchone()
        if row:
            return tuple(row)
        legacy = db.execute("SELECT words FROM headline_options WHERE id=1").fetchone()
        if legacy:
            return DEFAULT_HEADLINE_RANGE[0], legacy[0] * 6
        return DEFAULT_HEADLINE_RANGE

    @staticmethod
    def set_headline_range(db, minimum, maximum):
        db.execute("INSERT OR REPLACE INTO headline_ranges VALUES (1, ?, ?)", (minimum, maximum))

    @staticmethod
    def digest_language(db):
        row = db.execute("SELECT language FROM digest_options WHERE id=1").fetchone()
        if row:
            return row[0], True
        row = db.execute("SELECT json_extract(config, '$.ai.languages') FROM configuration WHERE id=1").fetchone()
        existing = json.loads(row[0]) if row and row[0] else []
        if len(existing) == 1 and existing[0] in DIGEST_LANGUAGES:
            return existing[0], True
        return "en", False

    @staticmethod
    def set_digest_language(db, language):
        db.execute("INSERT OR REPLACE INTO digest_options VALUES (1, ?)", (language,))

    @staticmethod
    def article_options(db):
        row = db.execute("SELECT payload FROM article_display_options WHERE id=1").fetchone()
        return {**DEFAULT_ARTICLE_OPTIONS, **(json.loads(row[0]) if row else {})}

    @staticmethod
    def set_article_options(db, options):
        row = db.execute("SELECT payload FROM article_display_options WHERE id=1").fetchone()
        updated = {**(json.loads(row[0]) if row else {}), **options}
        db.execute("INSERT OR REPLACE INTO article_display_options VALUES (1, ?)", (json.dumps(updated),))
        return {**DEFAULT_ARTICLE_OPTIONS, **updated}

    @staticmethod
    def merge_profiles(profiles, options):
        return [
            ProfileDocument.model_validate({**profile, **options.get(profile["definition"]["id"], {})})
            .model_dump(mode="json")
            for profile in profiles
        ]

    @staticmethod
    def split_profiles(profiles):
        # Keep configuration and saved runs readable by older agents after a downgrade.
        documents = [ProfileDocument.model_validate(profile) for profile in profiles]
        original = [doc.model_dump(mode="json", exclude=PROFILE_TEXT_FIELDS) for doc in documents]
        options = {
            doc.definition.id: doc.model_dump(mode="json", include=PROFILE_TEXT_FIELDS)
            for doc in documents
        }
        return original, options

    def settings(self, db):
        row = db.execute("SELECT * FROM configuration WHERE id=1").fetchone()
        settings = {"revision": row["revision"], **{key: json.loads(row[key]) for key in ("config", "profiles", "secrets")}}
        options = {entry["profile"]: json.loads(entry["payload"]) for entry in db.execute("SELECT * FROM profile_text_options")}
        settings["profiles"] = self.merge_profiles(settings["profiles"], options)
        return settings

    @staticmethod
    def provider_settings(db):
        return {row["provider"]: json.loads(row["config"]) for row in db.execute("SELECT * FROM ai_provider_settings")}

    def put_settings(self, db, current, updated):
        for settings in (current, updated):
            ai = settings["config"]["ai"]
            provider = ai["provider"]
            if provider == "openai":
                url = str(ai.get("base_url") or "").rstrip("/")
                provider = next((name for name, spec in ROUTERS.items() if spec[0] == url), provider)
            db.execute("INSERT OR REPLACE INTO ai_provider_settings VALUES (?, ?)", (provider, json.dumps(ai)))
        self.put_configuration(db, current, updated)

    def put_configuration(self, db, current, updated):
        previous_profiles, previous_options = self.split_profiles(current["profiles"])
        previous = {**current, "profiles": previous_profiles, "profile_text_options": previous_options}
        db.execute("INSERT OR REPLACE INTO previous VALUES (1, ?)", (json.dumps(previous),))
        profiles, options = self.split_profiles(updated["profiles"])
        db.execute("DELETE FROM profile_text_options")
        db.executemany("INSERT INTO profile_text_options VALUES (?, ?)",
                       ((profile, json.dumps(payload)) for profile, payload in options.items()))
        db.execute("UPDATE configuration SET revision=?, config=?, profiles=?, secrets=? WHERE id=1",
                   (current["revision"] + 1, json.dumps(updated["config"]), json.dumps(profiles), json.dumps(updated["secrets"])))

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
