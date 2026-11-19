"""Read saved news and expose a digest-independent result for one article."""
import hashlib
import json
from pathlib import Path


def key_for(item_id):
    return hashlib.sha256(item_id.encode("utf-8")).hexdigest()


def read_json(path: Path, fallback):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError:
        return fallback


def find_item(path, key, stages):
    for stage in stages:
        items = read_json(path / f"{stage}_items.json", [])
        for item in items:
            if key_for(item["id"]) == key:
                return item
    return None


def context_artifacts(item):
    artifacts = (item.get("processing") or {}).get("artifacts") or {}
    result = {}
    for language, artifact in artifacts.items():
        blocks = [{"title": block.get("title", "")[:300], "content": block["content"][:12000]}
                  for block in artifact.get("blocks", [])[:20] if block.get("content", "").strip()]
        if blocks:
            result[language] = {
                "blocks": blocks,
                "sources": [{"title": source["title"][:300], "url": source["url"][:2048]}
                            for source in artifact.get("sources", [])[:30]],
            }
    return result


def previous_context(path, key):
    item = find_item(path, key, ("enriched",))
    metadata = read_json(path / "meta.json", {})
    if item is not None and item["id"] not in (metadata.get("enrichment_failed_ids") or []):
        return context_artifacts(item)
    if (path / "enrichment_profiles.json").is_file():
        return {}
    # Some old exports only retain localized items. Recognize retained enrichment
    # from the original stage metadata or from explicit secondary blocks/sources.
    item = find_item(path, key, ("localized",))
    if item is None or item["id"] in (metadata.get("enrichment_failed_ids") or []):
        return {}
    artifacts = (item.get("processing") or {}).get("artifacts") or {}
    if (metadata.get("enrichment_status") in {"success", "partial_failure"}
            or any(artifact.get("sources") or any(not block.get("primary") for block in artifact.get("blocks", []))
                   for artifact in artifacts.values())):
        return context_artifacts(item)
    return {}


def localized_context(artifacts, language):
    for candidate in dict.fromkeys((language, "en", "ru", "zh", *artifacts)):
        if candidate in artifacts:
            return {"state": "ready", "language": candidate, **artifacts[candidate]}
    return None
