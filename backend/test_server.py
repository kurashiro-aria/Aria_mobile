import json
import threading
import unittest
import urllib.request
from http.server import ThreadingHTTPServer
from server import Handler

class BackendTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.base = f"http://127.0.0.1:{cls.server.server_port}"

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown(); cls.server.server_close()

    def test_health(self):
        with urllib.request.urlopen(self.base + "/v1/health") as r:
            self.assertEqual(200, r.status)
            self.assertEqual("ok", json.load(r)["status"])

    def test_respond_preserves_request_id(self):
        payload = json.dumps({"requestId":"abc","message":"prepared", "generation":{"maxOutputTokens":384}}).encode()
        req = urllib.request.Request(self.base + "/v1/brain/respond", data=payload, headers={"Content-Type":"application/json", "X-ARIA-Protocol":"1"}, method="POST")
        with urllib.request.urlopen(req) as r:
            body = json.load(r)
            self.assertEqual("abc", body["requestId"])
            self.assertTrue(body["reply"])

    def test_invalid_request(self):
        payload = json.dumps({"message":"missing id"}).encode()
        req = urllib.request.Request(self.base + "/v1/brain/respond", data=payload, headers={"X-ARIA-Protocol":"1"}, method="POST")
        with self.assertRaises(urllib.error.HTTPError) as cm:
            urllib.request.urlopen(req)
        self.assertEqual(400, cm.exception.code)

    def test_protocol_header_is_required(self):
        payload = json.dumps({"requestId": "abc", "message": "hello"}).encode()
        req = urllib.request.Request(self.base + "/v1/brain/respond", data=payload, method="POST")
        with self.assertRaises(urllib.error.HTTPError) as cm:
            urllib.request.urlopen(req)
        self.assertEqual(400, cm.exception.code)

    def test_duplicate_request_is_rejected(self):
        payload = json.dumps({"requestId": "duplicate-test", "message": "hello"}).encode()
        headers = {"X-ARIA-Protocol": "1"}
        with urllib.request.urlopen(urllib.request.Request(self.base + "/v1/brain/respond", data=payload, headers=headers, method="POST")):
            pass
        with self.assertRaises(urllib.error.HTTPError) as cm:
            urllib.request.urlopen(urllib.request.Request(self.base + "/v1/brain/respond", data=payload, headers=headers, method="POST"))
        self.assertEqual(409, cm.exception.code)

if __name__ == "__main__": unittest.main()
