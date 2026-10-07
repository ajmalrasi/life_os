"""Private LAN web client and narrowly scoped, same-origin LifeOS API proxy."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import argparse
import json
import mimetypes
import os
import re
import threading
import urllib.error
import urllib.parse
import urllib.request

PUBLIC = Path(__file__).parent / "public"
READS = re.compile(r"/(analytics/(today|scores|synthesis|weekly-recap)|expenses(/recurring|/\d+/repayment-candidates)?|money-profile|budgets|statements(/\d+/details|/cycles/current(/[a-zA-Z0-9_-]+/\d+)?)?|status/sync|mood(/\d{4}-\d{2}-\d{2})?|priority(/\d+(/message)?)?|trips(/[\w-]+)?)$")
WRITES = {
    "POST": re.compile(r"/(mood|chat/stream|expenses/(repayments|refunds))$"),
    "PATCH": re.compile(r"/(expenses/\d+/category|statements/\d+/paid)$"),
    "PUT": re.compile(r"/(money-profile|budgets/[a-z0-9_]+)$"),
    "DELETE": re.compile(r"/(budgets/[a-z0-9_]+|expenses/(repayments|refunds)/\d+)$"),
}


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        # Do not log request bodies, personal records, tokens, or query strings.
        print(f"{self.address_string()} {self.command} {self.path.split('?')[0]} {args[1] if len(args) > 1 else ''}", flush=True)

    def headers_for(self, code, mime, length=None):
        self.send_response(code)
        self.send_header("Content-Type", mime)
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self'; connect-src 'self'; object-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'")
        if length is not None:
            self.send_header("Content-Length", str(length))
        self.end_headers()

    def reply(self, code, value):
        data = json.dumps(value).encode()
        self.headers_for(code, "application/json; charset=utf-8", len(data))
        self.wfile.write(data)

    def valid_host(self):
        host = self.headers.get("Host", "").split(":")[0].lower()
        return host in self.server.allowed_hosts

    def do_GET(self):
        if not self.valid_host():
            return self.reply(403, {"detail": "Unrecognized host"})
        path = urllib.parse.urlsplit(self.path).path
        if path.startswith("/api/"):
            return self.proxy()
        if path == "/web/money-sync":
            return self.reply(200, self.server.money_sync)
        files = {"/": "index.html", "/index.html": "index.html", "/app.js": "app.js", "/logic.mjs": "logic.mjs", "/money.mjs": "money.mjs", "/money-logic.mjs": "money-logic.mjs", "/style.css": "style.css", "/money.css": "money.css", "/manifest.webmanifest": "manifest.webmanifest", "/icon.svg": "icon.svg", "/apple-touch-icon.png": "apple-touch-icon.png"}
        filename = files.get(path)
        if not filename:
            return self.reply(404, {"detail": "Not found"})
        data = (PUBLIC / filename).read_bytes()
        mime = mimetypes.guess_type(filename)[0] or "application/octet-stream"
        self.headers_for(200, mime, len(data))
        self.wfile.write(data)

    def do_POST(self):
        if not self.valid_host():
            return self.reply(403, {"detail": "Unrecognized host"})
        if self.path == "/web/money-sync":
            if self.command != "POST":
                return self.reply(405, {"detail": "Action unavailable"})
            if not self.same_origin():
                return self.reply(403, {"detail": "Open LifeOS directly before syncing"})
            if self.server.money_sync_lock.acquire(blocking=False):
                self.server.money_sync = {"running": True, "step": "Bank mail", "errors": []}
                threading.Thread(target=self.run_money_sync, daemon=True).start()
            return self.reply(200, self.server.money_sync)
        if not self.path.startswith("/api/"):
            return self.reply(404, {"detail": "Not found"})
        self.proxy()

    do_PATCH = do_POST
    do_PUT = do_POST
    do_DELETE = do_POST

    def same_origin(self):
        origin = urllib.parse.urlsplit(self.headers.get("Origin", ""))
        return origin.netloc == self.headers.get("Host") and origin.scheme in {"http", "https"} and self.headers.get("Sec-Fetch-Site") != "cross-site"

    def run_money_sync(self):
        try:
            for label, path in (("Bank mail", "/expenses/sync"), ("Card statements", "/statements/sync")):
                self.server.money_sync["step"] = label
                headers = {"Content-Type": "application/json"}
                if self.server.api_token:
                    headers["Authorization"] = "Bearer " + self.server.api_token
                try:
                    req = urllib.request.Request(self.server.backend + path, data=b"{}", headers=headers, method="POST")
                    with urllib.request.urlopen(req, timeout=240) as response:
                        result = json.loads(response.read())
                        self.server.money_sync[label] = result.get("message", "Updated")
                        if "deferred" in result.get("message", "").lower():
                            self.server.money_sync["errors"].append(label + " was deferred; try again")
                except (urllib.error.URLError, OSError, ValueError):
                    self.server.money_sync["errors"].append(label + " could not sync")
            self.server.money_sync["step"] = "Finished"
        finally:
            self.server.money_sync["running"] = False
            self.server.money_sync_lock.release()

    def proxy(self):
        parts = urllib.parse.urlsplit(self.path[4:])
        if self.headers.get("Sec-Fetch-Site") == "cross-site":
            return self.reply(403, {"detail": "Open LifeOS directly"})
        if self.command == "GET" and not READS.fullmatch(parts.path):
            return self.reply(404, {"detail": "Unavailable in the web app"})
        body = None
        if self.command != "GET":
            if not WRITES.get(self.command, re.compile(r"(?!)")).fullmatch(parts.path):
                return self.reply(405, {"detail": "Action unavailable"})
            if not self.same_origin():
                return self.reply(403, {"detail": "Open LifeOS directly before saving"})
            try:
                length = int(self.headers.get("Content-Length", "0"))
                if not 0 <= length <= 65536 or length == 0 and self.command != "DELETE":
                    raise ValueError()
                body = self.rfile.read(length)
                if body:
                    json.loads(body)
            except (ValueError, json.JSONDecodeError):
                return self.reply(400, {"detail": "Invalid request"})
        headers = {"Content-Type": "application/json", "Accept": "application/json"}
        if self.server.api_token:
            headers["Authorization"] = "Bearer " + self.server.api_token
        request = urllib.request.Request(self.server.backend + parts.path + ("?" + parts.query if parts.query else ""), data=body, headers=headers, method=self.command)
        started = False
        try:
            with urllib.request.urlopen(request, timeout=150 if parts.path == "/chat/stream" else 20) as response:
                stream = parts.path == "/chat/stream"
                if stream:
                    self.headers_for(response.status, "application/x-ndjson; charset=utf-8")
                    started = True
                    while chunk := response.read1(4096):
                        self.wfile.write(chunk)
                        self.wfile.flush()
                else:
                    data = response.read()
                    self.headers_for(response.status, "application/json; charset=utf-8", len(data))
                    self.wfile.write(data)
        except urllib.error.HTTPError as error:
            # Relay structured backend errors without proxy internals or credentials.
            data = error.read()
            self.headers_for(error.code, "application/json; charset=utf-8", len(data))
            self.wfile.write(data)
        except (BrokenPipeError, ConnectionResetError):
            pass
        except (urllib.error.URLError, TimeoutError, OSError):
            if not started:
                self.reply(502, {"detail": "LifeOS server is unavailable. Try again shortly."})


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8090)
    args = parser.parse_args()
    server = ThreadingHTTPServer((args.host, args.port), Handler)
    server.backend = os.environ.get("LIFEOS_BACKEND_URL", "http://192.168.3.30:8000").rstrip("/")
    server.api_token = os.environ.get("LIFEOS_API_TOKEN", "")
    server.money_sync = {"running": False, "step": "Not run yet", "errors": []}
    server.money_sync_lock = threading.Lock()
    server.allowed_hosts = set(os.environ.get("LIFEOS_ALLOWED_HOSTS", "192.168.3.20,raspberrypi,raspberrypi.local,localhost,127.0.0.1").lower().split(","))
    print(f"LifeOS web listening on {args.host}:{args.port}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
