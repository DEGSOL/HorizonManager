"""Collapse duplicate publications before either digest enrichment mode."""

import asyncio
import unicodedata

from src.orchestrator import _deduplication_url_key


def score(item):
    analysis = item.processing.analysis if item.processing else None
    return analysis.score if analysis and analysis.score is not None else -1


def identity_keys(item):
    yield ("id", item.id)
    try:
        url = _deduplication_url_key(str(item.url))
        if url[0] in {"http", "https"} and url[3] and (url[-2] != "/" or url[-1]):
            yield ("url", *url[1:])
    except ValueError:
        pass
    title = " ".join(unicodedata.normalize("NFKC", item.title).casefold().split())
    if len(title) >= 24 and not title.endswith(("…", "...")):
        yield ("title", title.rstrip(".!? 。！？"))


def exact_duplicates(items):
    seen = set()
    kept = set()
    for index in sorted(range(len(items)), key=lambda i: -score(items[i])):
        keys = set(identity_keys(items[index]))
        if keys.isdisjoint(seen):
            kept.add(index)
        seen.update(keys)
    return [item for index, item in enumerate(items) if index in kept]


def profile_id(item):
    return item.processing.classification.profile


async def digest_duplicates(items, orchestrator, report):
    items = exact_duplicates(items)
    settings = orchestrator.config.processing.profile_settings
    eligible = sorted(
        (item for item in items if profile_id(item) not in settings or settings[profile_id(item)].topic_dedup),
        key=lambda item: item.published_at,
        reverse=True,
    )
    removed = set()
    # Overlap adjacent requests so events near a batch boundary can be compared.
    batch_size = 32
    for start in range(0, max(1, len(eligible) - batch_size // 2), batch_size // 2):
        batch = [item for item in eligible[start:start + batch_size] if id(item) not in removed]
        if len({profile_id(item) for item in batch}) < 2:
            continue
        batch.sort(key=score, reverse=True)
        try:
            async with asyncio.timeout(35):
                kept = {id(item) for item in await orchestrator.merge_topic_duplicates(batch, log=False)}
        except Exception:
            report("DEDUPE_PARTIAL")
            break
        removed.update(id(item) for item in batch if id(item) not in kept)
    return [item for item in items if id(item) not in removed]
