"""Authenticated gateway: OpenAI Agents SDK plus Codex app-server.

The user provides a ChatGPT-plan OAuth token per request over HTTPS. The
service never persists it. Run this service in a hardened isolated container.
"""
import asyncio
import base64
import io
import zipfile
from pathlib import Path
import hmac
import json
import os
import signal
import tempfile
from typing import Annotated
from fastapi import FastAPI, Header, HTTPException
from pydantic import BaseModel, Field

app = FastAPI(title="Тень agent gateway")

class Request(BaseModel):
    goal: str = Field(min_length=1, max_length=16000)
    model: str = Field(min_length=1, max_length=128, pattern=r"^[a-zA-Z0-9._/:-]+$")
    base_url: str = Field(min_length=1, max_length=512)

class Response(BaseModel):
    result: str
    session_id: str
    archive: str | None = None


def authenticate(authorization: str | None) -> None:
    expected = os.getenv("TEN_AGENT_TOKEN", "")
    if not expected:
        raise HTTPException(503, "Сервис агента не настроен")
    given = (authorization or "").removeprefix("Bearer ")
    if not hmac.compare_digest(given, expected):
        raise HTTPException(401, "Неверный токен")

@app.get("/healthz")
def health() -> dict:
    return {"status": "ok", "configured": bool(os.getenv("TEN_AGENT_TOKEN"))}


def package_output(workspace: str) -> str | None:
    root=Path(workspace) / "output"
    if not root.is_dir(): return None
    data=io.BytesIO(); total=0; count=0
    with zipfile.ZipFile(data, "w", zipfile.ZIP_DEFLATED) as archive:
        for directory, folders, files in os.walk(root, followlinks=False):
            folders[:] = [name for name in sorted(folders) if name not in {".git", ".codex", ".venv", "node_modules", "__pycache__", "build", ".gradle"} and not (Path(directory)/name).is_symlink()]
            for name in sorted(files):
                path=Path(directory)/name
                if not path.is_file() or path.is_symlink(): continue
                size=path.stat().st_size; total+=size;count+=1
                if total>20_000_000 or count>1000: raise RuntimeError("Output exceeds delivery limit")
                archive.write(path, path.relative_to(root).as_posix())
    if not count: return None
    return base64.b64encode(data.getvalue()).decode("ascii")

def restore_output(workspace: str, encoded: str | None) -> None:
    root=Path(workspace, "output")
    root.mkdir(exist_ok=True)
    if not encoded: return
    raw=base64.b64decode(encoded, validate=True)
    if len(raw)>25_000_000: raise ValueError("Workspace archive too large")
    with zipfile.ZipFile(io.BytesIO(raw)) as archive:
        entries=archive.infolist()
        if len(entries)>1000 or sum(e.file_size for e in entries)>20_000_000: raise ValueError("Workspace contents too large")
        for entry in entries:
            path=Path(entry.filename)
            if path.is_absolute() or ".." in path.parts or entry.is_dir() or (entry.external_attr >> 16) & 0o170000 == 0o120000:
                raise ValueError("Unsafe workspace path")
            target=(root/path).resolve()
            if not target.is_relative_to(root.resolve()): raise ValueError("Unsafe workspace path")
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(archive.read(entry))
            permissions=(entry.external_attr >> 16) & 0o777
            if permissions: target.chmod(permissions)

async def run_codex(goal: str, token: str, model: str, base_url: str, initial_archive: str | None = None) -> tuple[str, str, str | None]:
    """Start one isolated, ephemeral Codex app-server and verify the turn outcome."""
    with tempfile.TemporaryDirectory(prefix="ten-codex-") as workspace:
        restore_output(workspace, initial_archive)
        sandbox_mode = os.getenv("TEN_CODEX_SANDBOX", "workspace-write")
        if sandbox_mode not in {"workspace-write", "danger-full-access"}:
            raise ValueError("Unsupported Codex sandbox mode")
        env = {"PATH": os.environ.get("PATH", "/usr/local/bin:/usr/bin:/bin"),
               "HOME": workspace, "ACCESS_TOKEN": token, "LANG": "C.UTF-8"}
        args = ["codex", "app-server", "--listen", "stdio://",
                "-c", 'model_provider="openai_chatgpt_plan"',
                "-c", 'model_providers.openai_chatgpt_plan.name="ChatGPT plan"',
                "-c", "model_providers.openai_chatgpt_plan.base_url=" + json.dumps(base_url),
                "-c", 'model_providers.openai_chatgpt_plan.env_key="ACCESS_TOKEN"',
                "-c", 'model_providers.openai_chatgpt_plan.wire_api="responses"',
                "-c", 'model_providers.openai_chatgpt_plan.requires_openai_auth=false',
                "-c", 'model_providers.openai_chatgpt_plan.supports_websockets=false',
                "-c", "shell_environment_policy.ignore_default_excludes=false",
                "-c", "sandbox_workspace_write.network_access=true"]
        if "deepseek" in model.lower():
            catalog=json.loads(Path(__file__).with_name("deepseek-models.json").read_text())
            family="deepseek-v4-pro" if "pro" in model.lower() else "deepseek-flash"
            selected=next(entry for entry in catalog["models"] if entry["slug"]==family)
            selected["slug"]=model
            catalog_file=Path(workspace,"provider-models.json")
            catalog_file.write_text(json.dumps({"models":[selected]}),encoding="utf-8")
            args.extend(["-c", "model_catalog_json=" + json.dumps(str(catalog_file))])
        process = await asyncio.create_subprocess_exec(*args, stdin=asyncio.subprocess.PIPE,
                    stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.DEVNULL, env=env, cwd=workspace, start_new_session=True, limit=2_000_000)
        async def send(message: dict) -> None:
            process.stdin.write((json.dumps(message, ensure_ascii=False) + "\n").encode())
            await process.stdin.drain()
        async def next_message() -> dict:
            raw = await process.stdout.readline()
            if not raw: raise RuntimeError("Codex app-server exited")
            return json.loads(raw)
        async def request(id: int, method: str, params: dict) -> dict:
            await send({"id": id, "method": method, "params": params})
            while True:
                msg = await next_message()
                if msg.get("id") == id:
                    if "error" in msg: raise RuntimeError("Codex app-server rejected the request")
                    return msg.get("result", {})
        try:
            await request(1, "initialize", {"clientInfo": {"name": "ten", "title": "Тень", "version": "1.0"}})
            await send({"method": "initialized", "params": {}})
            started = await request(2, "thread/start", {"model": model, "cwd": workspace,
                "approvalPolicy": "never", "sandbox": sandbox_mode, "ephemeral": True})
            thread_id = started["thread"]["id"]
            await request(3, "turn/start", {"threadId": thread_id, "input": [{"type": "text", "text": "The output/ directory contains the prior saved workspace. Place deliverable and project files there so they persist; keep a concise output/MEMORY.md for continuity where useful. Use the terminal and developer tools to complete the goal.\n\n" + goal}]})
            deltas = []
            final = ""
            while True:
                event = await next_message()
                method, params = event.get("method"), event.get("params", {})
                if method == "item/agentMessage/delta": deltas.append(params.get("delta", ""))
                if method == "item/completed" and params.get("item", {}).get("type") == "agentMessage":
                    final = params["item"].get("text", "")
                if method == "turn/completed":
                    if params.get("turn", {}).get("status") != "completed": raise RuntimeError("Codex turn failed")
                    result = final or "".join(deltas)
                    if not result: raise RuntimeError("Codex returned no result")
                    return result.replace(token, "[credential removed]"), thread_id, package_output(workspace)
                if event.get("id") is not None and method:
                    raise RuntimeError("Codex requested unsupported interactive approval")
        finally:
            if process.returncode is None:
                try: os.killpg(process.pid, signal.SIGTERM)
                except ProcessLookupError: pass
                try: await asyncio.wait_for(process.wait(), 5)
                except asyncio.TimeoutError:
                    try: os.killpg(process.pid, signal.SIGKILL)
                    except ProcessLookupError: pass
                    await process.wait()


async def execute_goal(goal: str, token: str, model: str, base_url: str, initial_archive: str | None = None) -> Response:
    # Codex handles ChatGPT plan streaming and tool requirements directly.
    if base_url.rstrip('/') == 'https://api.openai.com/v1':
        output, session_id, archive = await run_codex(goal, token, model, base_url, initial_archive)
        return Response(result=output, session_id=session_id, archive=archive)
    from agents import Agent, Runner, RunConfig, OpenAIResponsesModel, function_tool
    from openai import AsyncOpenAI
    result_holder: dict[str, str | None] = {}
    @function_tool
    async def codex_workspace(task: str) -> str:
        """Run the user's concrete goal in an isolated Codex workspace."""
        output, session_id, archive = await run_codex(task, token, model, base_url, initial_archive)
        result_holder.update(output=output, session_id=session_id, archive=archive)
        return output
    async with AsyncOpenAI(api_key=token, base_url=base_url) as client:
        orchestrator = Agent(name="Тень: цель — результат",
            model=OpenAIResponsesModel(model=model, openai_client=client),
            instructions=("Call codex_workspace for the user's goal. Report the actual result, in the user's language. "
                          "Never claim success without a completed tool result."), tools=[codex_workspace])
        run = await Runner.run(orchestrator, goal, max_turns=8, run_config=RunConfig(tracing_disabled=True))
    if not result_holder: raise RuntimeError("The agent did not run Codex")
    return Response(result=str(run.final_output).replace(token, "[credential removed]"), session_id=str(result_holder["session_id"]), archive=result_holder.get("archive"))

@app.post("/v1/run", response_model=Response)
async def run(request: Request, authorization: Annotated[str | None, Header()] = None,
              x_provider_token: Annotated[str | None, Header()] = None) -> Response:
    authenticate(authorization)
    if not x_provider_token: raise HTTPException(401, "Нет ключа выбранного провайдера")
    allowed = {s.strip().rstrip("/") for s in os.getenv("TEN_PROVIDER_URLS", "https://api.deepseek.com,https://anymodel.org/v1,https://api.openai.com/v1").split(",") if s.strip()}
    base_url = request.base_url.rstrip("/")
    if not base_url.startswith("https://") or base_url not in allowed:
        raise HTTPException(400, "Адрес провайдера не разрешён на сервисе")
    try:
        return await asyncio.wait_for(execute_goal(request.goal, x_provider_token, request.model, base_url), timeout=1200)
    except Exception as e:
        raise HTTPException(502, "Не удалось выполнить задачу через Codex") from e
