/*
 * ArchiveTune (2026) — AES-IGE contract test.
 *
 * TgJsCrypto.aesIge (Kotlin) and test/real_network.js's aesIge (Node mirror)
 * implement the same MTProto AES-IGE. This test pins that algorithm against
 * mtcute's REFERENCE implementation (@mtcute/wasm ige256Encrypt/Decrypt) on
 * random vectors, so a chaining-state regression can never land again.
 *
 * History: the original implementation updated the IGE state to the bare ECB
 * result (before the outer XOR) and used the encrypt XOR pattern for decrypt
 * too — only the first 16-byte block of every buffer was correct, which
 * silently corrupted req_DH_params and every encrypted MTProto message.
 *
 * Run: node test/ige_reference.js   (requires `npm install` for @mtcute/wasm)
 */
'use strict'

const { readFileSync } = require('node:fs')
const { join, dirname } = require('node:path')
const nodeCrypto = require('node:crypto')

const wasm = require('@mtcute/wasm')
wasm.initSync(readFileSync(join(__dirname, '../node_modules/@mtcute/wasm/mtcute.wasm')))

function b(i8arr) {
  if (i8arr == null) return Buffer.alloc(0)
  if (Buffer.isBuffer(i8arr)) return i8arr
  const u8 = new Uint8Array(i8arr.buffer, i8arr.byteOffset, i8arr.byteLength)
  return Buffer.from(u8)
}

// The Node mirror of TgJsCrypto.aesIge — keep byte-for-byte semantics in sync
// with app/src/main/kotlin/.../telegram/TgJsCrypto.kt.
function aesIge(encrypt, key, iv, data) {
  if (data.length === 0) return data
  if (data.length % 16 !== 0) throw new Error('AES-IGE input must be a multiple of 16 bytes')
  let ivX = iv.slice(0, 16) // c_{i-1}
  let ivY = iv.slice(16, 32) // p_{i-1}
  const ecb = encrypt
    ? nodeCrypto.createCipheriv('aes-256-ecb', key, null)
    : nodeCrypto.createDecipheriv('aes-256-ecb', key, null)
  ecb.setAutoPadding(false)
  const d = b(data)
  const out = Buffer.alloc(d.length)
  for (let off = 0; off < d.length; off += 16) {
    const block = d.slice(off, off + 16)
    if (encrypt) {
      const xored = Buffer.alloc(16)
      for (let i = 0; i < 16; i++) xored[i] = block[i] ^ ivX[i]
      const e = ecb.update(xored)
      const c = Buffer.alloc(16)
      for (let i = 0; i < 16; i++) c[i] = e[i] ^ ivY[i]
      c.copy(out, off)
      ivX = c
      ivY = Buffer.from(block)
    } else {
      const xored = Buffer.alloc(16)
      for (let i = 0; i < 16; i++) xored[i] = block[i] ^ ivY[i]
      const dec = ecb.update(xored)
      const p = Buffer.alloc(16)
      for (let i = 0; i < 16; i++) p[i] = dec[i] ^ ivX[i]
      p.copy(out, off)
      ivY = p
      ivX = Buffer.from(block)
    }
  }
  return out
}

let passed = 0
let failed = 0

function check(name, ok, detail) {
  if (ok) {
    passed++
    console.log('PASS', name)
  } else {
    failed++
    console.log('FAIL', name, detail ?? '')
  }
}

// 1. random multi-block vectors vs the reference wasm implementation
for (let iter = 0; iter < 8; iter++) {
  const key = nodeCrypto.randomBytes(32)
  const iv = nodeCrypto.randomBytes(32)
  const blocks = 1 + Math.floor(Math.random() * 20)
  const data = nodeCrypto.randomBytes(blocks * 16)

  const mineEnc = Buffer.from(aesIge(true, key, iv, data))
  const refEnc = Buffer.from(wasm.ige256Encrypt(data, key, iv))
  check(`encrypt matches wasm (${blocks} blocks)`, mineEnc.equals(refEnc),
    `mine=${mineEnc.toString('hex').slice(0, 32)} ref=${refEnc.toString('hex').slice(0, 32)}`)

  const mineDec = Buffer.from(aesIge(false, key, iv, refEnc))
  const refDec = Buffer.from(wasm.ige256Decrypt(refEnc, key, iv))
  check(`decrypt matches wasm (${blocks} blocks)`, mineDec.equals(refDec))
  check(`decrypt(encrypt(x)) == x (${blocks} blocks)`, mineDec.equals(Buffer.from(data)))
}

// 2. rsaPad-shaped case: 32-byte ZERO IV, 224-byte data
{
  const key = nodeCrypto.randomBytes(32)
  const iv = Buffer.alloc(32)
  const data = nodeCrypto.randomBytes(224)
  const mine = Buffer.from(aesIge(true, key, iv, data))
  const ref = Buffer.from(wasm.ige256Encrypt(data, key, iv))
  check('encrypt with zero 32B IV (rsaPad shape) matches wasm', mine.equals(ref))
}

// 3. cross-compatibility: node encrypt -> wasm decrypt, wasm encrypt -> node decrypt
{
  const key = nodeCrypto.randomBytes(32)
  const iv = nodeCrypto.randomBytes(32)
  const data = nodeCrypto.randomBytes(320)
  const enc = aesIge(true, key, iv, data)
  const dec = wasm.ige256Decrypt(enc, key, iv)
  check('wasm decrypts node-encrypted payload', Buffer.from(dec).equals(Buffer.from(data)))

  const enc2 = wasm.ige256Encrypt(data, key, iv)
  const dec2 = aesIge(false, key, iv, enc2)
  check('node decrypts wasm-encrypted payload', Buffer.from(dec2).equals(Buffer.from(data)))
}

// 4. regression shape: the OLD buggy implementation only matched the first block.
//    Assert divergence beyond block 0 is impossible now.
{
  const key = nodeCrypto.randomBytes(32)
  const iv = nodeCrypto.randomBytes(32)
  const data = nodeCrypto.randomBytes(128)
  const mine = aesIge(true, key, iv, data)
  const ref = wasm.ige256Encrypt(data, key, iv)
  const tailEqual = Buffer.from(mine.subarray(16)).equals(Buffer.from(ref.subarray(16)))
  check('blocks 2..8 match (old bug diverged from block 2)', tailEqual)
}

if (failed > 0) {
  console.log(`\n${failed} FAILED, ${passed} passed`)
  process.exit(1)
}
console.log(`\nall ${passed} IGE reference checks passed`)
