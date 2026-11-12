"""Loopback-only bridge to the installed Codex CLI. No GitHub Actions."""
import hmac
import json
import os
from pathlib import Path
import secrets
import re
import subprocess
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = Path(os.environ.get('TEN_LOCAL_HOME', Path.home() / '.ten-local'))
ROOT.mkdir(mode=0o700, parents=True, exist_ok=True)
TOKEN = ROOT / 'token'
if not TOKEN.exists():
    fd = os.open(TOKEN, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'w') as f:
        f.write(secrets.token_urlsafe(32))
SECRET = TOKEN.read_text().strip()
WORK = ROOT / 'workspace'
WORK.mkdir(exist_ok=True)
LOCK = threading.Lock()

class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass  # Do not log prompts or credentials.

    def reply(self, status, value):
        data = json.dumps(value, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header('Content-Type', 'application/json; charset=utf-8')
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path == '/health':
            self.reply(200, {'status': 'ok', 'busy': LOCK.locked()})
        else:
            self.reply(404, {'error': 'Неизвестный адрес'})

    def do_POST(self):
        if self.path != '/run':
            return self.reply(404, {'error': 'Неизвестный адрес'})
        if not hmac.compare_digest(self.headers.get('Authorization', ''), 'Bearer ' + SECRET):
            return self.reply(401, {'error': 'Проверь ключ локального окружения'})
        try:
            length = int(self.headers.get('Content-Length', '0'))
            if not 0 < length <= 100000:
                raise ValueError()
            task = json.loads(self.rfile.read(length))
            goal = task['goal']
            if not isinstance(goal, str) or not 0 < len(goal) <= 16000:
                raise ValueError()
        except (ValueError, KeyError, TypeError):
            return self.reply(400, {'error': 'Некорректная задача'})
        selected = task.get('selected', '')
        provider_token = task.get('provider_token', '')
        if not isinstance(selected, str) or not re.fullmatch(r'[a-zA-Z0-9._/:\-]{0,160}', selected):
            return self.reply(400, {'error': 'Некорректная модель'})
        backend = None
        if selected.startswith('deepseek:'):
            backend = 'https://api.deepseek.com'
        elif selected.startswith('any:'):
            backend = 'https://anymodel.org/v1'
        elif selected and not selected.startswith('chatgpt:'):
            return self.reply(400, {'error': 'Провайдер пока не поддерживается'})
        if backend and (not isinstance(provider_token, str) or not provider_token.strip()):
            return self.reply(400, {'error': 'Укажи ключ выбранного провайдера'})
        if not LOCK.acquire(blocking=False):
            return self.reply(409, {'error': 'Codex уже выполняет задачу'})
        try:
            with tempfile.TemporaryDirectory(prefix='result-', dir=ROOT) as tmp:
                output = Path(tmp) / 'answer.txt'
                command = ['codex', 'exec', '--skip-git-repo-check', '--sandbox', os.environ.get('TEN_CODEX_SANDBOX', 'workspace-write'),
                           '-C', str(WORK), '-o', str(output), '-']
                env = os.environ.copy()
                if selected:
                    command[2:2] = ['-m', selected.split(':', 1)[1]]
                if backend:
                    env['TEN_PROVIDER_TOKEN'] = provider_token.strip()
                    command[2:2] = ['-c', 'model_provider="ten_provider"',
                        '-c', 'model_providers.ten_provider.name="Ten provider"',
                        '-c', 'model_providers.ten_provider.base_url=' + json.dumps(backend),
                        '-c', 'model_providers.ten_provider.env_key="TEN_PROVIDER_TOKEN"',
                        '-c', 'model_providers.ten_provider.wire_api="responses"',
                        '-c', 'model_providers.ten_provider.requires_openai_auth=false']
                result = subprocess.run(command, input=goal, text=True, env=env, stdout=subprocess.DEVNULL,
                                        stderr=subprocess.PIPE, timeout=1200)
                diagnostic = getattr(result, 'stderr', '') or ''
                if 'error building bubblewrap command' in diagnostic or 'cannot establish app-server socket mount isolation' in diagnostic:
                    return self.reply(503, {'error': 'Локальная песочница Codex несовместима с окружением телефона. Выполнение команд не подтверждено.'})
                if result.returncode != 0:
                    return self.reply(502, {'error': 'Локальный Codex завершился с ошибкой. Проверь вход и запуск Codex в терминале.'})
                answer = output.read_text() if output.exists() else ''
                if not answer.strip():
                    return self.reply(502, {'error': 'Codex не вернул текстовый результат'})
                self.reply(200, {'result': answer})
        except subprocess.TimeoutExpired:
            self.reply(504, {'error': 'Время задачи истекло'})
        except (OSError, UnicodeError):
            self.reply(500, {'error': 'Не удалось запустить локальный Codex'})
        finally:
            LOCK.release()

if __name__ == '__main__':
    print('Тень: локальный исполнитель на 127.0.0.1:8766', flush=True)
    ThreadingHTTPServer(('127.0.0.1', 8766), Handler).serve_forever()
