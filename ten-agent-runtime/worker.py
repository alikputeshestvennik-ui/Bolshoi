"""GitHub Actions worker. Input/output are encrypted with a key derived from GH_PAT."""
import asyncio
import base64
import hashlib
import json
import os
import ipaddress
import socket
import io
import urllib.request
import urllib.parse
import zipfile
from urllib.parse import urlsplit
from pathlib import Path
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from app import Request, execute_goal


def key(pat: str) -> bytes:
    if not pat.strip(): raise RuntimeError("Missing GitHub token")
    return hashlib.sha256(("ten-agent-v1:" + pat.strip()).encode()).digest()

def decrypt(encoded: str, secret: bytes) -> dict:
    data = base64.b64decode(encoded, validate=True)
    return json.loads(AESGCM(secret).decrypt(data[:12], data[12:], None))

def encrypt(result: dict, secret: bytes) -> str:
    nonce = os.urandom(12)
    payload = json.dumps(result, ensure_ascii=False).encode()
    return base64.b64encode(nonce + AESGCM(secret).encrypt(nonce, payload, None)).decode()

def previous_output(pat: str, run_id: int, secret: bytes) -> str | None:
    if run_id <= 0: return None
    root = "https://api.github.com/repos/alikputeshestvennik-ui/Bolshoi"
    class SafeRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, req, fp, code, msg, headers, newurl):
            redirected=super().redirect_request(req, fp, code, msg, headers, newurl)
            if redirected is None: return None
            if urllib.parse.urlsplit(newurl).scheme != "https": raise ValueError("Unsafe artifact redirect")
            if urllib.parse.urlsplit(newurl).netloc != urllib.parse.urlsplit(req.full_url).netloc:
                redirected.remove_header("Authorization")
            return redirected
    opener=urllib.request.build_opener(SafeRedirect())
    def get(url: str) -> bytes:
        req=urllib.request.Request(url, headers={"Authorization":"Bearer " + pat, "Accept":"application/vnd.github+json", "X-GitHub-Api-Version":"2022-11-28"})
        with opener.open(req, timeout=60) as response:
            data=response.read(40_000_001)
            if len(data)>40_000_000: raise ValueError("Stored workspace too large")
            return data
    artifacts=json.loads(get(f"{root}/actions/runs/{run_id}/artifacts"))["artifacts"]
    artifact=next((a for a in artifacts if a["name"].startswith("ten-result-") and not a["expired"]), None)
    if not artifact: raise ValueError("Saved workspace expired")
    with zipfile.ZipFile(io.BytesIO(get(f"{root}/actions/artifacts/{artifact['id']}/zip"))) as z:
        info=next((i for i in z.infolist() if i.filename.endswith("ten-result.enc")), None)
        if info is None or info.file_size>40_000_000: raise ValueError("Invalid saved workspace")
        previous=decrypt(z.read(info).decode("ascii"),secret)
    if not previous.get("ok"): raise ValueError("Previous task failed")
    return previous.get("archive")

async def main() -> None:
    pat=os.environ.pop("GH_PAT", "").strip()
    secret=key(pat)
    encrypted=os.environ.pop("TEN_PAYLOAD", "")
    try:
        task=decrypt(encrypted,secret)
        parsed=Request(goal=task["goal"], model=task["model"], base_url=task["base_url"])
        base=parsed.base_url.rstrip("/")
        u=urlsplit(base)
        if u.scheme != "https" or not u.hostname or u.username or u.password or u.query or u.fragment:
            raise ValueError("Для провайдера нужен публичный HTTPS-адрес")
        for entry in socket.getaddrinfo(u.hostname, u.port or 443, type=socket.SOCK_STREAM):
            if not ipaddress.ip_address(entry[4][0]).is_global:
                raise ValueError("Локальные адреса провайдера недоступны")
        previous=await asyncio.to_thread(previous_output, pat, int(task.get("previous_run_id",0)), secret)
        result=await asyncio.wait_for(execute_goal(parsed.goal, task["provider_token"], parsed.model, base, previous),timeout=1200)
        output={"ok":True,"result":result.result,"session_id":result.session_id,"archive":result.archive}
    except Exception as exc:
        # Never publish exception details: provider errors can contain credentials.
        from openai import APIStatusError, APIConnectionError
        if isinstance(exc, APIStatusError):
            reason=f"Провайдер отклонил запрос (HTTP {exc.status_code}). Проверь вход, доступ к модели и совместимость API."
        elif isinstance(exc, APIConnectionError):
            reason="Не удалось соединиться с провайдером модели."
        elif isinstance(exc, asyncio.TimeoutError):
            reason="Время выполнения задачи истекло."
        else:
            reason="Не удалось выполнить задачу Codex. Проверь доступ модели и настройки рабочего окружения."
        output={"ok":False,"error":reason}
    Path("ten-result.enc").write_text(encrypt(output,secret),encoding="ascii")

if __name__ == "__main__": asyncio.run(main())
