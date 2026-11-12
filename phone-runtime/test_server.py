import importlib.util
import json
import os
from pathlib import Path
import tempfile
import threading
import unittest
import urllib.request
import urllib.error
from unittest.mock import patch

class BridgeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        with patch.dict(os.environ, TEN_LOCAL_HOME=cls.temp.name):
            spec = importlib.util.spec_from_file_location('bridge', Path(__file__).with_name('server.py'))
            cls.bridge = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(cls.bridge)
        cls.server = cls.bridge.ThreadingHTTPServer(('127.0.0.1', 0), cls.bridge.Handler)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown(); cls.server.server_close(); cls.thread.join(); cls.temp.cleanup()
    def request(self, token, value):
        req = urllib.request.Request(f'http://127.0.0.1:{self.server.server_port}/run', data=json.dumps(value).encode(), headers={'Authorization': 'Bearer '+token})
        try:
            with urllib.request.urlopen(req) as r: return r.status, json.load(r)
        except urllib.error.HTTPError as r: return r.code, json.load(r)
    def test_cancel_real_process(self):
        import subprocess, time
        result = []
        runner = threading.Thread(target=lambda: result.append(self.bridge.run_process(
            ['python3', '-c', 'import time; time.sleep(60)'], input='', text=True,
            stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, timeout=10)))
        runner.start()
        for _ in range(100):
            if self.bridge.PROCESS is not None: break
            time.sleep(0.01)
        self.assertTrue(self.bridge.stop_process())
        runner.join(5)
        self.assertFalse(runner.is_alive())
        self.assertEqual(result[0].returncode, -9)
    def test_bad_token(self):
        self.assertEqual(self.request('wrong', {'goal':'test'})[0],401)
    def test_invalid_goal(self):
        self.assertEqual(self.request(self.bridge.SECRET, {'goal':''})[0],400)
    def test_busy(self):
        with self.bridge.LOCK:
            self.assertEqual(self.request(self.bridge.SECRET, {'goal':'test'})[0],409)
    def test_provider_requires_key(self):
        self.assertEqual(self.request(self.bridge.SECRET, {'goal':'test', 'selected':'deepseek:deepseek-flash'})[0],400)
    def test_provider_key_is_not_on_command_line(self):
        def run(args, **kwargs):
            self.assertEqual(kwargs['env']['TEN_PROVIDER_TOKEN'], 'test-secret')
            self.assertNotIn('test-secret', str(args))
            self.assertIn('deepseek-flash', args)
            Path(args[args.index('-o')+1]).write_text('done')
            return type('Result', (), {'returncode':0})()
        with patch.object(self.bridge, 'run_process', side_effect=run):
            self.assertEqual(self.request(self.bridge.SECRET, {'goal':'test', 'selected':'deepseek:deepseek-flash', 'provider_token':'test-secret'})[0],200)
    def test_command_receives_prompt_as_stdin(self):
        def run(args, **kwargs):
            self.assertEqual(kwargs['input'], 'hello $(false)')
            self.assertNotIn('hello $(false)', args)
            Path(args[args.index('-o')+1]).write_text('done')
            return type('Result', (), {'returncode':0})()
        with patch.object(self.bridge, 'run_process', side_effect=run):
            self.assertEqual(self.request(self.bridge.SECRET, {'goal':'hello $(false)'}), (200, {'result':'done'}))

    def test_anymodel_native_id_requires_key(self):
        self.assertEqual(self.request(self.bridge.SECRET, {'goal':'test', 'selected':'cc/claude-opus-5-5'})[0],400)
    def test_anymodel_native_id_passes_model_and_env(self):
        def run(args, **kwargs):
            self.assertEqual(kwargs['env']['TEN_PROVIDER_TOKEN'], 'test-secret')
            self.assertNotIn('test-secret', str(args))
            self.assertIn('cc/claude-opus-5-5', args)
            Path(args[args.index('-o')+1]).write_text('done')
            return type('Result', (), {'returncode':0})()
        with patch.object(self.bridge, 'run_process', side_effect=run):
            self.assertEqual(self.request(self.bridge.SECRET, {'goal':'test', 'selected':'cc/claude-opus-5-5', 'provider_token':'test-secret'}), (200, {'result':'done'}))
    def test_any_prefix_strips_to_model_id(self):
        def run(args, **kwargs):
            self.assertIn('gpt-5', args)
            self.assertNotIn('any:gpt-5', str(args))
            Path(args[args.index('-o')+1]).write_text('done')
            return type('Result', (), {'returncode':0})()
        with patch.object(self.bridge, 'run_process', side_effect=run):
            self.assertEqual(self.request(self.bridge.SECRET, {'goal':'test', 'selected':'any:gpt-5', 'provider_token':'test-secret'})[0],200)
    def test_unknown_provider_rejected(self):
        self.assertEqual(self.request(self.bridge.SECRET, {'goal':'test', 'selected':'google:gemini-3.5-flash-lite'})[0],400)

if __name__ == '__main__': unittest.main()
