import unittest
import worker

class CryptoTest(unittest.TestCase):
    def test_roundtrip_and_wrong_key(self):
        value={"goal":"проверка", "provider_token":"example"}
        data=worker.encrypt(value,worker.key("token-a"))
        self.assertNotIn("проверка",data)
        self.assertEqual(worker.decrypt(data,worker.key("token-a")),value)
        with self.assertRaises(Exception): worker.decrypt(data,worker.key("token-b"))

if __name__ == '__main__': unittest.main()

class PreviousOutputTest(unittest.TestCase):
    def test_retrieves_and_decrypts_previous_workspace(self):
        import io, json, zipfile
        from unittest.mock import patch, Mock
        secret=worker.key('pat')
        value={'ok':True,'archive':'saved-workspace'}
        encrypted=worker.encrypt(value,secret)
        data=io.BytesIO()
        with zipfile.ZipFile(data,'w') as z:z.writestr('ten-result.enc',encrypted)
        class Response:
            def __init__(self, data):self.data=data
            def __enter__(self):return self
            def __exit__(self,*args):pass
            def read(self,n):return self.data[:n]
        opener=Mock()
        opener.open.side_effect=[Response(json.dumps({'artifacts':[{'id':7,'name':'ten-result-abc','expired':False}]}).encode()),Response(data.getvalue())]
        with patch('worker.urllib.request.build_opener',return_value=opener):
            self.assertEqual(worker.previous_output('pat',42,secret),'saved-workspace')
        self.assertEqual(opener.open.call_count,2)
