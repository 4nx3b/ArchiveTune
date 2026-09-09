#!/usr/bin/env python3
"""
State-machine brace/paren/bracket balance checker for Kotlin sources.

A naive regex/counter is unreliable on Kotlin: string templates ${...},
nested interpolation with strings inside templates, char literals '{',
line comments, block comments and KDoc all contain unbalanced delimiters.
This lexer tracks lexical state (with a proper return-stack for nested
string templates) and only counts delimiters in CODE regions. It is NOT a
full parser — it answers "is this file plausibly well-formed at the
delimiter level".

Usage: kotlin_balance_check.py FILE [FILE...]
Exit 0 if all files balanced, 1 otherwise.
"""

import sys

CODE = "code"
LINE_COMMENT = "line_comment"
BLOCK_COMMENT = "block_comment"
STRING = "string"
RAW_STRING = "raw_string"
CHAR = "char"

PAIRS = {")": "(", "]": "[", "}": "{"}
OPENS = set("([{")
STRINGY = (STRING, RAW_STRING)


class Lexer:
    def __init__(self, text: str):
        self.text = text
        self.i = 0
        self.n = len(text)
        self.state = CODE
        # states to return to when a template's '}' closes; each entry:
        # (delim_stack_depth_at_marker, return_state)
        self.template_returns = []
        self.delim_stack = []  # (char, line)
        self.errors = []
        self.line = 1

    def error(self, msg):
        self.errors.append(f"line {self.line}: {msg}")

    def run(self):
        text, n = self.text, self.n
        while self.i < n:
            ch = text[self.i]
            nxt = text[self.i + 1] if self.i + 1 < n else ""
            if ch == "\n":
                self.line += 1

            if self.state == CODE:
                if ch == "/" and nxt == "/":
                    self.state = LINE_COMMENT
                    self.i += 2
                    continue
                if ch == "/" and nxt == "*":
                    self.state = BLOCK_COMMENT
                    self.i += 2
                    continue
                if text[self.i : self.i + 3] == '"""':
                    self.state = RAW_STRING
                    self.i += 3
                    continue
                if ch == '"':
                    self.state = STRING
                    self.i += 1
                    continue
                if ch == "'":
                    self.state = CHAR
                    self.i += 1
                    continue
                if ch in OPENS:
                    self.delim_stack.append((ch, self.line))
                    self.i += 1
                    continue
                if ch in PAIRS:
                    if not self.delim_stack:
                        self.error(f"unmatched '{ch}'")
                    else:
                        top, top_line = self.delim_stack.pop()
                        if top != PAIRS[ch]:
                            self.error(
                                f"'{ch}' closes '{top}' opened line {top_line}"
                            )
                    # After popping, check whether this '}' closed a string
                    # template — if so, return to the enclosing string state.
                    if ch == "}":
                        while self.template_returns:
                            depth, ret = self.template_returns[-1]
                            if depth == len(self.delim_stack):
                                self.template_returns.pop()
                                self.state = ret
                                break
                            if depth > len(self.delim_stack):
                                # malformed nesting; drop stale marker
                                self.template_returns.pop()
                                continue
                            break
                    self.i += 1
                    continue
                self.i += 1
                continue

            if self.state == LINE_COMMENT:
                if ch == "\n":
                    self.state = CODE
                self.i += 1
                continue

            if self.state == BLOCK_COMMENT:
                if ch == "*" and nxt == "/":
                    self.state = CODE
                    self.i += 2
                    continue
                self.i += 1
                continue

            if self.state == STRING:
                if ch == "\\":
                    self.i += 2
                    continue
                if ch == '"':
                    self.state = CODE
                    self.i += 1
                    continue
                if ch == "$" and nxt == "{":
                    # marker records the stack depth to RETURN TO once the
                    # template's brace pops (i.e. depth before pushing it).
                    self.template_returns.append((len(self.delim_stack), STRING))
                    self.delim_stack.append(("{", self.line))
                    self.state = CODE
                    self.i += 2
                    continue
                self.i += 1
                continue

            if self.state == RAW_STRING:
                if text[self.i : self.i + 3] == '"""':
                    self.state = CODE
                    self.i += 3
                    continue
                if ch == "$" and nxt == "{":
                    self.template_returns.append((len(self.delim_stack), RAW_STRING))
                    self.delim_stack.append(("{", self.line))
                    self.state = CODE
                    self.i += 2
                    continue
                self.i += 1
                continue

            if self.state == CHAR:
                if ch == "\\":
                    self.i += 2
                    continue
                if ch == "'":
                    self.state = CODE
                self.i += 1
                continue

        if self.state == STRING:
            self.error("unterminated string literal")
        if self.state == RAW_STRING:
            self.error("unterminated raw string")
        if self.state == CHAR:
            self.error("unterminated char literal")
        if self.state not in (CODE,):
            self.error(f"ended in lexical state {self.state}")
        for top, top_line in self.delim_stack:
            self.error(f"unclosed '{top}' from line {top_line}")
        return self.errors


def check(path: str) -> bool:
    try:
        with open(path, "r", encoding="utf-8") as fh:
            text = fh.read()
    except OSError as exc:
        print(f"FAIL {path}: cannot read ({exc})")
        return False
    lexer = Lexer(text)
    errors = lexer.run()
    if errors:
        for err in errors[:10]:
            print(f"FAIL {path}: {err}")
        return False
    print(f"OK   {path}")
    return True


def main() -> int:
    files = sys.argv[1:]
    if not files:
        print("usage: kotlin_balance_check.py FILE [FILE...]")
        return 2
    results = [check(f) for f in files]
    return 0 if all(results) else 1


if __name__ == "__main__":
    sys.exit(main())
