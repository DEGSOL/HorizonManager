"""Durable signed wake-ups via ntfy's UnifiedPush endpoints."""
import asyncio
import hashlib
import hmac
import os
import re
import time
from urllib.parse import urlsplit

import httpx

from .store import Store


def valid_endpoint(value):
    try:
        url = urlsplit(value)
    except ValueError:
        return None
    # Administrators may pin their own ntfy domain in the VPS service environment.
    host = os.environ.get("HM_PUSH_HOST", "ntfy.sh")
    if (url.scheme != "https" or url.netloc != host or url.fragment
            or url.query not in {"", "up=1", "up=true", "unifiedpush=1"}
            or not re.fullmatch(r"/up[A-Za-z0-9_-]{8,62}", url.path)):
        return None
    return f"https://{host}{url.path}"


def enqueue_digest(db, run):
    now = int(time.time())
    db.execute("""INSERT OR IGNORE INTO push_outbox(run,device,created,next)
                  SELECT ?,id,?,? FROM push_devices""", (run, now, now))


def signed_message(run, created, secret):
    body = f"{run}.{created}"
    signature = hmac.new(bytes.fromhex(secret), body.encode("ascii"), hashlib.sha256).hexdigest()
    return f"{body}.{signature}"


async def loop(store):
    async with httpx.AsyncClient(timeout=8, follow_redirects=False, trust_env=False) as client:
        while True:
            now = int(time.time())
            with store.db() as db:
                db.execute("DELETE FROM push_outbox WHERE created < ?", (now - 86400,))
                rows = db.execute("""SELECT o.*,d.endpoint,d.secret FROM push_outbox o
                    JOIN push_devices d ON o.device=d.id WHERE o.next<=? ORDER BY o.next LIMIT 10""",
                                  (now,)).fetchall()
            for row in rows:
                endpoint = valid_endpoint(row["endpoint"])
                status = 0
                if endpoint:
                    try:
                        response = await client.post(endpoint, content=signed_message(
                            row["run"], row["created"], row["secret"]),
                            headers={"X-UnifiedPush": "1", "TTL": "86400"})
                        status = response.status_code
                    except httpx.HTTPError:
                        pass
                with store.db() as db:
                    key = row["run"], row["device"]
                    if 200 <= status < 300 or not endpoint or row["attempts"] >= 6:
                        db.execute("DELETE FROM push_outbox WHERE run=? AND device=?", key)
                        if not 200 <= status < 300:
                            Store.event(db, "PUSH_DELIVERY_FAILED", "WARNING", "agent", row["run"],
                                        str(status) if status else "NETWORK")
                    else:
                        delay = 30 * 2 ** row["attempts"]
                        db.execute("UPDATE push_outbox SET attempts=attempts+1,next=? WHERE run=? AND device=?",
                                   (now + delay, *key))
            await asyncio.sleep(5)
