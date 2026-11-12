"""Exercise real Codex app-server + shell against a local fake Responses API."""
import asyncio
import json
import os
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import app

class Provider(BaseHTTPRequestHandler):
    calls = []
    def log_message(self, *args): pass
    def do_POST(self):
        request=json.loads(self.rfile.read(int(self.headers.get('Content-Length','0'))))
        type(self).calls.append(request)
        if len(self.calls)==1:
            output={'id':'fc_mock','type':'function_call','call_id':'call_mock','name':'exec_command',
                    'arguments':json.dumps({'cmd':'python3 -c "print(6*7)"','yield_time_ms':10000}),'status':'completed'}
        else:
            output={'id':'msg_mock','type':'message','role':'assistant','status':'completed',
                    'content':[{'type':'output_text','text':'MOCK_DONE','annotations':[]}]}
        response={'id':'resp_'+str(len(self.calls)),'object':'response','created_at':1,'status':'completed',
                  'model':'deepseek-flash','output':[output],'parallel_tool_calls':False,'tool_choice':'auto','tools':[],
                  'usage':{'input_tokens':1,'output_tokens':1,'total_tokens':2,'input_tokens_details':{'cached_tokens':0},'output_tokens_details':{'reasoning_tokens':0}}}
        events=[{'type':'response.created','response':dict(response,status='in_progress',output=[])},
                {'type':'response.output_item.added','output_index':0,'item':output},
                {'type':'response.output_item.done','output_index':0,'item':output},
                {'type':'response.completed','response':response}]
        body=''.join('event: '+e['type']+'\ndata: '+json.dumps(e)+'\n\n' for e in events).encode()
        self.send_response(200);self.send_header('Content-Type','text/event-stream');self.send_header('Content-Length',str(len(body)));self.end_headers();self.wfile.write(body)

@unittest.skipUnless(os.getenv('TEN_RUN_CODEX_E2E')=='1','requires real Codex CLI and Linux sandbox')
class CodexSandboxTest(unittest.IsolatedAsyncioTestCase):
    async def test_python_tool_runs_in_sandbox(self):
        Provider.calls=[]
        server=ThreadingHTTPServer(('127.0.0.1',0),Provider)
        thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
        try:
            result=await asyncio.wait_for(app.run_codex('Use Python to calculate 6 times 7','fake-token','deepseek-flash',f'http://127.0.0.1:{server.server_port}/v1'),60)
            self.assertEqual(result[0],'MOCK_DONE')
            outputs=[x.get('output','') for r in Provider.calls for x in r.get('input',[]) if x.get('type')=='function_call_output']
            self.assertTrue(any('42' in str(o) and 'Process exited with code 0' in str(o) for o in outputs),f'Python tool did not succeed: {outputs}')
        finally:
            server.shutdown();server.server_close();thread.join()
