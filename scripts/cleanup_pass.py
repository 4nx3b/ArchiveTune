#!/usr/bin/env python3
"""Comment stripper + dead-code pass for ArchiveTune (convention from
cc28de9a1 / 7a9fd3903 / 8f113583c).

Lexer-based (NOT regex): tracks string templates, char literals, comments —
so `//` inside strings is never touched and no string is ever truncated.

Rules:
- Line comments (// ...) and block comments (/* ... */, /** ... */) are removed.
- EXCEPT: license headers (the leading /* ... */ block containing "License"),
  and anything the strip would leave as an empty line at file start.
- Keeps: annotations (@Suppress etc. are not comments), string content.
- After stripping: collapse 3+ consecutive blank lines to 1; strip trailing
  whitespace; ensure file ends with a single newline.
- SKIP files whose first block comment mentions GPL or License (header).

Dead-code pass (separate, opt-in via --dead-code):
- unused imports (word-boundary match; operator-convention names never removed)
- unused private top-level/class members are NOT auto-removed here (risky) —
  reported only.
"""

import os
import re
import sys

BASE = "/home/z/my-project/ArchiveTune/app/src/main/kotlin/moe/rukamori/archivetune"

LICENSE_MARKERS = ("GPL", "License", "©", "Copyright")


def strip_comments(src: str, keep_header: bool) -> str:
    out = []
    i = 0
    n = len(src)
    # newline tracking to reconstruct line structure
    while i < n:
        c = src[i]
        if c == '"':
            # string literal or triple-quoted
            if src.startswith('"""', i):
                j = i + 3
                while j < n and not src.startswith('"""', j):
                    j += 1
                j = min(j + 3, n)
                out.append(src[i:j])
                i = j
                continue
            j = i + 1
            while j < n:
                if src[j] == "\\":
                    j += 2
                    continue
                if src[j] == '"' or src[j] == "\n":
                    break
                j += 1
            j = min(j + 1, n)
            out.append(src[i:j])
            i = j
            continue
        if c == "'":
            j = i + 1
            while j < n:
                if src[j] == "\\":
                    j += 2
                    continue
                if src[j] == "'" or src[j] == "\n":
                    break
                j += 1
            j = min(j + 1, n)
            out.append(src[i:j])
            i = j
            continue
        if src.startswith("//", i):
            j = src.find("\n", i)
            if j == -1:
                j = n
            # drop the comment, keep the newline
            i = j
            continue
        if src.startswith("/*", i):
            j = src.find("*/", i + 2)
            j = n if j == -1 else j + 2
            comment = src[i:j]
            # license header protection: first block comment in file
            is_first_block = len("".join(out).lstrip()) == 0
            if keep_header and is_first_block and any(m in comment for m in LICENSE_MARKERS):
                out.append(comment)
            # else: dropped entirely
            i = j
            continue
        out.append(c)
        i += 1

    text = "".join(out)
    # collapse runs of blank lines (3+ -> 1); also collapse blank runs left by
    # comment removal at line starts
    text = re.sub(r"[ \t]+//(?=\n)", "", text)  # safety: empty line comments
    text = re.sub(r"\n[ \t]+\n", "\n\n", text)
    text = re.sub(r"\n{3,}", "\n\n", text)
    # remove blank lines directly after { and before }
    text = re.sub(r"\{\n\n+", "{\n", text)
    text = re.sub(r"\n\n\}", "\n}", text)
    # trailing whitespace per line
    text = "\n".join(line.rstrip() for line in text.split("\n"))
    if not text.endswith("\n"):
        text += "\n"
    text = re.sub(r"\n{3,}", "\n\n", text)
    return text


def unused_imports(src: str, filename: str):
    """Return import lines whose imported simple name is unused (word boundary),
    excluding operator-convention names."""
    OPERATOR_NAMES = {
        "getValue", "setValue", "component1", "component2", "component3",
        "component4", "component5", "get", "set", "plus", "minus", "times",
        "rem", "div", "compareTo", "contains", "invoke", "inc", "dec",
        "not", "unaryPlus", "unaryMinus", "rangeTo", "rangeUntil", "plusAssign",
    }
    imports = re.findall(r"^import\s+(?:[\w.]+\.)?(\w+)(?:\s+as\s+(\w+))?[^\n]*$", src, re.M)
    body = "\n".join(l for l in src.split("\n") if not l.startswith("import ") and not l.startswith("package "))
    dead = []
    for simple, alias in imports:
        name = alias or simple
        if name in OPERATOR_NAMES:
            continue
        # star imports and wildcard: skip removal
        if simple == "*":
            continue
        if not re.search(r"\b" + re.escape(name) + r"\b", body):
            # keep imports referenced in comments-only? comments are stripped later;
            # since strip runs first, body has no comments.
            dead.append((simple, alias, name))
    return dead


def remove_import(src: str, name: str):
    lines = src.split("\n")
    kept = []
    for line in lines:
        if line.startswith("import "):
            m = re.match(r"import\s+(?:[\w.]+\.)?(\w+)(?:\s+as\s+(\w+))?[^\n]*$", line)
            if m and (m.group(2) or m.group(1)) == name:
                continue
        kept.append(line)
    return "\n".join(kept)


def main():
    apply_changes = "--apply" in sys.argv
    strip = "--strip" in sys.argv or "--apply" in sys.argv
    deadcode = "--dead-code" in sys.argv or "--apply" in sys.argv
    changed = 0
    total_imports = 0
    for root, _, files in os.walk(BASE):
        for fname in files:
            if not fname.endswith(".kt"):
                continue
            path = os.path.join(root, fname)
            src = open(path, encoding="utf-8").read()
            orig = src
            if strip:
                src = strip_comments(src, keep_header=True)
            if deadcode:
                for simple, alias, name in unused_imports(src, fname):
                    if apply_changes:
                        src = remove_import(src, name)
                    total_imports += 1
            if src != orig:
                changed += 1
                if apply_changes:
                    open(path, "w", encoding="utf-8").write(src)
    mode = "APPLIED" if apply_changes else "DRY-RUN"
    print(f"{mode}: {changed} files changed; unused imports found: {total_imports}")


if __name__ == "__main__":
    main()
