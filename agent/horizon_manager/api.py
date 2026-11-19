import asyncio
import hmac
import httpx
import importlib.util
import json
import os
import re
import shutil
import threading
import time
from contextlib import asynccontextmanager, suppress
from datetime import datetime, timezone
from pathlib import Path
from typing import Literal
from uuid import UUID, uuid4

from fastapi import Depends, FastAPI, Header, HTTPException, Query, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ConfigDict, Field, ValidationError
from src.models import SOURCE_REGISTRY
from src.processing.content import split_content

from . import VERSION, backup, jobs, push, router_catalog
from .configuration import DIGEST_LANGUAGES, ai_missing, schema, secret_name_allowed, validate
from .scheduler import Schedule, next_run
from .store import Store

REVISION = "12d5fbde4d9e0776a48618ea65d16fe6d1e1b606"
ID = re.compile(r"(?:[a-f0-9]{32}|run-\d{8}T\d{6}Z-[a-f0-9]{8})")
LIMIT = 10 * 1024 * 1024


class Body(BaseModel):
    model_config = ConfigDict(extra="forbid")


class ConfigInput(Body):
    revision: int
    config: dict
    profiles: list[dict]
    secrets: dict[str, str | None] = Field(default_factory=dict, max_length=50)


class Revision(Body):
    revision: int


class JobInput(Body):
    id: UUID
    kind: Literal["run", "source", "ai", "localize"] = "run"
    source: str | None = Field(default=None, max_length=20)
    bucket: str | None = Field(default=None, max_length=20)
    index: int | None = Field(default=None, ge=0, le=10000)
    run: str | None = Field(default=None, max_length=32)


class CatalogKey(Body):
    key: str = Field(min_length=8, max_length=512)


class PushDevice(Body):
    endpoint: str = Field(max_length=512)
    secret: str = Field(pattern=r"^[a-f0-9]{64}$")


class Password(Body):
    password: str = Field(min_length=12, max_length=256)


class ImportBackup(Password):
    archive: str = Field(max_length=9_000_000)
    revision: int


def fail(status, code, **details):
    raise HTTPException(status, detail={"code": code, **details})


def check_config(config, profiles, secrets):
    try:
        return validate(config, profiles, secrets, openbb=importlib.util.find_spec("openbb") is not None)
    except ValidationError as exc:
        fields = [".".join(map(str, error["loc"])) for error in exc.errors(include_input=False, include_context=False)]
        fail(422, "CONFIG_FIELDS", fields=fields[:20])
    except (OSError, ValueError, KeyError, TypeError) as exc:
        code = str(exc) if type(exc) is ValueError and re.fullmatch(r"[A-Z_]{2,55}", str(exc)) else "CONFIG_OR_PROFILE_INVALID"
        fail(422, code)


def public(config):
    return {**config, "secrets": sorted(config["secrets"])}


def create_app(root, token):
    store = Store(root)
    upstream_lock = threading.Lock()
    upstream_cache = {}
    installer_lock = threading.Lock()
    installer_stamp = None

    def sync_installer_events():
        nonlocal installer_stamp
        path = Path("/var/lib/horizon-manager-install/events")
        with installer_lock, suppress(OSError):
            stat = path.stat()
            stamp = stat.st_mtime_ns, stat.st_size
            if stamp != installer_stamp:
                store.import_installer_events(path)
                installer_stamp = stamp

    @asynccontextmanager
    async def lifespan(app):
        sync_installer_events()
        jobs.recovery(store)
        tasks = [asyncio.create_task(jobs.loop(store)), asyncio.create_task(push.loop(store))]
        try:
            yield
        finally:
            for task in tasks:
                task.cancel()
            await asyncio.gather(*tasks, return_exceptions=True)

    def authorized(authorization: str = Header(default="")):
        if not hmac.compare_digest(authorization.encode(), ("Bearer " + token).encode()):
            fail(401, "AUTH_REQUIRED")

    app = FastAPI(title="Horizon Manager", version=VERSION, lifespan=lifespan,
                  dependencies=[Depends(authorized)], docs_url=None, redoc_url=None, openapi_url=None)

    @app.middleware("http")
    async def restrict_body(request: Request, call_next):
        if request.method in {"POST", "PUT"}:
            length = request.headers.get("content-length", "")
            if not length.isdecimal() or int(length) > LIMIT or request.headers.get("transfer-encoding"):
                return JSONResponse(status_code=413, content={"detail": {"code": "BODY_LIMIT"}})
        return await call_next(request)

    @app.exception_handler(RequestValidationError)
    async def validation_error(request, exc):
        fields = [".".join(map(str, item["loc"])) for item in exc.errors()]
        return JSONResponse(status_code=422, content={"detail": {"code": "REQUEST_INVALID", "fields": fields[:20]}})

    @app.exception_handler(Exception)
    async def internal_error(request, exc):
        with suppress(Exception), store.db() as db:
            Store.event(db, "INTERNAL_ERROR", "ERROR", detail=type(exc).__name__)
        return JSONResponse(status_code=500, content={"detail": {"code": "INTERNAL_ERROR"}})

    @app.get("/v1/health")
    def health():
        return {"api": 1, "agent": VERSION, "horizon": REVISION,
                "runtime": "systemd/venv", "openbb": importlib.util.find_spec("openbb") is not None}

    @app.get("/v1/upstream")
    def upstream():
        with upstream_lock:
            if time.monotonic() >= upstream_cache.get("until", 0):
                try:
                    response = httpx.get("https://api.github.com/repos/Thysrael/Horizon/commits/main",
                                         headers={"Accept": "application/vnd.github+json"}, timeout=7)
                    response.raise_for_status()
                    head = response.json()["sha"]
                    if not re.fullmatch(r"[0-9a-f]{40}", head):
                        raise ValueError
                except (httpx.HTTPError, KeyError, ValueError, TypeError):
                    fail(502, "UPSTREAM_CHECK_UNAVAILABLE")
                upstream_cache.update(until=time.monotonic() + 300, head=head)
            return {"installed": REVISION, "upstream_head": upstream_cache["head"],
                    "compatible_revisions": [REVISION], "agent": VERSION}

    @app.get("/v1/schema")
    def get_schema():
        return schema()

    @app.get("/v1/status")
    def status():
        disk = shutil.disk_usage(root)
        mem = {}
        for line in Path("/proc/meminfo").read_text().splitlines():
            if line.startswith(("MemTotal:", "MemAvailable:")):
                key, value, _ = line.split()
                mem[key.rstrip(":")] = int(value) * 1024
        with store.db() as db:
            next_at = db.execute("SELECT next FROM schedule WHERE id=1").fetchone()[0]
            return {**health(), "disk_free": disk.free, "disk_total": disk.total,
                    "load": os.getloadavg()[0], "cpu": os.cpu_count(), "memory": mem, "next": next_at}

    @app.get("/v1/config")
    def get_config():
        with store.db() as db:
            return public(store.settings(db))

    async def load_catalog(router, key):
        if not key:
            fail(404, "ROUTER_KEY_MISSING")
        try:
            return await router_catalog.fetch(router, key)
        except router_catalog.CatalogError as exc:
            fail(502, exc.code, fields=[str(exc.status)] if exc.status is not None else [])

    @app.get("/v1/ai/catalog/{router}")
    async def saved_router_catalog(router: Literal["openrouter", "unorouter"]):
        with store.db() as db:
            settings = store.settings(db)
        key = router_catalog.saved_key(router, settings["config"]["ai"], settings["secrets"])
        return await load_catalog(router, key)

    @app.post("/v1/ai/catalog/{router}")
    async def preview_router_catalog(router: Literal["openrouter", "unorouter"], body: CatalogKey):
        return await load_catalog(router, body.key)

    def no_active(db):
        if db.execute(f"SELECT 1 FROM jobs WHERE {jobs.ACTIVE}").fetchone():
            fail(409, "ACTIVE_JOB")

    @app.put("/v1/config")
    def set_config(body: ConfigInput):
        with store.db() as db:
            db.execute("BEGIN IMMEDIATE")
            no_active(db)
            current = store.settings(db)
            if current["revision"] != body.revision:
                fail(409, "CONFIG_CHANGED")
            secrets = current["secrets"].copy()
            for name, value in body.secrets.items():
                if not secret_name_allowed(name) or value is not None and (not 1 <= len(value) <= 8192 or "\0" in value):
                    fail(422, "SECRET_VALUE_INVALID")
                if value is None:
                    secrets.pop(name, None)
                else:
                    secrets[name] = value
            _, _, missing = check_config(body.config, body.profiles, secrets)
            store.put_settings(db, current, {"config": body.config, "profiles": body.profiles, "secrets": secrets})
            Store.event(db, "CONFIG_SAVED")
            return {**public(store.settings(db)), "missing": missing}

    @app.post("/v1/config/check")
    def check():
        with store.db() as db:
            current = store.settings(db)
        model, enabled, missing = check_config(current["config"], current["profiles"], current["secrets"])
        return {"enabled_sources": enabled, "missing": missing, "model_configured": bool(model.ai.model.strip())}

    @app.post("/v1/config/revert")
    def revert(body: Revision):
        with store.db() as db:
            db.execute("BEGIN IMMEDIATE")
            no_active(db)
            current = store.settings(db)
            if current["revision"] != body.revision:
                fail(409, "CONFIG_CHANGED")
            previous = db.execute("SELECT payload FROM previous WHERE id=1").fetchone()
            if not previous:
                fail(404, "NO_PREVIOUS_CONFIG")
            updated = json.loads(previous[0])
            check_config(updated["config"], updated["profiles"], updated["secrets"])
            store.put_settings(db, current, updated)
            Store.event(db, "CONFIG_REVERTED")
            return public(store.settings(db))

    @app.post("/v1/jobs")
    def submit(body: JobInput):
        if body.kind == "source" and body.source not in SOURCE_REGISTRY:
            fail(422, "SOURCE_UNKNOWN")
        if body.kind != "source" and any(value is not None for value in (body.source, body.bucket, body.index)):
            fail(422, "REQUEST_INVALID")
        if body.kind == "localize":
            if body.run is None:
                fail(422, "REQUEST_INVALID")
            artifacts(body.run)
        elif body.run is not None:
            fail(422, "REQUEST_INVALID")
        with store.db() as db:
            db.execute("BEGIN IMMEDIATE")
            job = body.id.hex
            data = body.model_dump(exclude={"id", "kind"}, exclude_none=True)
            existing = db.execute("SELECT kind,input FROM jobs WHERE id=?", (job,)).fetchone()
            if existing:
                if existing["kind"] != body.kind or json.loads(existing["input"]) != data:
                    fail(409, "JOB_ID_CONFLICT")
            else:
                no_active(db)
                if body.kind in {"ai", "localize"}:
                    settings = store.settings(db)
                    model, _, _ = check_config(settings["config"], settings["profiles"], settings["secrets"])
                    if missing := ai_missing(model.ai, settings["secrets"]):
                        fail(422, "AI_SETUP_REQUIRED", fields=missing)
                Store.queue(db, job, body.kind, data)
        return {"id": job}

    @app.get("/v1/jobs")
    def list_jobs(limit: int = Query(50, ge=1, le=100)):
        with store.db() as db:
            rows = db.execute("SELECT id,kind,state,created,updated,phase,result FROM jobs ORDER BY rowid DESC LIMIT ?", (limit,))
            return [{**dict(row), "result": json.loads(row["result"]) if row["result"] else None} for row in rows]

    @app.post("/v1/jobs/{job}/stop")
    def stop(job: str):
        with store.db() as db:
            db.execute("BEGIN IMMEDIATE")
            row = db.execute("SELECT state FROM jobs WHERE id=?", (job,)).fetchone()
            if row is None:
                fail(404, "JOB_UNKNOWN")
            if row["state"] == "queued":
                db.execute("UPDATE jobs SET state='cancelled' WHERE id=?", (job,))
            elif row["state"] == "running":
                db.execute("UPDATE jobs SET stop=1 WHERE id=?", (job,))
            else:
                fail(409, "JOB_FINISHED")
            Store.event(db, "STOP_REQUESTED", job=job)
        return {"id": job}

    @app.get("/v1/sources")
    def source_history():
        with store.db() as db:
            rows = db.execute("SELECT * FROM source_history ORDER BY checked DESC LIMIT 200")
            return [dict(row) for row in rows]

    @app.get("/v1/events")
    def events(after: int = Query(0, ge=0), limit: int = Query(200, ge=1, le=1000),
               latest: bool = False):
        sync_installer_events()
        with store.db() as db:
            order = "DESC" if latest else "ASC"
            rows = [dict(row) for row in db.execute(
                f"SELECT * FROM events WHERE id > ? ORDER BY id {order} LIMIT ?", (after, limit))]
            return rows[::-1] if latest else rows

    @app.delete("/v1/events")
    def clear_events(before: int = Query(ge=1)):
        with store.db() as db:
            count = db.execute("DELETE FROM events WHERE id <= ?", (before,)).rowcount
            Store.event(db, "JOURNAL_CLEARED", detail=str(count))
        return {"deleted": count}

    @app.get("/v1/schedule")
    def schedule():
        with store.db() as db:
            row = db.execute("SELECT spec,next FROM schedule WHERE id=1").fetchone()
            return {"spec": Schedule.model_validate_json(row["spec"]).model_dump(), "next": row["next"]}

    @app.put("/v1/schedule")
    def put_schedule(body: Schedule):
        upcoming = next_run(body, time.time())
        with store.db() as db:
            db.execute("UPDATE schedule SET spec=?,next=? WHERE id=1", (body.model_dump_json(), upcoming))
            Store.event(db, "SCHEDULE_SAVED", component="scheduler")
        return {"spec": body.model_dump(), "next": upcoming}

    def completed_digests():
        with store.db() as db:
            rows = db.execute("SELECT id,result FROM jobs WHERE kind='run' AND result IS NOT NULL")
            results = {row["id"]: json.loads(row["result"]) for row in rows}
        return {run: result.get("ready_at") for run, result in results.items() if result.get("languages")}

    def artifacts(run):
        if not ID.fullmatch(run) or (not run.startswith("run-") and run not in completed_digests()):
            fail(404, "RUN_UNKNOWN")
        path = (root / "runs" / run).resolve()
        if not path.is_relative_to((root / "runs").resolve()) or not path.is_dir():
            fail(404, "RUN_UNKNOWN")
        return path

    def digest_info(path, completed):
        summaries = {lang: path / f"summary-{lang}.md" for lang in DIGEST_LANGUAGES}
        languages = [lang for lang, file in summaries.items() if file.is_file()]
        if not languages:
            return None
        try:
            metadata = json.loads((path / "meta.json").read_text(encoding="utf-8"))
            created = metadata["created_at"]
            ready = completed.get(path.name) or datetime.fromtimestamp(
                min(summaries[lang].stat().st_mtime for lang in languages), timezone.utc).isoformat()
            return {"id": path.name, "created": created, "ready_at": ready, "languages": languages}
        except (OSError, ValueError, KeyError):
            return None

    @app.get("/v1/digests")
    def digests(limit: int = Query(60, ge=1, le=100), after: str | None = Query(None, max_length=32)):
        directory = root / "runs"
        if not directory.is_dir():
            return []
        completed = completed_digests()
        result = []
        for path in directory.iterdir():
            if not path.is_dir() or not ID.fullmatch(path.name) or (not path.name.startswith("run-") and path.name not in completed):
                continue
            if info := digest_info(path, completed):
                result.append(info)
        result.sort(key=lambda row: (datetime.fromisoformat(row["ready_at"]), row["id"]), reverse=True)
        offset = next((index + 1 for index, row in enumerate(result) if row["id"] == after), 0)
        return result[offset:offset + limit]

    @app.get("/v1/digest-info/{run}")
    def get_digest_info(run: str):
        info = digest_info(artifacts(run), completed_digests())
        if info is None:
            fail(404, "DIGEST_UNKNOWN")
        return info

    @app.get("/v1/digest-marker")
    def digest_marker():
        with store.db() as db:
            row = db.execute("""SELECT id FROM jobs WHERE kind='run'
                AND json_array_length(result, '$.languages') > 0 ORDER BY rowid DESC LIMIT 1""").fetchone()
        return {"id": row["id"] if row else ""}

    @app.put("/v1/push/{device}")
    def put_push(device: UUID, body: PushDevice):
        endpoint = push.valid_endpoint(body.endpoint)
        if endpoint is None:
            fail(422, "PUSH_ENDPOINT")
        with store.db() as db:
            db.execute("BEGIN IMMEDIATE")
            if not db.execute("SELECT 1 FROM push_devices WHERE id=?", (device.hex,)).fetchone():
                if db.execute("SELECT count(*) FROM push_devices").fetchone()[0] >= 25:
                    fail(409, "PUSH_LIMIT")
            db.execute("""INSERT INTO push_devices VALUES (?,?,?) ON CONFLICT(id)
                       DO UPDATE SET endpoint=excluded.endpoint,secret=excluded.secret""",
                       (device.hex, endpoint, body.secret))
        return {"registered": True, **digest_marker()}

    @app.delete("/v1/push/{device}")
    def delete_push(device: UUID):
        with store.db() as db:
            db.execute("DELETE FROM push_devices WHERE id=?", (device.hex,))
            db.execute("DELETE FROM push_outbox WHERE device=?", (device.hex,))
        return {"registered": False}

    @app.get("/v1/digests/{run}/{language}")
    def digest(run: str, language: Literal["en", "zh", "ru"], offset: int = Query(0, ge=0), limit: int = Query(40, ge=1, le=80)):
        path = artifacts(run)
        try:
            with (path / f"summary-{language}.md").open(encoding="utf-8") as file:
                markdown = file.read(3_000_000) if offset == 0 else ""
            stage = next((path / name for name in ("localized_items.json", "enriched_items.json",
                         "filtered_items.json") if (path / name).is_file()), path / "filtered_items.json")
            items = json.loads(stage.read_text(encoding="utf-8"))
        except FileNotFoundError:
            fail(404, "DIGEST_UNKNOWN")
        cards = []
        has_localized_items = stage.name == "localized_items.json"
        for item in items[offset:offset + limit]:
            process = item.get("processing") or {}
            analysis = process.get("analysis") or {}
            artifact = (process.get("artifacts") or {}).get(language) or {}
            blocks = artifact.get("blocks", [])
            primary = next((block.get("content", "") for block in blocks if block.get("primary")), "")
            original = split_content(item.get("content")).main
            body = primary or original or analysis.get("summary", "")
            cards.append({
                "id": item["id"], "title": (artifact.get("title") or item["title"])[:600], "url": item["url"],
                "source": item["source_type"], "time": item["published_at"],
                "category": (item.get("metadata") or {}).get("category"),
                "profile": (process.get("classification") or {}).get("profile"),
                "score": analysis.get("score"), "summary": body[:8000],
                "body": body[:24000], "body_is_plain": not bool(primary),
                "reason": (analysis.get("reason") or "")[:8000] if not has_localized_items else "",
                "blocks": blocks[:30], "citations": artifact.get("sources", [])[:50],
            })
        return {"id": run, "language": language, "markdown": markdown, "items": cards, "total": len(items)}

    @app.post("/v1/backup")
    def export(body: Password):
        with store.db() as db:
            settings = store.settings(db)
            schedule = json.loads(db.execute("SELECT spec FROM schedule WHERE id=1").fetchone()[0])
            Store.event(db, "BACKUP_EXPORTED")
        return {"archive": backup.pack({"horizon": REVISION, "settings": settings, "schedule": schedule}, body.password)}

    @app.post("/v1/restore")
    def restore(body: ImportBackup):
        try:
            payload = backup.unpack(body.archive, body.password)
            if payload["horizon"] != REVISION:
                fail(422, "BACKUP_VERSION")
            updated = payload["settings"]
            for name, value in updated["secrets"].items():
                if not secret_name_allowed(name) or not isinstance(value, str) or not 1 <= len(value) <= 8192 or "\0" in value:
                    fail(422, "SECRET_VALUE_INVALID")
            restored_schedule = Schedule.model_validate(payload["schedule"])
            check_config(updated["config"], updated["profiles"], updated["secrets"])
        except (KeyError, ValueError, TypeError, AttributeError):
            fail(422, "BACKUP_INVALID")
        with store.db() as db:
            db.execute("BEGIN IMMEDIATE")
            no_active(db)
            current = store.settings(db)
            if current["revision"] != body.revision:
                fail(409, "CONFIG_CHANGED")
            store.put_settings(db, current, updated)
            db.execute("UPDATE schedule SET spec=?,next=? WHERE id=1",
                       (restored_schedule.model_dump_json(), next_run(restored_schedule, time.time())))
            Store.event(db, "BACKUP_RESTORED")
            return public(store.settings(db))

    return app


def main():
    import uvicorn
    os.umask(0o077)
    root = Path(os.environ["HM_STATE"]).absolute()
    token = (Path(os.environ["CREDENTIALS_DIRECTORY"]) / "api-token").read_text().strip()
    if not re.fullmatch(r"[a-f0-9]{64}", token):
        raise SystemExit("Invalid installation credential.")
    uvicorn.run(create_app(root, token), host="127.0.0.1", port=18777, access_log=False,
                server_header=False, proxy_headers=False, timeout_keep_alive=10)


if __name__ == "__main__":
    main()
