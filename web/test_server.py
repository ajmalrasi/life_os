import json
import threading
import unittest
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from server import Handler


class Upstream(BaseHTTPRequestHandler):
    requests = []

    def do_GET(self):
        self.requests.append((self.path, self.headers.get("Authorization")))
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(b'{"day":"2026-10-07"}')

    def do_POST(self):
        self.requests.append((self.path, self.headers.get("Authorization")))
        data = self.rfile.read(int(self.headers.get("Content-Length", 0)))
        self.send_response(200)
        self.end_headers()
        self.wfile.write(data)

    do_PATCH = do_POST
    do_PUT = do_POST

    def do_DELETE(self):
        self.requests.append((self.path, self.headers.get("Authorization")))
        self.send_response(204)
        self.end_headers()

    def log_message(self, *args):
        pass


class ProxyTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.upstream = ThreadingHTTPServer(("127.0.0.1", 0), Upstream)
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        cls.server.backend = f"http://127.0.0.1:{cls.upstream.server_port}"
        cls.server.api_token = "test-secret"
        cls.server.allowed_hosts = {"127.0.0.1"}
        cls.url = f"http://127.0.0.1:{cls.server.server_port}"
        for server in (cls.upstream, cls.server):
            threading.Thread(target=server.serve_forever, daemon=True).start()

    @classmethod
    def tearDownClass(cls):
        for server in (cls.server, cls.upstream):
            server.shutdown()
            server.server_close()

    def request(self, path, body=None, headers=None, method=None):
        req = urllib.request.Request(self.url + path, data=body, headers=headers or {}, method=method)
        try:
            with urllib.request.urlopen(req) as response:
                return response.status, response.read(), dict(response.headers)
        except urllib.error.HTTPError as error:
            return error.code, error.read(), dict(error.headers)

    def test_private_token_stays_on_server_and_api_is_not_cached(self):
        code, data, headers = self.request("/api/analytics/today")
        self.assertEqual(code, 200)
        self.assertEqual(Upstream.requests[-1][1], "Bearer test-secret")
        self.assertNotIn(b"test-secret", data)
        self.assertEqual(headers["Cache-Control"], "no-store")

    def test_proxy_blocks_sensitive_and_cross_site_routes(self):
        self.assertEqual(self.request("/api/mail/accounts")[0], 404)
        self.assertEqual(self.request("/api/analytics/today", headers={"Sec-Fetch-Site":"cross-site"})[0], 403)
        self.assertEqual(self.request("/", headers={"Host":"evil.example"})[0], 403)

    def test_only_same_origin_authorized_writes_pass(self):
        body = json.dumps({"day":"2026-10-07", "mood":4}).encode()
        self.assertEqual(self.request("/api/mood", body)[0], 403)
        self.assertEqual(self.request("/api/mood", body, {"Origin":"http://evil.example"})[0], 403)
        code, data, _ = self.request("/api/mood", body, {"Origin":self.url})
        self.assertEqual(code, 200)
        self.assertEqual(data, body)
        self.assertEqual(self.request("/api/expenses/sync", body, {"Origin":self.url})[0], 405)

    def test_money_mutations_require_same_origin_and_use_exact_methods(self):
        for path, method in (("/api/expenses/1/category", "PATCH"), ("/api/statements/1/paid", "PATCH"), ("/api/budgets/food", "PUT"), ("/api/money-profile", "PUT"), ("/api/expenses/refunds", "POST"), ("/api/expenses/repayments", "POST")):
            with self.subTest(path=path):
                self.assertEqual(self.request(path, b"{}", method=method)[0], 403)
                self.assertEqual(self.request(path, b"{}", {"Origin":self.url}, method=method)[0], 200)
                self.assertEqual(self.request(path, b"{}", {"Origin":"http://evil.example"}, method=method)[0], 403)
        self.assertEqual(self.request("/api/budgets/food", headers={"Origin":self.url}, method="DELETE")[0], 204)
        self.assertEqual(self.request("/api/expenses/refunds/1", headers={"Origin":self.url}, method="DELETE")[0], 204)
        self.assertEqual(self.request("/api/trips/1", headers={"Origin":self.url}, method="DELETE")[0], 405)

    def test_money_detail_read_routes(self):
        for path in ("/api/expenses/recurring", "/api/expenses/1/repayment-candidates", "/api/statements/1/details", "/api/statements/cycles/current/hdfc/6116", "/api/money-profile"):
            self.assertEqual(self.request(path)[0], 200)


if __name__ == "__main__":
    unittest.main()
