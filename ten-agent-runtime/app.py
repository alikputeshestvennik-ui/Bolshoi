"""Authenticated gateway: OpenAI Agents SDK plus Codex app-server.

The user provides a ChatGPT-plan OAuth token per request over HTTPS. The
service never persists it. Run this service in a hardened isolated container.
"""
import asyncio
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


async def run_codex(goal: str, token: str, model: str, base_url: str) -> tuple[str, str]:
    """Start one isolated, ephemeral Codex app-server and verify the turn outcome."""
    with tempfile.TemporaryDirectory(prefix="ten-codex-") as workspace:
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
                "-c", "shell_environment_policy.ignore_default_excludes=false"]
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
                "approvalPolicy": "never", "sandbox": "workspace-write", "ephemeral": True})
            thread_id = started["thread"]["id"]
            await request(3, "turn/start", {"threadId": thread_id, "input": [{"type": "text", "text": goal}]})
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
                    return result.replace(token, "[credential removed]"), thread_id
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


async def execute_goal(goal: str, token: str, model: str, base_url: str) -> Response:
    from agents import Agent, Runner, RunConfig, OpenAIResponsesModel, function_tool
    from openai import AsyncOpenAI
    result_holder: dict[str, str] = {}
    @function_tool
    async def codex_workspace(task: str) -> str:
        """Run the user's concrete goal in an isolated Codex workspace."""
        output, session_id = await run_codex(task, token, model, base_url)
        result_holder.update(output=output, session_id=session_id)
        return output
    async with AsyncOpenAI(api_key=token, base_url=base_url) as client:
        orchestrator = Agent(name="Тень: цель — результат",
            model=OpenAIResponsesModel(model=model, openai_client=client),
            instructions=("Call codex_workspace for the user's goal. Report the actual result, in the user's language. "
                          "Never claim success without a completed tool result."), tools=[codex_workspace])
        run = await Runner.run(orchestrator, goal, max_turns=8, run_config=RunConfig(tracing_disabled=True))
    if not result_holder: raise RuntimeError("The agent did not run Codex")
    return Response(result=str(run.final_output).replace(token, "[credential removed]"), session_id=result_holder["session_id"])

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
