import asyncio
import os
import unittest
from unittest.mock import patch
from fastapi import HTTPException
import app

class GatewayTest(unittest.TestCase):
    def test_auth_required(self):
        with patch.dict(os.environ, {"TEN_AGENT_TOKEN": "secret"}):
            with self.assertRaises(HTTPException): app.authenticate("Bearer wrong")
            app.authenticate("Bearer secret")
    def test_no_server_configuration(self):
        with patch.dict(os.environ, {"TEN_AGENT_TOKEN": ""}):
            with self.assertRaises(HTTPException) as err: app.authenticate(None)
            self.assertEqual(err.exception.status_code, 503)
    def test_request_validation(self):
        with self.assertRaises(ValueError): app.Request(goal="", model="deepseek-flash", base_url="https://api.deepseek.com")

class EndpointTest(unittest.IsolatedAsyncioTestCase):
    async def test_rejects_unapproved_backend(self):
        req = app.Request(goal="проверка", model="deepseek-flash", base_url="https://untrusted.example")
        with patch.dict(os.environ, {"TEN_AGENT_TOKEN": "secret", "TEN_PROVIDER_URLS": "https://api.deepseek.com"}):
            with self.assertRaises(HTTPException) as err:
                await app.run(req, authorization="Bearer secret", x_provider_token="key")
        self.assertEqual(err.exception.status_code, 400)
    async def test_success_uses_selected_backend(self):
        from unittest.mock import AsyncMock
        req = app.Request(goal="цель", model="deepseek-flash", base_url="https://api.deepseek.com/")
        fake = AsyncMock(return_value=app.Response(result="готово", session_id="s1"))
        with patch.dict(os.environ, {"TEN_AGENT_TOKEN": "secret", "TEN_PROVIDER_URLS": "https://api.deepseek.com"}), patch.object(app, "execute_goal", fake):
            result = await app.run(req, authorization="Bearer secret", x_provider_token="key")
        self.assertEqual(result.result, "готово")
        fake.assert_awaited_once_with("цель", "key", "deepseek-flash", "https://api.deepseek.com")
    async def test_failure_is_not_reported_as_success(self):
        from unittest.mock import AsyncMock
        req = app.Request(goal="цель", model="deepseek-flash", base_url="https://api.deepseek.com")
        with patch.dict(os.environ, {"TEN_AGENT_TOKEN": "secret"}), patch.object(app, "execute_goal", AsyncMock(side_effect=RuntimeError("secret detail"))):
            with self.assertRaises(HTTPException) as err:
                await app.run(req, authorization="Bearer secret", x_provider_token="key")
        self.assertEqual(err.exception.status_code, 502)
        self.assertNotIn("secret detail", err.exception.detail)

if __name__ == "__main__": unittest.main()

class CodexProtocolTest(unittest.IsolatedAsyncioTestCase):
    async def invoke(self, status):
        import json
        from unittest.mock import AsyncMock, Mock, patch
        messages=[{"id":1,"result":{}}, {"id":2,"result":{"thread":{"id":"thread-1"}}},
                  {"id":3,"result":{}}, {"method":"item/agentMessage/delta","params":{"delta":"готово"}},
                  {"method":"turn/completed","params":{"turn":{"status":status}}}]
        reader=asyncio.StreamReader()
        reader.feed_data(b''.join((json.dumps(m)+'\n').encode() for m in messages));reader.feed_eof()
        fake=Mock();fake.stdout=reader;fake.stdin=Mock();fake.stdin.drain=AsyncMock();fake.returncode=0
        with patch('app.asyncio.create_subprocess_exec', AsyncMock(return_value=fake)) as spawn:
            if status == 'completed':
                self.assertEqual(await app.run_codex('цель','provider-secret','deepseek-flash','https://api.deepseek.com'), ('готово','thread-1'))
            else:
                with self.assertRaises(RuntimeError): await app.run_codex('цель','provider-secret','deepseek-flash','https://api.deepseek.com')
            options=spawn.await_args.kwargs
            self.assertEqual(options['env']['ACCESS_TOKEN'],'provider-secret')
            self.assertNotIn('GH_PAT', options['env'])
            self.assertTrue(options['start_new_session'])
        wire=b''.join(call.args[0] for call in fake.stdin.write.call_args_list).decode()
        self.assertIn('thread/start',wire)
        self.assertIn('workspace-write',wire)
        self.assertNotIn('provider-secret',wire)
    async def test_success(self): await self.invoke('completed')
    async def test_failed_turn(self): await self.invoke('failed')
