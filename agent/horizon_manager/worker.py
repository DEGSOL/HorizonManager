"""Run one fixed operation using the pinned upstream environment."""
import asyncio
import json
import logging
import os
import re
import sys
from datetime import datetime, timezone
from pathlib import Path
from tempfile import NamedTemporaryFile, TemporaryDirectory
from urllib.parse import quote, quote_plus

from rich.console import Console
from src.ai.client import create_ai_client
from src.mcp.horizon_adapter import dicts_to_items, items_to_dicts
from src.mcp.service import HorizonPipelineService
from src.models import Config

from .article_context import context_artifacts, find_item, read_json
from .classification import score_with_retry
from .configuration import DEFAULT_HEADLINE_RANGE, only_source, write_profiles
from .digest_duplicates import digest_duplicates
from .localized_digest import LANGUAGE_NAMES, atomic_text, complete_artifacts, save_localized

STATE = Path(os.environ["HM_STATE"])
WIRE = sys.stdout
# Horizon and third-party stdout are deliberately excluded from event exports.
sys.stdout = sys.stderr

MESSAGES = {
    "fetch": "Получение источников и первичная дедупликация",
    "score": "Классификация и AI-оценка",
    "filter": "Фильтрация, квоты и тематическая дедупликация",
    "enrich": "Дополнение контекстом и аналитикой",
    "generate": "Генерация Markdown",
    "save": "Сохранение дайджеста",
    "delivery": "Доставка дайджеста",
}


def send(kind, **values):
    WIRE.write(json.dumps({"kind": kind, **values}, ensure_ascii=False) + "\n")
    WIRE.flush()


def phase(name):
    send("phase", phase=name, detail=MESSAGES[name])


def category(message):
    text = str(message).lower()
    for reason, patterns in (
        ("AUTH", ("401", "403", "unauthoriz", "forbidden", "api key", "authentication")),
        ("RATE_LIMIT", ("429", "rate limit", "too many requests", "quota")),
        ("HTTP_NOT_FOUND", ("404", "not found")),
        ("TIMEOUT", ("timeout", "timed out")),
        ("INVALID_FORMAT", ("parse", "xml", "format", "malformed")),
        ("NETWORK", ("connect", "dns", "network", "ssl", "tls", "resolve")),
    ):
        if any(value in text for value in patterns):
            return reason
    return "UPSTREAM_FAILURE"


class HorizonLogs(logging.Handler):
    def emit(self, record):
        if not record.name.startswith("src.") or record.levelno < logging.WARNING:
            return
        # Raw SDK messages may include user URLs, prompts and credentials.
        message = record.getMessage()
        code = category(message)
        if record.name == "src.ai.classifier" and message.startswith("Could not classify"):
            if code == "UPSTREAM_FAILURE":
                code = ("INVALID_FORMAT" if any(marker in message.lower() for marker in
                        ("json", "classifier response", "classifier did not return", "outside the allowed catalog"))
                        else "CLASSIFIER_FALLBACK")
            detail = "src.ai.classifier · fallback"
        else:
            detail = record.name
        send("log", level=record.levelname, code=code, detail=detail)


def redact_source(value, secrets):
    text = str(value)
    for secret in secrets:
        for variant in {secret, quote(secret, safe=""), quote_plus(secret)}:
            if variant:
                text = text.replace(variant, "[скрыто]")
    text = re.sub(r"https?://\S+", "[URL]", text)
    return "".join(c for c in text if c.isprintable())[:160]


def fetch_report(result, secrets):
    for source in (result.get("fetch_report") or {}).get("sources", []):
        status = source["status"]
        send("source", source=redact_source(source["source"], secrets), status=status,
             count=source.get("item_count", 0), reason=category(source.get("error")) if source.get("error") else "")
        for name, reason in source.get("failures", {}).items():
            send("source", source=redact_source(name, secrets), status="failure", count=0, reason=category(reason))


async def enrich_article(work, data, settings, params, language):
    runs = STATE / "runs"
    path = runs / params["run"]
    raw = find_item(path, params["article"], ("filtered", "enriched", "localized"))
    if raw is None:
        raise ValueError("ARTICLE_UNKNOWN")
    item = dicts_to_items([raw])[0]
    profile_id = item.processing.classification.profile if item.processing and item.processing.classification else None
    snapshot = read_json(path / "enrichment_profiles.json", {})
    profile = snapshot.get(profile_id) or next(
        (value for value in settings["profiles"] if value["definition"]["id"] == profile_id), None)
    if profile is None:
        raise ValueError("ARTICLE_PROFILE_UNAVAILABLE")

    # The main worker owns data/config.json. Give this task its own config
    # while retaining Horizon's shared read-only access to digest history.
    with TemporaryDirectory(prefix="article-profiles-", dir=STATE) as profiles, NamedTemporaryFile(
            mode="w", encoding="utf-8", prefix="article-", suffix=".json", dir=data) as cfg:
        write_profiles(Path(profiles), [profile])
        config = settings["config"]
        effective = {**config,
                     "ai": {**config["ai"], "languages": [language]},
                     "processing": {"profiles_dir": profiles, "default_profile": profile_id}}
        # Preserve the account selected by the original Horizon pipeline,
        # without writing back to the immutable run's metadata.
        if effective["ai"].get("provider") == "openai-chatgpt":
            meta = read_json(path / "meta.json", {})
            if meta.get("chatgpt_account"):
                effective["ai"]["chatgpt_account"] = meta["chatgpt_account"]
        json.dump(effective, cfg, ensure_ascii=False)
        cfg.flush()
        service = HorizonPipelineService(runs, Console(file=sys.stderr, quiet=True))
        context, _, _ = service._build_context(str(work), cfg.name, sources=None)
        phase("enrich")
        outcome = await service._orchestrator(context).enrich_items([item])
        if outcome.status != "success":
            raise ValueError(category(outcome.failures.get(item.id, "")))
        artifacts = context_artifacts(items_to_dicts([item])[0])
        if not artifacts:
            raise ValueError("ARTICLE_CONTEXT_EMPTY")
        send("result", data={"context": artifacts})


async def main(payload, job):
    settings, params, kind = payload["settings"], payload["params"], payload["kind"]
    config = settings["config"]
    language = params.get("language") or payload["language"]
    if kind == "ai":
        model = Config.model_validate(config)
        response = await asyncio.wait_for(
            create_ai_client(model.ai.model_copy(update={"provider_chain": None})).complete("Return OK as plain text.", "Confirm that the API responds.", max_tokens=512),
            timeout=75,
        )
        if not response.strip():
            raise ValueError("EMPTY_AI_RESPONSE")
        send("result", data={"model": model.ai.model, "verified": True})
        return

    # Persistent Horizon history; one job at a time writes config and daily summaries.
    work = STATE / "horizon"
    data = work / "data"
    data.mkdir(parents=True, exist_ok=True)
    if kind == "article_context":
        await enrich_article(work, data, settings, params, language)
        return
    cfg = data / "config.json"
    if kind == "source":
        config = only_source(config, params["source"], params.get("bucket"), params.get("index"))
    headline_range = payload.get("headline_range", DEFAULT_HEADLINE_RANGE)
    if kind in {"run", "localize"}:
        config = {**config, "ai": {**config["ai"], "languages": [language]}}
    cfg.write_text(json.dumps(config, ensure_ascii=False), encoding="utf-8")
    with TemporaryDirectory(prefix="profiles-", dir=STATE) as folder:
        profiles = Path(folder) / "profiles"
        summary_settings = write_profiles(profiles, settings["profiles"])
        effective = json.loads(cfg.read_text(encoding="utf-8"))
        effective["processing"]["profiles_dir"] = str(profiles)
        try:
            cfg.write_text(json.dumps(effective, ensure_ascii=False), encoding="utf-8")
            store_dir = STATE / ("source-tests" if kind == "source" else "runs")
            service = HorizonPipelineService(store_dir, Console(file=sys.stderr, quiet=True))
            common = {"horizon_path": str(work), "config_path": str(cfg)}
            checked = await service.validate_config(**common, check_env=(kind == "run"))
            if kind == "run" and (checked["missing_env"] or not checked["enabled_sources"]):
                raise ValueError("RUN_SETUP_REQUIRED")
            if kind == "localize":
                phase("generate")
                run = params["run"]
                path = store_dir / run
                meta = service.run_store.load_meta(run)
                stage = "enriched" if (path / "enriched_items.json").is_file() else "filtered"
                items = dicts_to_items(service.run_store.load_items(run, stage))
                if (path / "localized_items.json").is_file():
                    items = dicts_to_items(json.loads((path / "localized_items.json").read_text(encoding="utf-8")))
                missing = not (path / f"summary-{language}.md").is_file()
                if missing:
                    context, _, _ = service._build_context(**common, sources=None)
                    if await complete_artifacts(items, language, context.config.ai, headline_range=headline_range):
                        # Keep the existing legacy edition intact and let the user retry.
                        raise ValueError("LOCALIZATION_INVALID")
                    await save_localized(path, items, [language], checked_raw=meta.get("raw_count", len(items)),
                                         date=meta["created_at"][:10], config=context.config,
                                         names=service._profiles(context).names)
                send("result", data={"localized_run": run, "languages": [language] if missing else []})
                return
            phase("fetch")
            fetched = await service.fetch_items(
                **common, run_id=job, hours=checked["collection"]["time_window_hours"],
                sources=[params["source"]] if kind == "source" else None,
            )
            fetch_report(fetched, settings["secrets"])
            status = fetched.get("fetch_status")
            raw = fetched["meta"]["raw_count"]
            if status in {"failure", "not_attempted"}:
                raise ValueError("SOURCE_ALL_FAILED")
            if kind == "source" or raw == 0:
                send("result", data={"raw": raw, "fetch_status": status})
                return
            phase("score")
            scored = await score_with_retry(service, job, summary_settings=summary_settings,
                                   language=LANGUAGE_NAMES[language], **common)
            phase("filter")
            filtered = await service.filter_items(job, **common)
            context, _, _ = service._build_context(**common, sources=None)
            items = dicts_to_items(service.run_store.load_items(job, "filtered"))
            if len(items) > 1:
                service._bind_chatgpt_account(context, job)
            orchestrator = service._orchestrator(context)
            if len(items) > 1:
                items = await digest_duplicates(items, orchestrator, lambda code: send("log", level="WARNING", code=code))
            kept = len(items)
            removed = filtered["kept"] - kept
            if removed:
                service.run_store.save_items(job, "filtered", items_to_dicts(items))
                service.run_store.update_meta(job, {"filtered_count": kept})
                send("log", level="INFO", code="DIGEST_DUPLICATES_REMOVED", detail=str(removed))
            stage = "filtered"
            enriched_count = 0
            mode = payload.get("enrichment_mode", "on_demand")
            if kept and mode == "automatic":
                phase("enrich")
                enriched = await service.enrich_items(job, **common)
                if enriched["status"] == "failure":
                    send("log", level="WARNING", code="ENRICHMENT_FAILED", detail="Будут доступны результаты первичного анализа")
                else:
                    stage = "enriched"
                    enriched_count = enriched["enriched"]
            phase("generate")
            today = datetime.now(timezone.utc).date().isoformat()
            if stage == "enriched":
                items = dicts_to_items(service.run_store.load_items(job, stage))
            unavailable = await complete_artifacts(
                items, language, context.config.ai,
                headline_range=headline_range,
                report=lambda code, count: send(
                    "log", level="INFO" if code == "LOCALIZATION_RECOVERED" else "WARNING",
                    code=code, detail=str(count)),
            )
            for item in items:
                item.processing.artifacts = {language: item.processing.artifacts[language]}
            if unavailable:
                send("log", level="WARNING", code="LOCALIZATION_PARTIAL", detail=str(unavailable))
            run_path = store_dir / job
            assigned = {item.processing.classification.profile for item in items if item.processing.classification}
            atomic_text(run_path / "enrichment_profiles.json", json.dumps({
                profile["definition"]["id"]: {key: value for key, value in profile.items()
                                              if key not in {"summary_mode", "summary_instruction"}}
                for profile in settings["profiles"]
                if profile["definition"]["id"] in assigned
            }, ensure_ascii=False))
            summaries = await save_localized(
                run_path, items, [language], checked_raw=raw, date=today,
                config=context.config, names=service._profiles(context).names,
            )
            phase("save")
            orchestrator.storage.save_daily_summary(today, summaries[language], language=language)
            send("result", data={
                "raw": raw, "scored": scored["scored"], "kept": kept, "enriched": enriched_count,
                "languages": list(summaries), "output_language": language, "translation_unavailable": unavailable,
                "stage": stage, "fetch_status": status, "enrichment_mode": mode,
            })
            email = orchestrator.email_manager if context.config.email and context.config.email.enabled else None
            webhook = orchestrator.webhook_notifier
            if email or webhook:
                phase("delivery")
                from src.ai.summarizer import DailySummarizer
                summarizer = DailySummarizer(
                    profile_names=orchestrator.profiles.names, profile_order=context.config.digest.profile_order,
                )
                markdown = summaries[language]
                if email:
                    if context.config.email.imap_enabled:
                        await asyncio.to_thread(email.check_subscriptions, orchestrator.storage)
                    await asyncio.to_thread(email.send_daily_summary, markdown,
                                            f"Horizon Summary ({language.upper()}) - {today}",
                                            orchestrator.storage.load_subscribers())
                if webhook:
                    await webhook.send_daily_summary(markdown, items, raw, today, language, summarizer)
            from .media import prepare_images
            try:
                covers = await prepare_images(store_dir / job, items, context.config, payload.get("media_search", False))
                send("log", level="INFO", code="MEDIA_READY", detail=str(covers or 0))
            except Exception:
                send("log", level="WARNING", code="MEDIA_UNAVAILABLE", detail="")
        finally:
            cfg.write_text(json.dumps(settings["config"], ensure_ascii=False), encoding="utf-8")
            if kind == "source":
                import shutil
                shutil.rmtree(STATE / "source-tests" / job, ignore_errors=True)


if __name__ == "__main__":
    logging.basicConfig(level=logging.WARNING, handlers=[HorizonLogs()], force=True)
    try:
        asyncio.run(main(json.load(sys.stdin), sys.argv[1]))
    except Exception as exc:
        while isinstance(exc, ExceptionGroup):
            exc = exc.exceptions[0]
        from src.mcp.errors import HorizonMcpError
        if isinstance(exc, HorizonMcpError):
            code = exc.code
        elif type(exc) is ValueError and re.fullmatch(r"[A-Z_]+", str(exc)):
            code = str(exc)
        elif getattr(exc, "status_code", None) in {401, 403, 404, 429}:
            code = "HTTP_" + str(exc.status_code)
        elif type(exc).__name__ == "AIProviderUnavailableError" and "subscription_sharing_usage_limit_exceeded" in str(exc):
            code = "RATE_LIMIT"
        elif type(exc).__name__ == "AIProviderUnavailableError" and "subscription_sharing_user_not_eligible" in str(exc):
            code = "CHATGPT_PLAN_REQUIRED"
        elif isinstance(exc, TimeoutError):
            code = "TIMEOUT"
        else:
            code = category(exc)
        send("log", level="ERROR", code=code, detail=type(exc).__name__)
        raise SystemExit(1)
