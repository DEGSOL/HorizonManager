"""Bridge the Android loopback callback to Horizon's OAuth client on the VPS."""

from dataclasses import dataclass, field
from hmac import compare_digest
from http.client import HTTPConnection
from pathlib import Path
from threading import Event, Lock, Thread
from urllib.parse import parse_qs, urlencode, urlsplit, urlunsplit
from uuid import uuid4

from src.ai.chatgpt_auth import ChatGPTAuth


def chatgpt_auth(root: Path):
    return ChatGPTAuth(root / "chatgpt")


def plan_available(root: Path, account=None):
    try:
        return bool(chatgpt_auth(root).status(account).get("plan_usage"))
    except (RuntimeError, OSError, ValueError):
        return False


@dataclass
class LoginAttempt:
    id: str = field(default_factory=lambda: uuid4().hex)
    available: Event = field(default_factory=Event)
    url: str = ""
    state: str = ""
    port: int = 0
    status: str = "waiting"
    problem: str = ""


class ChatGptLogin:
    def __init__(self, root):
        self.auth = chatgpt_auth(root)
        self.lock = Lock()
        self.attempt = None

    def start(self):
        with self.lock:
            if self.attempt and self.attempt.status in {"waiting", "checking"}:
                raise ValueError("CHATGPT_LOGIN_ACTIVE")
            attempt = self.attempt = LoginAttempt()
            Thread(target=self._sign_in, args=(attempt,), daemon=True).start()
        if not attempt.available.wait(5) or not attempt.url:
            raise ValueError(attempt.problem or "CHATGPT_LOGIN_FAILED")
        return {"id": attempt.id, "url": attempt.url}

    def _sign_in(self, attempt):
        def open_on_phone(url):
            parts = urlsplit(url)
            fields = parse_qs(parts.query)
            attempt.state = fields["state"][0]
            attempt.port = urlsplit(fields["redirect_uri"][0]).port
            if "agent_name_hint" in fields:
                fields["agent_name_hint"] = ["Hovq"]
            attempt.url = urlunsplit(parts._replace(query=urlencode(fields, doseq=True)))
            attempt.available.set()
            return True

        try:
            result = self.auth.sign_in(enable_plan=True, timeout=180, open_browser=open_on_phone)
            status = "connected" if result.get("plan_usage") else "failed"
            problem = "" if status == "connected" else "CHATGPT_PLAN_REQUIRED"
        except Exception:
            status, problem = "failed", "CHATGPT_LOGIN_FAILED"
        with self.lock:
            if attempt.status == "cancelled":
                return
            attempt.status, attempt.problem = status, problem
            attempt.available.set()

    def _find(self, attempt_id):
        if self.attempt is None or self.attempt.id != attempt_id:
            raise ValueError("CHATGPT_LOGIN_EXPIRED")
        return self.attempt

    def status(self, attempt_id):
        with self.lock:
            attempt = self._find(attempt_id)
            return {"state": attempt.status, "code": attempt.problem}

    def finish(self, attempt_id, fields):
        with self.lock:
            attempt = self._find(attempt_id)
            if attempt.status == "waiting":
                self._relay(attempt, fields)
                attempt.status = "checking" if "code" in fields else "cancelled"
            return {"state": attempt.status, "code": attempt.problem}

    def cancel(self, attempt_id):
        with self.lock:
            attempt = self._find(attempt_id)
            if attempt.status == "waiting" and attempt.port:
                self._relay(attempt, {"state": attempt.state, "error": "access_denied"})
                attempt.status = "cancelled"
            return {"state": attempt.status}

    @staticmethod
    def _relay(attempt, fields):
        if not attempt.port or not compare_digest(attempt.state.encode(), fields["state"].encode()):
            raise ValueError("CHATGPT_CALLBACK_INVALID")
        conn = HTTPConnection("127.0.0.1", attempt.port, timeout=3)
        try:
            conn.request("GET", "/auth/callback?" + urlencode(fields))
            if conn.getresponse().status != 200:
                raise ValueError("CHATGPT_CALLBACK_INVALID")
        except OSError:
            raise ValueError("CHATGPT_LOGIN_EXPIRED") from None
        finally:
            conn.close()
