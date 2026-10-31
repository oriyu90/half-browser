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


FEATURES_HTML = """<!doctype html>
<html lang="ja"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Browser feature test</title>
<style>body{font-family:sans-serif;margin:1.2rem;line-height:1.7}
section{border:1px solid #ccc;border-radius:.6rem;padding:.8rem;margin:.8rem 0}
button{padding:.5rem .9rem;margin:.2rem;font-size:1rem}
#log{background:#f3f3f3;padding:.6rem;white-space:pre-wrap;border-radius:.4rem}
</style></head><body>
<h1>Browser feature test</h1>
<p>UPDATE-REV-6</p>
<pre id="diag">diagnostics...</pre>
<script>
document.getElementById('diag').textContent = [
  'Notification: ' + (typeof Notification),
  'serviceWorker: ' + ('serviceWorker' in navigator),
  'PushManager: ' + ('PushManager' in window),
  'isSecureContext: ' + window.isSecureContext,
  'mediaDevices: ' + !!(navigator.mediaDevices && navigator.mediaDevices.getUserMedia),
  'geolocation: ' + !!navigator.geolocation
].join(String.fromCharCode(10));
</script>

<section><h2>1. File attach</h2>
<input id="file" type="file" accept="image/*">
<p><button onclick="document.getElementById('file').click()">Choose image</button></p>

</section><section><h2>2. Camera</h2>
<button onclick="startCam()">Start camera</button>
<video id="cam" autoplay playsinline style="max-width:100%;background:#000"></video>

</section><section><h2>3. External app</h2>
<a href="mailto:test@example.com?subject=Half-Browser">mailto: link</a><br>
<a href="tel:+81312345678">tel: link</a>

</section><section><h2>4. New window / target=_blank</h2>
<a href="/second" target="_blank">Open /second in a new window</a>

</section><section><h2>5. JavaScript dialogs</h2>
<button onclick="alert('alert works')">alert</button>
<button onclick="confirm('confirm works?')">confirm</button>
<button onclick="prompt('prompt works?','default')">prompt</button>

</section><section><h2>6. Geolocation</h2>
<button onclick="geo()">Get location</button>

</section><section><h2>7. Download</h2>
<a href="/sample.txt">Download sample.txt</a>

</section><section><h2>8. Notifications API</h2>
<button onclick="notify()">new Notification()</button>

</section><section><h2>9. Video fullscreen</h2>
<video id="video" controls playsinline style="max-width:100%"
 src="https://interactive-examples.mdn.mozilla.net/media/cc0-videos/flower.mp4"></video>

</section>
<h2>Log</h2><pre id="log">ready</pre>
<script>
function log(s){document.getElementById('log').textContent += String.fromCharCode(10)+s;}
async function startCam(){
  try{
    const s = await navigator.mediaDevices.getUserMedia({video:true,audio:false});
    document.getElementById('cam').srcObject = s; log('camera: granted');
  }catch(e){ log('camera: '+e.name+' '+e.message); }
}
function geo(){
  if(!navigator.geolocation){ log('geolocation: unsupported'); return; }
  navigator.geolocation.getCurrentPosition(
    p=>log('geo: '+p.coords.latitude+','+p.coords.longitude),
    e=>log('geo error: '+e.code+' '+e.message));
}
function notify(){
  log('Notification: ' + (typeof Notification));
  if(typeof Notification === 'undefined'){ return; }
  try{ new Notification('Test from site',{body:'body'}); log('notification requested'); }
  catch(e){ log('notification error: '+e.message); }
}
</script>
</body></html>"""


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
        if path == "/features":
            self._send_html(FEATURES_HTML)
            return
        if path == "/sample.txt":
            self.send_response(200)
            self.send_header("Content-Type", "text/plain; charset=utf-8")
            self.send_header("Content-Disposition", "attachment; filename=sample.txt")
            self.end_headers()
            self.wfile.write(b"Half-Browser download test\n")
            return
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

    def _send_html(self, html: str):
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.end_headers()
        self.wfile.write(html.encode("utf-8"))

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
