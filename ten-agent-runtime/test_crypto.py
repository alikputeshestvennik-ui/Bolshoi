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
