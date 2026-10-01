#!/usr/bin/env python3
"""Report private top-level/class members that are never referenced elsewhere in the same file."""
import os
import re
import sys

BASE = "/home/z/my-project/ArchiveTune/app/src/main/kotlin/moe/rukamori/archivetune"

def find_private_declarations(src):
    decls = []
    for m in re.finditer(r'^\s*(?:@\w+(?:\([^)]*\))?\s+)*private\s+(?:const\s+)?(?:val|var|fun)\s+(\w+)', src, re.M):
        decls.append(m.group(1))
    return decls

def main():
    hits = []
    for root, _, files in os.walk(BASE):
        for fname in files:
            if not fname.endswith(".kt"):
                continue
            path = os.path.join(root, fname)
            src = open(path, encoding="utf-8").read()
            for name in find_private_declarations(src):
                # count references outside the declaration line
                refs = len(re.findall(r'\b' + re.escape(name) + r'\b', src))
                if refs <= 1:
                    hits.append((path, name))
    for p, n in hits:
        print(f"{p}: private '{n}' unreferenced")

if __name__ == "__main__":
    main()
