#!/usr/bin/env python3
"""Apply ONLY the unused-import removal from cleanup_pass.py (no comment
stripping — the codebase's remaining comments are recent, purposeful
invariant documentation from the last sessions and must survive)."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import cleanup_pass as cp

BASE = cp.BASE
changed = 0
removed = 0
for root, _, files in os.walk(BASE):
    for fname in files:
        if not fname.endswith(".kt"):
            continue
        path = os.path.join(root, fname)
        src = open(path, encoding="utf-8").read()
        orig = src
        for simple, alias, name in cp.unused_imports(src, fname):
            src = cp.remove_import(src, name)
            removed += 1
        if src != orig:
            changed += 1
            open(path, "w", encoding="utf-8").write(src)
print(f"removed {removed} imports across {changed} files")
