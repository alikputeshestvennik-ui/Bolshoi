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
        with patch.object(self.bridge.subprocess, 'run', side_effect=run):
            self.assertEqual(self.request(self.bridge.SECRET, {'goal':'test', 'selected':'deepseek:deepseek-flash', 'provider_token':'test-secret'})[0],200)
    def test_command_receives_prompt_as_stdin(self):
        def run(args, **kwargs):
            self.assertEqual(kwargs['input'], 'hello $(false)')
            self.assertNotIn('hello $(false)', args)
            Path(args[args.index('-o')+1]).write_text('done')
            return type('Result', (), {'returncode':0})()
        with patch.object(self.bridge.subprocess, 'run', side_effect=run):
            self.assertEqual(self.request(self.bridge.SECRET, {'goal':'hello $(false)'}), (200, {'result':'done'}))

if __name__ == '__main__': unittest.main()
