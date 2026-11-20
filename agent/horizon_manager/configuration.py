import copy
import re
from pathlib import Path
from tempfile import TemporaryDirectory
from urllib.parse import urlsplit

from pydantic import BaseModel, ConfigDict, Field
from src.collection_window import collection_hours
from src.mcp.horizon_adapter import get_enabled_sources
from src.models import AI_PROVIDER_DEFAULTS, Config, SOURCE_REGISTRY
from src.processing.profiles import BUILTIN_PROFILES_DIR, ProfileDefinition, ProfileRegistry

SECRET_NAMES = frozenset({
    "OPENAI_API_KEY", "ANTHROPIC_API_KEY", "GOOGLE_API_KEY", "AZURE_OPENAI_API_KEY",
    "AZURE_OPENAI_ENDPOINT", "DASHSCOPE_API_KEY", "DOUBAO_API_KEY", "MINIMAX_API_KEY",
    "DEEPSEEK_API_KEY", "GITHUB_TOKEN", "APIFY_TOKEN", "HORIZON_WEBHOOK_URL",
    "EMAIL_PASSWORD", "FIRECRAWL_API_KEY", "OUTCRAWL_API_KEY", "SPECSTREAM_API_KEY",
})
SECRET_ALIAS = re.compile(r"HM_SECRET_[A-Z0-9_]{1,48}")
ENV_REFERENCE = re.compile(r"\$\{([A-Z_][A-Z0-9_]*)\}")
DIGEST_LANGUAGES = ("en", "zh", "ru")
LANGUAGES = frozenset(DIGEST_LANGUAGES)


def secret_name_allowed(name):
    return name in SECRET_NAMES or SECRET_ALIAS.fullmatch(name) is not None


class ProfileDocument(BaseModel):
    model_config = ConfigDict(extra="forbid")
    definition: ProfileDefinition
    match_prompt: str = Field(min_length=1, max_length=100_000)
    analysis_prompt: str = Field(min_length=1, max_length=100_000)
    enrichment_prompt: str = Field(min_length=1, max_length=100_000)


def builtin_profiles():
    registry = ProfileRegistry.load(BUILTIN_PROFILES_DIR, "tech-news")
    return [
        ProfileDocument(
            definition=p.definition, match_prompt=p.match_prompt,
            analysis_prompt=p.analysis_prompt, enrichment_prompt=p.enrichment_prompt,
        ).model_dump(mode="json")
        for p in registry.profiles
    ]


def initial_config():
    return Config.model_validate({
        "ai": {"provider": "openai", "model": "", "api_key_env": "OPENAI_API_KEY", "languages": list(DIGEST_LANGUAGES)},
        "sources": {
            "hackernews": {"enabled": False}, "reddit": {"enabled": False},
            "telegram": {"enabled": False},
        },
    }).model_dump(mode="json")


def write_profiles(target, documents):
    entries = [ProfileDocument.model_validate(document) for document in documents]
    ids = {entry.definition.id for entry in entries}
    if len(ids) != len(entries):
        raise ValueError("PROFILE_DUPLICATE_ID")
    target.mkdir(parents=True, exist_ok=True)
    for entry in entries:
        definition = entry.definition.model_copy(deep=True)
        folder = target / definition.id
        folder.mkdir()
        for filename, value in (
            ("match.md", entry.match_prompt), ("analysis.md", entry.analysis_prompt),
            ("enrichment.md", entry.enrichment_prompt),
        ):
            (folder / filename).write_text(value, encoding="utf-8")
        definition.match = "match.md"
        definition.analysis = "analysis.md"
        definition.enrichment.prompt = "enrichment.md"
        (folder / "profile.json").write_text(definition.model_dump_json(indent=2), encoding="utf-8")


def validate(config, documents, secrets, ready=False, openbb=False):
    if not 1 <= len(documents) <= 32:
        raise ValueError("PROFILE_COUNT")
    # Resolve only registered secret variables. Avoid including submitted values in errors.
    import json
    for name in ENV_REFERENCE.findall(json.dumps(config, ensure_ascii=False)):
        if not secret_name_allowed(name) or name not in secrets:
            raise ValueError("SECRET_REFERENCE")
    def expand(value):
        if isinstance(value, str):
            return ENV_REFERENCE.sub(lambda match: secrets[match[1]], value)
        if isinstance(value, dict):
            return {k: expand(v) for k, v in value.items()}
        if isinstance(value, list):
            return [expand(v) for v in value]
        return value
    model = Config.model_validate(expand(config))
    collection_hours(model.collection.time_window_hours)
    if model.collection.time_window_hours > 8760:
        raise ValueError("COLLECTION_LIMIT")
    if not model.ai.languages or set(model.ai.languages) - LANGUAGES:
        raise ValueError("LANGUAGE_UNVERIFIED")
    if model.ai.analysis_concurrency not in range(1, 17) or model.ai.enrichment_concurrency not in range(1, 17):
        raise ValueError("AI_CONCURRENCY")
    if not 0 <= model.ai.throttle_sec <= 300 or not 1 <= model.ai.max_tokens <= 200000:
        raise ValueError("AI_LIMIT")
    if model.processing.profiles_dir != "profiles":
        raise ValueError("PROFILE_PATH")
    enabled = get_enabled_sources(model)
    twitter = model.sources.twitter
    if twitter and twitter.enabled and twitter.mode != "apify":
        raise ValueError("TWITTER_MODE_UNAVAILABLE")
    if "openbb" in enabled and not openbb:
        raise ValueError("OPENBB_DEPENDENCY")
    if model.wechat and model.wechat.enabled:
        raise ValueError("WECHAT_AUTH_UNAVAILABLE")
    if model.ai.provider.value == "openai-chatgpt" or "openai-chatgpt" in (model.ai.provider_chain or "").split(","):
        raise ValueError("CHATGPT_AUTH_UNAVAILABLE")
    if model.ai.provider_chain:
        from src.models import AIProvider
        names = [x.strip() for x in model.ai.provider_chain.split(",")]
        if not all(x in {provider.value for provider in AIProvider} - {"openai-chatgpt"} for x in names):
            raise ValueError("AI_CHAIN")
    for url in (model.ai.base_url,):
        if url:
            parts = urlsplit(url)
            local = parts.hostname in {"localhost", "127.0.0.1", "::1"}
            if (parts.scheme != "https" and not (local and parts.scheme == "http")) or not parts.hostname or parts.username or parts.password:
                raise ValueError("AI_BASE_URL")
    required = []
    if model.ai.provider.value != "ollama":
        required.append(model.ai.api_key_env)
    if model.ai.provider.value == "azure":
        required.append(model.ai.azure_endpoint_env)
    if model.email and model.email.enabled:
        required.append(model.email.password_env)
    if model.webhook and model.webhook.enabled:
        required.append(model.webhook.url_env)
    if twitter and twitter.enabled:
        required.append(twitter.apify_token_env)
    if any(name is None or not secret_name_allowed(name) for name in required):
        raise ValueError("SECRET_NAME")
    for extractor in model.extractors.values():
        if hasattr(extractor, "api_key_env") and not secret_name_allowed(extractor.api_key_env):
            raise ValueError("SECRET_NAME")
    if ready and (not model.ai.model.strip() or any(not secrets.get(name) for name in required)):
        raise ValueError("AI_OR_DELIVERY_SETUP_REQUIRED")
    with TemporaryDirectory() as scratch:
        path = Path(scratch) / "profiles"
        write_profiles(path, documents)
        ids = {document["definition"]["id"] for document in documents}
        if model.processing.default_profile not in ids:
            raise ValueError("PROFILE_DEFAULT_UNKNOWN")
        registry = ProfileRegistry.load(path, model.processing.default_profile)
        try:
            registry.validate_source_references(model.sources.model_dump(mode="json"))
        except ValueError:
            raise ValueError("PROFILE_SOURCE_INVALID") from None
        try:
            registry.validate_digest_references(model.digest)
        except ValueError:
            raise ValueError("PROFILE_DIGEST_INVALID") from None
        if (set(model.processing.profile_settings) | set(model.digest.profile_order)) - registry.ids:
            raise ValueError("PROFILE_ORDER_INVALID")
        for entry in registry.profiles:
            if any(tool not in {"history_search", "web_search"} for block in entry.definition.enrichment.blocks for tool in block.tools):
                raise ValueError("PROFILE_TOOL")
    return model, enabled, sorted(set(required) - secrets.keys())


def ai_missing(ai, secrets):
    fields = []
    if not ai.model.strip():
        fields.append("ai.model")
    if ai.provider.value != "ollama" and not secrets.get(ai.api_key_env):
        fields.append(ai.api_key_env)
    if ai.provider.value == "azure" and not secrets.get(ai.azure_endpoint_env):
        fields.append(ai.azure_endpoint_env)
    return fields


def schema():
    source_schema = Config.model_json_schema()
    profile_schema = ProfileDocument.model_json_schema()
    return {
        "config": source_schema,
        "profile": profile_schema,
        "sources": list(SOURCE_REGISTRY),
        "providers": {name.value: value for name, value in AI_PROVIDER_DEFAULTS.items()},
        "languages": sorted(LANGUAGES),
        "secrets": sorted(SECRET_NAMES),
    }


def only_source(raw, kind, bucket=None, index=None):
    sources = raw["sources"]
    definition = SOURCE_REGISTRY[kind]
    selected = copy.deepcopy(sources.get(kind))
    if selected is None:
        raise ValueError("SOURCE_MISSING")
    if definition.config_is_list:
        if bucket is not None or index is None or not 0 <= index < len(selected):
            raise ValueError("SOURCE_INDEX")
        selected = [selected[index]]
        selected[0]["enabled"] = True
    else:
        selected["enabled"] = True
        if bucket is not None:
            if bucket not in definition.item_fields or index is None or not 0 <= index < len(selected.get(bucket, [])):
                raise ValueError("SOURCE_INDEX")
            for name in definition.item_fields:
                if name in selected:
                    selected[name] = [selected[name][index]] if name == bucket else []
            item = selected[bucket][0]
            if isinstance(item, dict):
                item["enabled"] = True
    return {**raw, "sources": {**sources, kind: selected}}
