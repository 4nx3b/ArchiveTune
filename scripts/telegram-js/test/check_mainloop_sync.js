/*
 * ArchiveTune (2026) — verifies that the JS main loop appended by
 * TgJsRuntime.MAIN_LOOP_JS (Kotlin) is byte-identical to
 * scripts/telegram-js/host/mainloop.js (used by the Node smoke test).
 *
 * Usage: node test/check_mainloop_sync.js   (exit 0 = in sync)
 */

'use strict'

const fs = require('fs')
const path = require('path')

const here = path.dirname(__filename)
const root = path.resolve(here, '../../..')
const kotlinFile = path.join(
  root,
  'app/src/main/kotlin/moe/rukamori/archivetune/telegram/TgJsRuntime.kt',
)
const mainloopFile = path.join(here, '../host/mainloop.js')

const kotlin = fs.readFileSync(kotlinFile, 'utf8')

// Extract the MAIN_LOOP_JS constant body: from `MAIN_LOOP_JS = "..."` up to
// the terminating `"`. Kotlin source pieces are string literals joined by
// `+`; unescape \n and rebuild.
const start = kotlin.indexOf('MAIN_LOOP_JS = "')
if (start < 0) {
  console.error('MAIN_LOOP_JS constant not found in TgJsRuntime.kt')
  process.exit(1)
}
let i = kotlin.indexOf('"', start + 'MAIN_LOOP_JS = '.length)
let body = ''
let pos = i + 1
for (;;) {
  const nextQuote = kotlin.indexOf('"', pos)
  if (nextQuote < 0) {
    console.error('unterminated MAIN_LOOP_JS constant')
    process.exit(1)
  }
  body += kotlin.slice(pos, nextQuote)
  // skip the closing quote; check what follows: `+` -> another piece, else end
  let after = nextQuote + 1
  while (kotlin[after] === ' ' || kotlin[after] === '\n') after++
  if (kotlin[after] === '+') {
    // jump to the next opening quote
    let open = after + 1
    while (kotlin[open] === ' ' || kotlin[open] === '\n') open++
    if (kotlin[open] !== '"') {
      console.error('unexpected MAIN_LOOP_JS constant structure')
      process.exit(1)
    }
    pos = open + 1
    continue
  }
  break
}

// Unescape the Kotlin string escapes used in the constant.
let kotlinJs = ''
for (let k = 0; k < body.length; k++) {
  if (body[k] === '\\') {
    const c = body[k + 1]
    if (c === 'n') {
      kotlinJs += '\n'
      k++
    } else if (c === '\\') {
      kotlinJs += '\\'
      k++
    } else {
      kotlinJs += c
      k++
    }
  } else {
    kotlinJs += body[k]
  }
}

const mainloopJs = fs.readFileSync(mainloopFile, 'utf8')

const norm = (s) => s.replace(/\r\n/g, '\n').replace(/\s+$/, '')

if (norm(kotlinJs) === norm(mainloopJs)) {
  console.log('mainloop.js is in sync with TgJsRuntime.MAIN_LOOP_JS')
  process.exit(0)
} else {
  console.error('MISMATCH between TgJsRuntime.MAIN_LOOP_JS and host/mainloop.js')
  console.error('--- kotlin constant ---')
  console.error(norm(kotlinJs))
  console.error('--- mainloop.js ---')
  console.error(norm(mainloopJs))
  process.exit(1)
}
