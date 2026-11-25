import asyncio
import importlib.util
import json
import os
import signal
import sys
import time
from contextlib import suppress
from datetime import datetime, timezone
from uuid import uuid4

from .configuration import ai_missing, only_source, validate
from .push import enqueue_digest
from .scheduler import Schedule, next_run
from .store import Store, utc

ACTIVE = "state IN ('queued','running')"
PIPELINE_ACTIVE = f"kind != 'article_context' AND {ACTIVE}"
CONTEXT_TIMEOUT = 180


def recovery(store):
    with store.db() as db:
        for row in db.execute("SELECT id,kind,updated FROM jobs WHERE state='running'").fetchall():
            Store.event(db, "INTERRUPTED_BY_RESTART", "WARNING", job=row["id"])
            if row["kind"] == "article_context":
                started = db.execute("""SELECT time FROM events
                    WHERE job=? AND code='ARTICLE_CONTEXT_STARTED' ORDER BY id DESC LIMIT 1""", (row["id"],)).fetchone()
                elapsed = datetime.now(timezone.utc) - datetime.fromisoformat(started[0] if started else row["updated"])
                Store.event(db, "ARTICLE_CONTEXT_INTERRUPTED", "WARNING", "horizon", row["id"],
                            f"{max(0, int(elapsed.total_seconds() * 1000))} ms · INTERRUPTED_BY_RESTART")
        db.execute("UPDATE jobs SET phase='INTERRUPTED_BY_RESTART' WHERE kind='article_context' AND state='running'")
        db.execute("UPDATE jobs SET state='interrupted',updated=? WHERE state='running'", (utc(),))


def claim(store, article=False):
    now = time.time()
    with store.db() as db:
        db.execute("BEGIN IMMEDIATE")
        schedule = db.execute("SELECT spec,next FROM schedule WHERE id=1").fetchone()
        if not article and schedule["next"] is not None and schedule["next"] <= now:
            spec = Schedule.model_validate_json(schedule["spec"])
            db.execute("UPDATE schedule SET next=? WHERE id=1", (next_run(spec, now, previous=schedule["next"]),))
            if db.execute(f"SELECT 1 FROM jobs WHERE {PIPELINE_ACTIVE}").fetchone():
                Store.event(db, "SCHEDULE_SKIPPED_ACTIVE", "WARNING", "scheduler")
            else:
                Store.queue(db, uuid4().hex, "run", {"trigger": "schedule"})
        predicate = "kind = 'article_context'" if article else "kind != 'article_context'"
        row = db.execute(f"SELECT * FROM jobs WHERE state='queued' AND {predicate} ORDER BY rowid").fetchone()
        if row is not None:
            db.execute("UPDATE jobs SET state='running',updated=? WHERE id=?", (utc(), row["id"]))
            return dict(row), store.settings(db), store.enrichment_mode(db), store.digest_language(db)[0]
    return None


def consume(store, job, event):
    digest_ready = False
    with store.db() as db:
        kind = event["kind"]
        if kind == "phase":
            db.execute("UPDATE jobs SET phase=?,updated=? WHERE id=?", (event["phase"], utc(), job))
            Store.event(db, event["phase"], component="horizon", job=job, detail=event["detail"])
        elif kind == "result":
            data = event["data"]
            digest_ready = bool(data.get("languages")) and not data.get("localized_run")
            if digest_ready:
                data = {**data, "ready_at": utc()}
                enqueue_digest(db, job)
            db.execute("UPDATE jobs SET result=? WHERE id=?", (json.dumps(data), job))
        elif kind == "log":
            Store.event(db, event["code"], event["level"], "horizon", job, event["detail"])
        elif kind == "source":
            status, name = event["status"], event["source"]
            db.execute("""INSERT INTO source_history VALUES (?,?,?,?)
                ON CONFLICT(source) DO UPDATE SET status=excluded.status,checked=excluded.checked,
                last_success=COALESCE(excluded.last_success,source_history.last_success)""",
                (name, status, utc(), utc() if status in {"success", "empty"} else None))
            Store.event(db, "SOURCE_" + status.upper(),
                        "WARNING" if "failure" in status else "INFO", "horizon", job,
                        f"{name} · {event['count']} · {event['reason']}".rstrip(" ·"))

    return digest_ready


async def terminate(process):
    if process.returncode is not None:
        return
    with suppress(ProcessLookupError):
        os.killpg(process.pid, signal.SIGTERM)
    try:
        await asyncio.wait_for(process.wait(), 5)
    except TimeoutError:
        with suppress(ProcessLookupError):
            os.killpg(process.pid, signal.SIGKILL)
        await process.wait()


async def execute(store, row, settings, enrichment_mode, digest_language, on_digest=None):
    job = row["id"]
    article = row["kind"] == "article_context"
    start = time.monotonic()
    state = "failed"
    failure = "UPSTREAM_FAILURE"
    process, line = None, None
    if article:
        with store.db() as db:
            Store.event(db, "ARTICLE_CONTEXT_STARTED", component="horizon", job=job)
    try:
        config = settings["config"]
        params = json.loads(row["input"])
        if row["kind"] == "source":
            config = only_source(config, params["source"], params.get("bucket"), params.get("index"))
        model, enabled, _ = validate(
            config, settings["profiles"], settings["secrets"], ready=row["kind"] == "run",
            openbb=importlib.util.find_spec("openbb") is not None,
        )
        if row["kind"] == "run" and not enabled:
            raise ValueError("NO_ENABLED_SOURCES")
        if row["kind"] in {"ai", "localize", "article_context"}:
            missing = ai_missing(model.ai, settings["secrets"])
            if missing:
                raise ValueError("AI_SETUP_REQUIRED")
        env = {"PATH": "/usr/bin:/bin", "LANG": "C.UTF-8", "HOME": str(store.root),
               "HM_STATE": str(store.root), "PYTHONPATH": os.path.dirname(os.path.dirname(__file__)),
               **settings["secrets"]}
        process = await asyncio.create_subprocess_exec(
            sys.executable, "-m", "horizon_manager.worker", job,
            stdin=asyncio.subprocess.PIPE, stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.DEVNULL, start_new_session=True, env=env, limit=4 * 1024 * 1024,
        )
        with store.db() as db:
            search = store.media_search(db)
            headline_range = store.headline_range(db)
        process.stdin.write(json.dumps({"media_search": search, "enrichment_mode": enrichment_mode, "language": digest_language,
                                        "headline_range": headline_range, "settings": settings,
                                        "kind": row["kind"], "params": params}).encode())
        await process.stdin.drain()
        process.stdin.close()
        deadline = time.monotonic() + {"ai": 95, "source": 180, "run": 7200, "localize": 7200,
                                       "article_context": CONTEXT_TIMEOUT}[row["kind"]]
        line = asyncio.create_task(process.stdout.readline())
        while True:
            with store.db() as db:
                cancelled = db.execute("SELECT stop FROM jobs WHERE id=?", (job,)).fetchone()[0]
            if cancelled or time.monotonic() >= deadline:
                state = "cancelled" if cancelled else "timeout"
                break
            done, _ = await asyncio.wait([line], timeout=0.5)
            if not done:
                continue
            raw = line.result()
            if not raw:
                status = await process.wait()
                state = "success" if status == 0 else "failed"
                if status:
                    with store.db() as db:
                        Store.event(db, "HORIZON_EXIT", "ERROR", "horizon", job, str(status))
                break
            event = json.loads(raw)
            if event["kind"] == "log" and event["level"] == "ERROR":
                failure = event["code"]
            if consume(store, job, event) and on_digest is not None:
                await on_digest()
            line = asyncio.create_task(process.stdout.readline())
    except asyncio.CancelledError:
        state = "interrupted"
        raise
    except Exception as exc:
        code = str(exc) if type(exc) is ValueError and str(exc).isupper() and str(exc).replace("_", "").isalpha() else "JOB_ERROR"
        failure = code
        with store.db() as db:
            Store.event(db, code[:80], "ERROR", job=job, detail=type(exc).__name__)
    finally:
        if line and not line.done():
            line.cancel()
            with suppress(asyncio.CancelledError):
                await line
        if process:
            await terminate(process)
        with store.db() as db:
            db.execute("UPDATE jobs SET state=?,updated=? WHERE id=?", (state, utc(), job))
            Store.event(db, "JOB_" + state.upper(), "INFO" if state == "success" else "WARNING", job=job)
            if article:
                reason = {"timeout": "TIMEOUT", "interrupted": "INTERRUPTED_BY_RESTART",
                          "cancelled": "CANCELLED", "failed": failure}.get(state, "")
                db.execute("UPDATE jobs SET phase=? WHERE id=?", (reason or "complete", job))
                Store.event(db, "ARTICLE_CONTEXT_" + state.upper(),
                            "INFO" if state == "success" else "WARNING", "horizon", job,
                            f"{int((time.monotonic() - start) * 1000)} ms" + (f" · {reason}" if reason else ""))


async def loop(store, article=False, on_digest=None):
    while True:
        try:
            claimed = claim(store, article=article)
            if claimed:
                await execute(store, *claimed, on_digest=on_digest)
        except (OSError, ValueError):
            with store.db() as db:
                Store.event(db, "SCHEDULER_ERROR", "ERROR", "scheduler")
        await asyncio.sleep(1)
