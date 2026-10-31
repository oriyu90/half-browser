#!/usr/bin/env python3
"""Local HTTPS + HTTP Basic auth server used to QA Half-Browser.

Deliberately uses a self-signed certificate and Basic auth so the browser's
certificate interstitial and credential saving can be exercised end to end.
"""
import argparse
import base64
import html
import ssl
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

USER = "halfuser"
PASSWORD = "halfpass"
REALM = "Half-Browser QA"


class Handler(BaseHTTPRequestHandler):
    server_version = "HalfBrowserQA/1.0"

    def do_GET(self):
        if not self._authorized():
            self.send_response(401)
            self.send_header("WWW-Authenticate", f'Basic realm="{REALM}", charset="UTF-8"')
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.end_headers()
            self.wfile.write(b"<h1>401 Unauthorized</h1>")
            return
        path = self.path.split("?")[0]
        if path == "/favicon.ico":
            self.send_response(204)
            self.end_headers()
            return
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.end_headers()
        body = f"""<!doctype html>
<html lang="ja"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Half-Browser QA {html.escape(path)}</title>
<style>body{{font-family:sans-serif;margin:2rem;line-height:1.6}}
code{{background:#eee;padding:.2rem .4rem;border-radius:.3rem}}</style>
</head><body>
<h1>Local HTTPS server</h1>
<p>This page is served with a <strong>self-signed certificate</strong> and
<strong>HTTP Basic authentication</strong>.</p>
<p>Path: <code>{html.escape(path)}</code></p>
<p>User: <code>{USER}</code> / Realm: <code>{REALM}</code></p>
<p><a href="/">Home</a> · <a href="/second">Second page</a> ·
<a href="/dummy">Another page</a></p>
<p id="marker">QA-OK</p>
</body></html>"""
        self.wfile.write(body.encode("utf-8"))

    def _authorized(self):
        header = self.headers.get("Authorization", "")
        if not header.startswith("Basic "):
            return False
        try:
            decoded = base64.b64decode(header[6:]).decode("utf-8")
        except Exception:
            return False
        return decoded == f"{USER}:{PASSWORD}"

    def log_message(self, fmt, *args):
        sys.stderr.write("%s - %s\n" % (self.address_string(), fmt % args))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=8844)
    parser.add_argument("--bind", default="0.0.0.0")
    parser.add_argument("--cert", default="cert.pem")
    parser.add_argument("--key", default="key.pem")
    args = parser.parse_args()
    httpd = ThreadingHTTPServer((args.bind, args.port), Handler)
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.load_cert_chain(certfile=args.cert, keyfile=args.key)
    httpd.socket = ctx.wrap_socket(httpd.socket, server_side=True)
    print(f"HTTPS QA server on https://{args.bind}:{args.port} (Basic {USER}/{PASSWORD})", flush=True)
    httpd.serve_forever()


if __name__ == "__main__":
    main()
