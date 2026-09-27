#!/usr/bin/env python3
"""Convert the uniform `topBar = { TopAppBar(FrostedHeaderPill...) }` boilerplate
in Settings sub-pages to the reference-style `SettingsPageTopBar(titleText = ...)`.

Conservative: only converts blocks that match the exact uniform shape
(TopAppBar + empty title + FrostedHeaderPill(plain=true) nav icon + colors,
nothing else). Anything else is reported and left untouched.
"""
import os
import re
import sys

BASE = "/home/z/my-project/ArchiveTune/app/src/main/kotlin/moe/rukamori/archivetune/ui/screens/settings"

IMPORT_LINE = "import moe.rukamori.archivetune.ui.component.SettingsPageTopBar"


def match_brace(src, open_idx):
    """Return index of the closing brace for the '{' at open_idx."""
    depth = 0
    i = open_idx
    n = len(src)
    while i < n:
        c = src[i]
        if c == '"':
            # skip string literal
            i += 1
            while i < n and src[i] != '"':
                if src[i] == '\\':
                    i += 1
                i += 1
        elif c == "'":
            i += 1
            while i < n and src[i] != "'":
                if src[i] == '\\':
                    i += 1
                i += 1
        elif c == '{':
            depth += 1
        elif c == '}':
            depth -= 1
            if depth == 0:
                return i
        i += 1
    return -1


def match_paren(src, open_idx):
    """Return index of the closing paren for the '(' at open_idx."""
    depth = 0
    i = open_idx
    n = len(src)
    while i < n:
        c = src[i]
        if c == '"':
            i += 1
            while i < n and src[i] != '"':
                if src[i] == '\\':
                    i += 1
                i += 1
        elif c == '{':
            j = match_brace(src, i)
            if j < 0:
                return -1
            i = j
        elif c == '(':
            depth += 1
        elif c == ')':
            depth -= 1
            if depth == 0:
                return i
        i += 1
    return -1


def extract_title_expr(block):
    """Find the Text(...) inside the FrostedHeaderPill block and return the
    `text = <expr>` expression (or the first positional argument)."""
    m = re.search(r'\bText\s*\(', block)
    if not m:
        return None
    open_idx = m.end() - 1
    close_idx = match_paren(block, open_idx)
    if close_idx < 0:
        return None
    args = block[open_idx + 1:close_idx]

    # named `text = ...` at depth 0
    depth = 0
    i = 0
    while i < len(args):
        c = args[i]
        if c in '([':
            depth += 1
        elif c in ')]':
            depth -= 1
        elif c == '"' and depth == 0:
            # skip string
            i += 1
            while i < len(args) and args[i] != '"':
                if args[i] == '\\':
                    i += 1
                i += 1
        elif depth == 0 and args[i:i + 7] == 'text = ':
            j = i + 7
            d = 0
            k = j
            while k < len(args):
                ch = args[k]
                if ch in '([{':
                    d += 1
                elif ch in ')]}':
                    if d == 0:
                        break
                    d -= 1
                elif ch == '"' and d == 0:
                    k += 1
                    while k < len(args) and args[k] != '"':
                        if args[k] == '\\':
                            k += 1
                        k += 1
                elif ch == ',' and d == 0:
                    break
                k += 1
            expr = args[j:k].strip()
            return expr if expr else None
        i += 1

    # positional first argument (e.g. Text(stringResource(...)))
    d = 0
    k = 0
    while k < len(args):
        ch = args[k]
        if ch in '([{':
            d += 1
        elif ch in ')]}':
            if d == 0:
                break
            d -= 1
        elif ch == ',' and d == 0:
            break
        elif ch == '"' and d == 0:
            k += 1
            while k < len(args) and args[k] != '"':
                if args[k] == '\\':
                    k += 1
                k += 1
        k += 1
    expr = args[:k].strip()
    return expr if expr and not expr.startswith('@') else None


def convert_file(path):
    src = open(path, encoding="utf-8").read()
    original = src
    converted_blocks = 0

    while True:
        m = re.search(r'topBar\s*=\s*\{', src)
        if not m:
            break
        open_idx = m.end() - 1
        close_idx = match_brace(src, open_idx)
        if close_idx < 0:
            break
        block = src[open_idx + 1:close_idx]

        # Only the uniform pattern.
        if 'FrostedHeaderPill(plain = true)' not in block:
            break
        if re.search(r'\bactions\s*=', block):
            break
        if 'TopAppBar(' not in block:
            break

        title_expr = extract_title_expr(block)
        if title_expr is None:
            break

        replacement = (
            'topBar = {\n'
            '                SettingsPageTopBar(\n'
            f'                    titleText = {title_expr},\n'
            '                    onBack = navController::navigateUp,\n'
            '                    onBackLongClick = navController::backToMain,\n'
            '                )\n'
            '            }'
        )
        src = src[:m.start()] + replacement + src[close_idx + 1:]
        converted_blocks += 1

    if src == original:
        return 0, False

    # Ensure the import exists.
    if IMPORT_LINE not in src:
        imports = list(re.finditer(r'^import .+$', src, re.M))
        if imports:
            last = imports[-1]
            src = src[:last.end()] + '\n' + IMPORT_LINE + src[last.end():]
        else:
            pkg = re.search(r'^package .+$', src, re.M)
            src = src[:pkg.end()] + '\n\n' + IMPORT_LINE + src[pkg.end():]

    open(path, "w", encoding="utf-8").write(src)
    return converted_blocks, True


def main():
    total_files = 0
    total_blocks = 0
    skipped = []
    for root, _, files in os.walk(BASE):
        for fname in sorted(files):
            if not fname.endswith(".kt"):
                continue
            path = os.path.join(root, fname)
            text = open(path, encoding="utf-8").read()
            if 'FrostedHeaderPill(plain = true)' not in text:
                continue
            blocks, changed = convert_file(path)
            if changed:
                total_files += 1
                total_blocks += blocks
                print(f"converted {blocks}: {fname}")
            else:
                skipped.append(fname)
    print(f"\nTOTAL: {total_blocks} blocks in {total_files} files")
    if skipped:
        print("SKIPPED (manual review):")
        for s in skipped:
            print("  -", s)


if __name__ == "__main__":
    main()
