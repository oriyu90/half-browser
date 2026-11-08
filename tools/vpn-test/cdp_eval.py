#!/usr/bin/env python3
"""Drive the attack test page through Chrome DevTools Protocol."""
import asyncio
import json
import sys
import urllib.request

import websockets


async def main(expr: str):
    with urllib.request.urlopen("http://127.0.0.1:9223/json/list", timeout=5) as r:
        pages = json.loads(r.read())
    ws_url = pages[0]["webSocketDebuggerUrl"]
    async with websockets.connect(ws_url, max_size=1 << 24) as ws:
        await ws.send(json.dumps({
            "id": 1,
            "method": "Runtime.evaluate",
            "params": {
                "expression": expr,
                "returnByValue": True,
                "awaitPromise": False,
            },
        }))
        while True:
            msg = json.loads(await ws.recv())
            if msg.get("id") == 1:
                result = msg.get("result", {}).get("result", {})
                print(json.dumps(result.get("value", result), ensure_ascii=False))
                if "exceptionDetails" in msg.get("result", {}):
                    print("EXCEPTION:", json.dumps(msg["result"]["exceptionDetails"])[:300])
                break


if __name__ == "__main__":
    asyncio.run(main(sys.argv[1]))
