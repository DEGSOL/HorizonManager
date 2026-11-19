"""Load model IDs without returning stored credentials to Android."""
import json

import httpx

ROUTERS = {
    "openrouter": ("https://openrouter.ai/api/v1", "/models/user", "HM_SECRET_OPENROUTER_KEY"),
    "unorouter": ("https://api.unorouter.com/v1", "/models", "HM_SECRET_UNOROUTER_KEY"),
}
MAX_CATALOG = 4 * 1024 * 1024


class CatalogError(Exception):
    def __init__(self, code, status=None):
        self.code = code
        self.status = status


def saved_key(router, ai, secrets):
    base_url, _, default_secret = ROUTERS[router]
    if ai.get("provider") == "openai" and str(ai.get("base_url") or "").rstrip("/") == base_url:
        alias = ai.get("api_key_env") or default_secret
        return secrets.get(alias) or secrets.get(default_secret)
    return secrets.get(default_secret)


async def fetch(router, key):
    if not 8 <= len(key) <= 512 or any(not 0x21 <= ord(char) <= 0x7e for char in key):
        raise CatalogError("ROUTER_KEY_FORMAT")
    base_url, path, _ = ROUTERS[router]
    try:
        async with httpx.AsyncClient(timeout=httpx.Timeout(12), follow_redirects=False, trust_env=False) as client:
            async with client.stream("GET", base_url + path, headers={
                "Authorization": f"Bearer {key}", "Accept": "application/json",
            }) as response:
                if response.status_code in {401, 403}:
                    raise CatalogError("ROUTER_KEY_REJECTED")
                if response.status_code == 429:
                    raise CatalogError("ROUTER_RATE_LIMIT")
                if not response.is_success:
                    raise CatalogError("ROUTER_CATALOG_HTTP", response.status_code)
                body = bytearray()
                async for chunk in response.aiter_bytes():
                    if len(body) + len(chunk) > MAX_CATALOG:
                        raise CatalogError("ROUTER_CATALOG_FORMAT")
                    body.extend(chunk)
        models = json.loads(body)["data"]
        if not isinstance(models, list):
            raise CatalogError("ROUTER_CATALOG_FORMAT")
    except httpx.HTTPError as exc:
        raise CatalogError("ROUTER_CATALOG_NETWORK") from exc
    except (KeyError, UnicodeError, ValueError, TypeError) as exc:
        raise CatalogError("ROUTER_CATALOG_FORMAT") from exc

    data = []
    for model in models:
        if not isinstance(model, dict):
            continue
        model_id = model.get("id")
        if not isinstance(model_id, str) or not 0 < len(model_id) <= 256 or key in model_id:
            continue
        name = model.get("name")
        architecture = model.get("architecture")
        modes = architecture.get("output_modalities") if isinstance(architecture, dict) else None
        if isinstance(modes, list) and modes and "text" not in modes:
            continue
        length = model.get("context_length")
        data.append({
            "id": model_id, "name": name[:200] if isinstance(name, str) and key not in name else model_id,
            "context_length": length if isinstance(length, int) and not isinstance(length, bool) else None,
        })
    return {"data": data}
