"""GitHub Actions worker. Input/output are encrypted with a key derived from GH_PAT."""
import asyncio
import base64
import hashlib
import json
import os
import ipaddress
import socket
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

async def main() -> None:
    secret=key(os.environ.pop("GH_PAT", ""))
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
        result=await asyncio.wait_for(execute_goal(parsed.goal, task["provider_token"], parsed.model, base),timeout=1200)
        output={"ok":True,"result":result.result,"session_id":result.session_id}
    except Exception as exc:
        # Never publish exception details: provider errors can contain credentials.
        output={"ok":False,"error":"Codex не завершил задачу. Проверь доступ модели, совместимость Responses API и настройки песочницы."}
    Path("ten-result.enc").write_text(encrypt(output,secret),encoding="ascii")

if __name__ == "__main__": asyncio.run(main())
