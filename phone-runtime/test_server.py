import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request
from unittest.mock import patch


class BridgeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        with patch.dict(os.environ, TEN_LOCAL_HOME=cls.temp.name):
            spec = importlib.util.spec_from_file_location("bridge", Path(__file__).with_name("server.py"))
            cls.bridge = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(cls.bridge)
        cls.server = cls.bridge.ThreadingHTTPServer(("127.0.0.1", 0), cls.bridge.Handler)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join()
        cls.temp.cleanup()

    def request(self, token, value, path="/run"):
        req = urllib.request.Request(
            f"http://127.0.0.1:{self.server.server_port}{path}",
            data=json.dumps(value).encode(),
            headers={"Authorization": "Bearer " + token},
        )
        try:
            with urllib.request.urlopen(req, timeout=10) as response:
                return response.status, json.load(response)
        except urllib.error.HTTPError as response:
            return response.code, json.load(response)

    def get_status(self, token, task_id=None):
        suffix = f"?task_id={task_id}" if task_id else ""
        req = urllib.request.Request(
            f"http://127.0.0.1:{self.server.server_port}/status{suffix}",
            headers={"Authorization": "Bearer " + token},
        )
        try:
            with urllib.request.urlopen(req, timeout=10) as response:
                return response.status, json.load(response)
        except urllib.error.HTTPError as response:
            return response.code, json.load(response)

    def fake_run(self, answer="done", inspect=None):
        def run(task, args, prompt, env, output, timeout):
            if inspect:
                inspect(task, args, prompt, env, output, timeout)
            output.write_text(answer)
            task.last_answer = answer
            task.status = "done"
            return subprocess.CompletedProcess(args, 0, answer, "")
        return run

    def test_token_created_and_private(self):
        token = Path(self.temp.name, "token")
        self.assertTrue(token.exists())
        self.assertEqual(token.read_text(), self.bridge.SECRET)
        self.assertEqual(token.stat().st_mode & 0o777, 0o600)

    def test_bad_token(self):
        self.assertEqual(self.request("wrong", {"goal": "test"})[0], 401)

    def test_invalid_goal(self):
        self.assertEqual(self.request(self.bridge.SECRET, {"goal": ""})[0], 400)

    def test_busy_same_task(self):
        task = self.bridge.Task("busy-task")
        task.process = subprocess.Popen(["python3", "-c", "import time; time.sleep(30)"], start_new_session=True)
        try:
            with self.bridge.TASKS_LOCK:
                self.bridge.TASKS["busy-task"] = task
            self.assertEqual(self.request(self.bridge.SECRET, {"goal": "test", "task_id": "busy-task"})[0], 409)
        finally:
            self.bridge.stop_task("busy-task")
            task.process.wait()

    def test_provider_requires_key(self):
        self.assertEqual(self.request(self.bridge.SECRET, {"goal": "test", "selected": "deepseek:deepseek-flash"})[0], 400)

    def test_gpt_requires_key(self):
        self.assertEqual(self.request(self.bridge.SECRET, {"goal": "test", "selected": "chatgpt:gpt-5.1-codex"})[0], 400)

    def test_provider_key_not_on_command_line(self):
        def inspect(task, args, prompt, env, output, timeout):
            self.assertEqual(env["TEN_PROVIDER_TOKEN"], "test-secret")
            self.assertNotIn("test-secret", str(args))
            self.assertIn("deepseek-flash", args)
            self.assertIn('wire_api="chat"', str(args))

        with patch.object(self.bridge, "run_process", side_effect=self.fake_run(inspect=inspect)):
            self.assertEqual(self.request(self.bridge.SECRET, {"goal": "test", "selected": "deepseek:deepseek-flash", "provider_token": "test-secret"})[0], 200)

    def test_prompt_goes_to_stdin(self):
        def inspect(task, args, prompt, env, output, timeout):
            self.assertEqual(prompt, "hello $(false)")
            self.assertNotIn("hello $(false)", args)

        with patch.object(self.bridge, "run_process", side_effect=self.fake_run(inspect=inspect)):
            self.assertEqual(self.request(self.bridge.SECRET, {"goal": "hello $(false)"}), (200, {"result": "done", "task_id": "default", "files": []}))

    def test_output_file_is_reported(self):
        def inspect(task, args, prompt, env, output, timeout):
            workspace = Path(args[args.index("-C") + 1])
            (workspace / "result.txt").write_text("created by Codex", encoding="utf-8")

        with patch.object(self.bridge, "run_process", side_effect=self.fake_run(inspect=inspect)):
            status, body = self.request(self.bridge.SECRET, {"goal": "make a file", "task_id": "files-task"})
        self.assertEqual(status, 200)
        self.assertEqual(body["files"][0]["name"], "result.txt")
        self.assertGreater(body["files"][0]["size"], 0)

    def test_gpt_uses_openai_responses(self):
        def inspect(task, args, prompt, env, output, timeout):
            self.assertEqual(env["TEN_PROVIDER_TOKEN"], "chatgpt-token")
            self.assertIn("https://api.openai.com/v1", str(args))
            self.assertIn('wire_api="responses"', str(args))
            self.assertIn("gpt-5.1-codex", args)

        with patch.object(self.bridge, "run_process", side_effect=self.fake_run(inspect=inspect)):
            self.assertEqual(self.request(self.bridge.SECRET, {"goal": "test", "selected": "chatgpt:gpt-5.1-codex", "provider_token": "chatgpt-token"})[0], 200)

    def test_anymodel_native_id_requires_key(self):
        self.assertEqual(self.request(self.bridge.SECRET, {"goal": "test", "selected": "cc/claude-opus-5-5"})[0], 400)

    def test_anymodel_native_id_passes_model_and_env(self):
        def inspect(task, args, prompt, env, output, timeout):
            self.assertEqual(env["TEN_PROVIDER_TOKEN"], "test-secret")
            self.assertIn("cc/claude-opus-5-5", args)

        with patch.object(self.bridge, "run_process", side_effect=self.fake_run(inspect=inspect)):
            self.assertEqual(self.request(self.bridge.SECRET, {"goal": "test", "selected": "cc/claude-opus-5-5", "provider_token": "test-secret"})[0], 200)

    def test_any_prefix_strips_to_model_id(self):
        def inspect(task, args, prompt, env, output, timeout):
            self.assertIn("gpt-5", args)
            self.assertNotIn("any:gpt-5", str(args))

        with patch.object(self.bridge, "run_process", side_effect=self.fake_run(inspect=inspect)):
            self.assertEqual(self.request(self.bridge.SECRET, {"goal": "test", "selected": "any:gpt-5", "provider_token": "test-secret"})[0], 200)

    def test_unknown_provider_rejected(self):
        self.assertEqual(self.request(self.bridge.SECRET, {"goal": "test", "selected": "google:gemini-3.5-flash-lite"})[0], 400)

    def test_compatible_requires_https(self):
        self.assertEqual(self.request(self.bridge.SECRET, {"goal": "test", "selected": "compatible:gpt-4o", "base_url": "http://api.example.com", "provider_token": "k"})[0], 400)

    def test_compatible_passes_model_base_and_token(self):
        def inspect(task, args, prompt, env, output, timeout):
            self.assertEqual(env["TEN_PROVIDER_TOKEN"], "k")
            self.assertIn("gpt-4o", args)
            self.assertIn("https://api.example.com/v1", str(args))

        with patch.object(self.bridge, "run_process", side_effect=self.fake_run(inspect=inspect)):
            self.assertEqual(self.request(self.bridge.SECRET, {"goal": "test", "selected": "compatible:gpt-4o", "base_url": "https://api.example.com/v1", "provider_token": "k"})[0], 200)

    def test_gh_token_passed_to_env(self):
        def inspect(task, args, prompt, env, output, timeout):
            self.assertEqual(env["GH_TOKEN"], "ghp_test")
            self.assertEqual(env["GITHUB_TOKEN"], "ghp_test")
            self.assertNotIn("ghp_test", str(args))

        with patch.object(self.bridge, "run_process", side_effect=self.fake_run(inspect=inspect)):
            self.assertEqual(self.request(self.bridge.SECRET, {"goal": "test", "selected": "deepseek:deepseek-flash", "provider_token": "k", "gh_token": "ghp_test"})[0], 200)

    def test_status_reports_finished_task(self):
        with patch.object(self.bridge, "run_process", side_effect=self.fake_run(answer="answer")):
            self.request(self.bridge.SECRET, {"goal": "test", "task_id": "status-task"})
        status, body = self.get_status(self.bridge.SECRET, "status-task")
        self.assertEqual(status, 200)
        self.assertEqual(body["status"], "done")
        self.assertEqual(body["answer"], "answer")

    def test_status_requires_token(self):
        self.assertEqual(self.get_status("wrong")[0], 401)

    def test_cancel_real_process(self):
        task = self.bridge.Task("cancel-task")
        process = subprocess.Popen(["python3", "-c", "import time; time.sleep(60)"], start_new_session=True)
        task.process = process
        with self.bridge.TASKS_LOCK:
            self.bridge.TASKS["cancel-task"] = task
        self.assertTrue(self.bridge.stop_task("cancel-task"))
        process.wait(timeout=5)
        self.assertEqual(process.returncode, -9)

    def test_health_does_not_leak_task_details(self):
        req = urllib.request.Request(f"http://127.0.0.1:{self.server.server_port}/health")
        with urllib.request.urlopen(req, timeout=10) as response:
            body = json.load(response)
        self.assertEqual(body["status"], "ok")
        self.assertIn("busy", body)
        self.assertNotIn("tasks", body)


if __name__ == "__main__":
    unittest.main()
