#!/usr/bin/env python3
"""Syntax-check the inline <script> in index.html and worker/worker.js. Needs Node.js.
Usage: python3 tools/check_js.py"""
import pathlib, re, shutil, subprocess, sys, tempfile

root = pathlib.Path(__file__).resolve().parent.parent
if not shutil.which("node"):
    sys.exit("Node.js is required (node --check).")

def check(name, code, suffix):
    with tempfile.NamedTemporaryFile("w", suffix=suffix, delete=False, encoding="utf-8") as f:
        f.write(code)
    r = subprocess.run(["node", "--check", f.name], capture_output=True, text=True)
    print(("OK   " if r.returncode == 0 else "FAIL ") + name)
    if r.returncode:
        print(r.stderr)
    return r.returncode == 0

html = (root / "index.html").read_text(encoding="utf-8")
inline = re.findall(r"<script>(.*?)</script>", html, re.S)
ok = check("index.html (inline script)", inline[-1], ".js")
ok &= check("worker/worker.js", (root / "worker" / "worker.js").read_text(encoding="utf-8"), ".mjs")
sys.exit(0 if ok else 1)
