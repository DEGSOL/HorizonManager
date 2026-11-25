import asyncio
import base64
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
from pydantic import BaseModel, ConfigDict, Field, ValidationError, model_validator
from src.mcp.horizon_adapter import dicts_to_items
from src.models import SOURCE_REGISTRY
from src.processing.content import split_content

from . import VERSION, backup, jobs, push, router_catalog
from .article_context import find_item, key_for, localized_context, previous_context, read_json
from .configuration import (DIGEST_LANGUAGES, MAX_HEADLINE_CHARACTERS, MAX_HEADLINE_WORDS, MIN_HEADLINE_WORDS,
                            DigestLanguageCode, ai_missing, schema, secret_name_allowed, validate)
from .localized_digest import LocalizedSummarizer, restore_original_cards
from .media import relay_cover, web_url
from .scheduler import Schedule, next_run
from .store import Store, utc

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


class MediaSearch(Body):
    enabled: bool


class EnrichmentMode(Body):
    mode: Literal["on_demand", "automatic"]


class HeadlineLength(Body):
    words: int = Field(strict=True, ge=MIN_HEADLINE_WORDS, le=MAX_HEADLINE_WORDS)


class HeadlineRange(Body):
    minimum: int = Field(strict=True, ge=1, le=MAX_HEADLINE_CHARACTERS)
    maximum: int = Field(strict=True, ge=1, le=MAX_HEADLINE_CHARACTERS)

    @model_validator(mode="after")
    def ordered(self):
        if self.minimum > self.maximum:
            raise ValueError("minimum exceeds maximum")
        return self


def headline_range(db):
    minimum, maximum = Store.headline_range(db)
    return {"minimum": minimum, "maximum": maximum}


class DigestLanguage(Body):
    language: DigestLanguageCode
    initialize: bool = False


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
    cover_slots = asyncio.Semaphore(4)

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
        tasks = [asyncio.create_task(jobs.loop(store)), asyncio.create_task(jobs.loop(store, article=True)),
                 asyncio.create_task(push.loop(store))]
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
            language, chosen = store.digest_language(db)
            return {**health(), "digest_language": language, "digest_language_chosen": chosen,
                    "digest_languages": DIGEST_LANGUAGES, "headline_max_words": store.headline_words(db),
                    "headline_range": headline_range(db), "disk_free": disk.free, "disk_total": disk.total,
                    "media_search": store.media_search(db), "cover_relay": True, "article_source_links": True, "enrichment_mode": store.enrichment_mode(db),
                    "load": os.getloadavg()[0], "cpu": os.cpu_count(), "memory": mem, "next": next_at}

    @app.put("/v1/media-search")
    def set_media_search(body: MediaSearch):
        with store.db() as db:
            db.execute("INSERT OR REPLACE INTO media_options VALUES (1, ?)", (int(body.enabled),))
            Store.event(db, "MEDIA_SEARCH_CHANGED")
            return {"enabled": store.media_search(db)}

    @app.put("/v1/enrichment-mode")
    def set_enrichment_mode(body: EnrichmentMode):
        with store.db() as db:
            db.execute("BEGIN IMMEDIATE")
            Store.set_enrichment_mode(db, body.mode)
            Store.event(db, "ENRICHMENT_MODE_CHANGED", detail=body.mode)
            return {"mode": store.enrichment_mode(db)}

    @app.put("/v1/headline-length")
    def set_headline_length(body: HeadlineLength):
        with store.db() as db:
            Store.set_headline_words(db, body.words)
            db.execute("DELETE FROM headline_ranges WHERE id=1")
            Store.event(db, "HEADLINE_LENGTH_CHANGED")
            return {"words": store.headline_words(db)}

    @app.put("/v1/headline-range")
    def set_headline_range(body: HeadlineRange):
        with store.db() as db:
            Store.set_headline_range(db, body.minimum, body.maximum)
            Store.event(db, "HEADLINE_LENGTH_CHANGED")
            return headline_range(db)

    @app.put("/v1/digest-language")
    def set_digest_language(body: DigestLanguage):
        with store.db() as db:
            db.execute("BEGIN IMMEDIATE")
            language, chosen = store.digest_language(db)
            if not body.initialize or not chosen:
                language = body.language
                Store.set_digest_language(db, language)
                Store.event(db, "DIGEST_LANGUAGE_CHANGED", detail=language)
            return {"language": language}

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
        if db.execute(f"SELECT 1 FROM jobs WHERE {jobs.PIPELINE_ACTIVE}").fetchone():
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
            if completed_digests().get(body.run, {}).get("output_language"):
                fail(409, "DIGEST_LANGUAGE_FIXED")
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
                if db.execute(f"SELECT 1 FROM jobs WHERE {jobs.PIPELINE_ACTIVE}").fetchone():
                    fail(409, "ACTIVE_JOB")
                if body.kind in {"ai", "localize"}:
                    settings = store.settings(db)
                    model, _, _ = check_config(settings["config"], settings["profiles"], settings["secrets"])
                    if missing := ai_missing(model.ai, settings["secrets"]):
                        fail(422, "AI_SETUP_REQUIRED", fields=missing)
                Store.queue(db, job, body.kind, data)
        return {"id": job}

    @app.get("/v1/jobs")
    def list_jobs(limit: int = Query(50, ge=1, le=100), active: bool = False):
        where = f"WHERE {jobs.ACTIVE}" if active else ""
        with store.db() as db:
            rows = db.execute(f"""SELECT id,kind,state,created,updated,phase,
                      CASE WHEN kind != 'article_context' THEN result END AS result
                      FROM jobs {where} ORDER BY rowid DESC LIMIT ?""", (limit,))
            return [{**dict(row), "result": json.loads(row["result"]) if row["result"] else None} for row in rows]

    @app.post("/v1/jobs/{job}/stop")
    def stop(job: str):
        with store.db() as db:
            db.execute("BEGIN IMMEDIATE")
            row = db.execute("SELECT state FROM jobs WHERE id=?", (job,)).fetchone()
            if row is None:
                fail(404, "JOB_UNKNOWN")
            if row["state"] == "queued":
                db.execute("UPDATE jobs SET state='cancelled',phase='CANCELLED',updated=? WHERE id=?", (utc(), job))
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
        with store.db() as db:
            db.execute("BEGIN IMMEDIATE")
            row = db.execute("SELECT spec,next FROM schedule WHERE id=1").fetchone()
            current = Schedule.model_validate_json(row["spec"])
            if (body.mode == current.mode == "interval" and body.interval_start is not None
                    and (body.hours, body.interval_start, body.zone) ==
                    (current.hours, current.interval_start, current.zone) and row["next"] is not None):
                upcoming = row["next"]
            else:
                upcoming = next_run(body, time.time())
            db.execute("UPDATE schedule SET spec=?,next=? WHERE id=1", (body.model_dump_json(), upcoming))
            Store.event(db, "SCHEDULE_SAVED", component="scheduler")
        return {"spec": body.model_dump(), "next": upcoming}

    def completed_digests():
        with store.db() as db:
            rows = db.execute("SELECT id,result FROM jobs WHERE kind='run' AND result IS NOT NULL")
            results = {row["id"]: json.loads(row["result"]) for row in rows}
        return {run: result for run, result in results.items() if result.get("languages")}

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
            result = completed.get(path.name, {})
            ready = result.get("ready_at") or datetime.fromtimestamp(
                min(summaries[lang].stat().st_mtime for lang in languages), timezone.utc).isoformat()
            total = result.get("kept", metadata.get("filtered_count", metadata.get("kept_count")))
            return {"id": path.name, "created": created, "ready_at": ready, "languages": languages,
                    **({"total": total} if type(total) is int and total >= 0 else {}),
                    "media_ready": (path / "media.json").is_file(),
                    **({"output_language": result["output_language"]} if result.get("output_language") else {})}
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

    @app.get("/v1/media/{run}")
    def media_manifest(run: str):
        path = artifacts(run) / "media.json"
        if not path.is_file():
            fail(404, "MEDIA_UNAVAILABLE")
        return json.loads(path.read_text(encoding="utf-8"))

    @app.get("/v1/covers/{run}/{key}")
    async def cover(run: str, key: str):
        if not re.fullmatch(r"[a-f0-9]{64}", key):
            fail(404, "ARTICLE_UNKNOWN")
        path = artifacts(run)
        item = find_item(path, key, ("localized", "enriched", "filtered"))
        if item is None:
            fail(404, "ARTICLE_UNKNOWN")
        known = read_json(path / "media.json", {}).get(item["id"])
        cached = root / "cover-cache" / (key_for(f"{run}/{key}") + ".img")
        try:
            async with cover_slots:
                data = await relay_cover(item, known, cached)
        except TimeoutError:
            data = None
        if data is None:
            fail(404, "MEDIA_UNAVAILABLE")
        return {"image": base64.b64encode(data).decode("ascii")}

    def stored_context(db, run, key, language):
        row = db.execute("""SELECT jobs.id, state, phase, result
            FROM article_context_jobs JOIN jobs ON jobs.id=article_context_jobs.job
            WHERE run=? AND article=?""", (run, key)).fetchone()
        if row is None:
            return None
        if row["result"]:
            rendered = localized_context(json.loads(row["result"])["context"], language)
            if rendered:
                return rendered
        state = row["state"]
        if state in {"queued", "running"}:
            return {"state": state, "job": row["id"]}
        return {"state": "failed", "code": row["phase"]}

    def access_context(run, key, language, submit):
        if not ID.fullmatch(run) or not re.fullmatch(r"[a-f0-9]{64}", key):
            fail(404, "ARTICLE_UNKNOWN")
        with store.db() as db:
            saved = stored_context(db, run, key, language)
        if saved and (saved["state"] != "failed" or not submit):
            if not (root / "runs" / run / f"summary-{language}.md").is_file():
                fail(404, "DIGEST_UNKNOWN")
            return saved
        path = artifacts(run)
        if not (path / f"summary-{language}.md").is_file():
            fail(404, "DIGEST_UNKNOWN")
        previous = localized_context(previous_context(path, key), language)
        if previous:
            return previous
        if find_item(path, key, ("filtered", "enriched", "localized")) is None:
            fail(404, "ARTICLE_UNKNOWN")
        if not submit:
            return {"state": "idle"}
        with store.db() as db:
            db.execute("BEGIN IMMEDIATE")
            refreshed = stored_context(db, run, key, language)
            if refreshed and refreshed["state"] != "failed":
                return refreshed
            job = uuid4().hex
            Store.queue(db, job, "article_context", {"run": run, "article": key, "language": language})
            db.execute("INSERT OR REPLACE INTO article_context_jobs VALUES (?,?,?)", (run, key, job))
            return {"state": "queued", "job": job}

    @app.get("/v1/article-context/{run}/{key}/{language}")
    def get_article_context(run: str, key: str, language: DigestLanguageCode):
        return access_context(run, key, language, submit=False)

    @app.post("/v1/article-context/{run}/{key}/{language}")
    def request_article_context(run: str, key: str, language: DigestLanguageCode):
        return access_context(run, key, language, submit=True)

    @app.get("/v1/digests/{run}/{language}")
    def digest(run: str, language: DigestLanguageCode, offset: int = Query(0, ge=0), limit: int = Query(40, ge=1, le=80)):
        path = artifacts(run)
        try:
            with (path / f"summary-{language}.md").open(encoding="utf-8") as file:
                markdown = file.read(3_000_000) if offset == 0 else ""
            stage = next((path / name for name in ("localized_items.json", "enriched_items.json",
                         "filtered_items.json") if (path / name).is_file()), path / "filtered_items.json")
            items = json.loads(stage.read_text(encoding="utf-8"))
        except FileNotFoundError:
            fail(404, "DIGEST_UNKNOWN")
        repaired = restore_original_cards(items, language)
        if repaired and offset == 0:
            meta = read_json(path / "meta.json", {})
            profiles = read_json(path / "enrichment_profiles.json", {})
            names = {profile_id: entry["definition"].get("display_names", {})
                     for profile_id, entry in profiles.items()}
            with store.db() as db:
                order = store.settings(db)["config"].get("digest", {}).get("profile_order", [])
            summarizer = LocalizedSummarizer(profile_names=names, profile_order=order)
            markdown = asyncio.run(summarizer.generate_summary(
                dicts_to_items(items), meta.get("created_at", "")[:10], meta.get("raw_count", len(items)), language,
            ))
        media_file = path / "media.json"
        media = json.loads(media_file.read_text()) if media_file.is_file() else None
        cards = []
        has_localized_items = stage.name == "localized_items.json"
        for item in items[offset:offset + limit]:
            process = item.get("processing") or {}
            metadata = item.get("metadata") or {}
            analysis = process.get("analysis") or {}
            artifact = (process.get("artifacts") or {}).get(language) or {}
            blocks = artifact.get("blocks", [])
            primary = next((block.get("content", "") for block in blocks if block.get("primary")), "")
            original = split_content(item.get("content")).main
            body = primary or original or analysis.get("summary", "")
            publisher = next((name.strip() for name in (
                metadata.get("source_name"), metadata.get("feed_name"), metadata.get("subreddit"),
                metadata.get("repo"), metadata.get("domain"), item.get("author"),
            ) if isinstance(name, str) and name.strip()), "")
            source = item["source_type"]
            permalink = web_url(metadata.get("msg_url" if source == "telegram" else "discussion_url"))
            cards.append({
                "id": item["id"], "title": artifact.get("title") or item["title"], "url": item["url"],
                **({"media": media.get(item["id"], {})} if media is not None else {}),
                "source": source, "time": item["published_at"],
                **({"source_url": permalink} if permalink else {}),
                **({"publisher": publisher} if publisher else {}),
                "category": metadata.get("category"),
                **({"telegram_url": metadata["msg_url"]} if metadata.get("msg_url") else {}),
                **({"telegram_channel": metadata.get("channel") or item.get("author")}
                   if item["source_type"] == "telegram" else {}),
                "profile": (process.get("classification") or {}).get("profile"),
                "score": analysis.get("score"), "summary": body[:8000],
                "body": body, "body_is_plain": not bool(primary) or (
                    len(blocks) == 1 and blocks[0].get("id") == "article" and blocks[0].get("title") == ""
                ),
                "reason": (analysis.get("reason") or "")[:8000] if not has_localized_items else "",
                "blocks": blocks[:30], "citations": artifact.get("sources", [])[:50],
            })
        return {"id": run, "language": language, "markdown": markdown, "items": cards, "total": len(items),
                "content_version": 3}

    @app.post("/v1/backup")
    def export(body: Password):
        with store.db() as db:
            settings = store.settings(db)
            schedule = json.loads(db.execute("SELECT spec FROM schedule WHERE id=1").fetchone()[0])
            media_search = store.media_search(db)
            enrichment_mode = store.enrichment_mode(db)
            language, chosen = store.digest_language(db)
            headline_words = store.headline_words(db)
            characters = headline_range(db)
            Store.event(db, "BACKUP_EXPORTED")
        return {"archive": backup.pack({"horizon": REVISION, "settings": settings, "schedule": schedule,
                                        "media_search": media_search, "enrichment_mode": enrichment_mode,
                                        "digest_language": language, "digest_language_chosen": chosen,
                                        "headline_max_words": headline_words, "headline_range": characters}, body.password)}

    @app.post("/v1/restore")
    def restore(body: ImportBackup):
        try:
            payload = backup.unpack(body.archive, body.password)
            if payload["horizon"] != REVISION:
                fail(422, "BACKUP_VERSION")
            updated = payload["settings"]
            headline_words = HeadlineLength(words=payload["headline_max_words"]).words if "headline_max_words" in payload else None
            characters = HeadlineRange.model_validate(payload["headline_range"]) if "headline_range" in payload else None
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
            if isinstance(payload.get("media_search"), bool):
                db.execute("INSERT OR REPLACE INTO media_options VALUES (1, ?)", (int(payload["media_search"]),))
            mode = payload.get("enrichment_mode")
            if mode in ("on_demand", "automatic"):
                Store.set_enrichment_mode(db, mode)
            if payload.get("digest_language_chosen") is False:
                db.execute("DELETE FROM digest_options WHERE id=1")
            elif payload.get("digest_language") in DIGEST_LANGUAGES:
                Store.set_digest_language(db, payload["digest_language"])
            if headline_words is not None:
                Store.set_headline_words(db, headline_words)
                if characters is None:
                    db.execute("DELETE FROM headline_ranges WHERE id=1")
            if characters is not None:
                Store.set_headline_range(db, characters.minimum, characters.maximum)
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
