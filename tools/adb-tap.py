#!/usr/bin/env python3
"""Tap the centre of the first UI node whose text/content-desc matches.

Usage: tools/adb-tap.py "Always trust this site" [--contains]
"""
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

ADB = os.environ.get("ADB", "adb")


def dump() -> str:
    subprocess.run([ADB, "shell", "uiautomator", "dump", "/sdcard/ui.xml"],
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
    return subprocess.check_output([ADB, "shell", "cat", "/sdcard/ui.xml"]).decode("utf-8", "replace")


def main() -> int:
    target = sys.argv[1]
    contains = "--contains" in sys.argv[2:]
    pick_last = "--last" in sys.argv[2:]
    root = ET.fromstring(dump())
    matches = []
    for node in root.iter("node"):
        text = node.get("text") or ""
        desc = node.get("content-desc") or ""
        hay = text + "|" + desc
        hit = target in hay if contains else target in (text, desc)
        if not hit:
            continue
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds") or "")
        if not m:
            continue
        matches.append((text or desc, tuple(map(int, m.groups()))))
    if not matches:
        print(f"not found: {target}")
        return 1
    text, (x1, y1, x2, y2) = matches[-1] if pick_last else matches[0]
    x, y = (x1 + x2) // 2, (y1 + y2) // 2
    subprocess.run([ADB, "shell", "input", "tap", str(x), str(y)], check=False)
    print(f"tapped '{text}' at {x},{y}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
