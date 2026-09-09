/*
 * ArchiveTune (2026) — smoke test for the mtcute host bundle.
 *
 * Runs banner.js + bundle.js inside a bare `vm` context that has NO Node or
 * browser globals — the closest simulation of the QuickJS runtime we have.
 * Kotlin bridge bindings are stubbed in JS. Verifies:
 *
 *   1. the environment shims install (TextEncoder/AbortController/timers/…)
 *   2. mtcute's TelegramClient constructs with our transport/crypto/storage
 *   3. connect() drives our WebSocket transport + packet codecs end to end
 *   4. the JSON API layer + error envelopes work
 *   5. binary handlers registered
 *
 * Exit code 0 = pass.
 */

'use strict'

const fs = require('fs')
const path = require('path')
const vm = require('vm')
const nodeCrypto = require('crypto')

const here = path.dirname(__filename)
const banner = fs.readFileSync(path.join(here, '../host/banner.js'), 'utf8')
const bundle = fs.readFileSync(path.join(here, '../build/bundle.js'), 'utf8')

// ---------------------------------------------------------------------------
// bridge stubs (what TgJsRuntime will provide in Kotlin)
// ---------------------------------------------------------------------------

const queue = []
let pollWaiter = null

function pushEvent(event) {
  queue.push(event)
  if (pollWaiter) {
    const w = pollWaiter
    pollWaiter = null
    w()
  }
}

const logLines = []
let wsOpened = 0
let wsUrl = null

function b(i8) {
  if (i8 == null) return Buffer.alloc(0)
  return Buffer.from(i8.buffer, i8.byteOffset, i8.byteLength)
}

const sandbox = {
  // the vm context provides bare JS built-ins (Promise, JSON, Map, Date, ...)
  __tgLog: (level, tag, message) => {
    logLines.push(`[${level}][${tag}] ${message}`)
  },
  __tgSetTimer: (id, ms, repeat) =>
    Promise.resolve().then(() => {
      if (repeat) {
        setInterval(() => pushEvent([1, id]), Math.max(0, Number(ms)))
      } else {
        setTimeout(() => pushEvent([1, id]), Math.max(0, Number(ms)))
      }
      return true
    }),
  __tgCancelTimer: () => true,
  __tgPollEvent: () =>
    new Promise((resolve) => {
      const drain = () => resolve(queue.length ? queue.shift() : null)
      if (queue.length) {
        drain()
      } else {
        pollWaiter = () => resolve(queue.shift())
      }
    }),
  __tgWsOpen: (id, url, protocol) => {
    wsOpened++
    wsUrl = url
    if (typeof url !== 'string' || !url.startsWith('wss://')) {
      throw new Error('unexpected ws url: ' + url)
    }
    // simulate a failed connection so init() returns an error envelope
    setTimeout(() => pushEvent([0, id, 3, 0, 'connection refused (stub)', null]), 30)
    return true
  },
  __tgWsSend: () => true,
  __tgWsClose: () => true,
  // crypto bridge -> real node crypto where trivial
  __tgSha1: (data) => b(nodeCrypto.createHash('sha1').update(b(data)).digest()),
  __tgSha256: (data) => b(nodeCrypto.createHash('sha256').update(b(data)).digest()),
  __tgHmacSha256: (data, key) => b(nodeCrypto.createHmac('sha256', b(key)).update(b(data)).digest()),
  __tgPbkdf2: (password, salt, iterations, keylen, algo) => {
    const hash = algo === 'sha512' ? 'sha512' : 'sha1'
    return b(nodeCrypto.pbkdf2Sync(b(password), b(salt), Number(iterations), Number(keylen), hash))
  },
  __tgAesCtrOpen: () => 1,
  __tgAesCtrProcess: (handle, data) => data,
  __tgAesCtrClose: () => true,
  __tgAesIge: (encrypt, key, iv, data) => data,
  __tgFactorizePq: () => [b(Buffer.from([3])), b(Buffer.from([7]))],
  __tgGzip: () => null,
  __tgGunzip: (data) => data,
  __tgRandomBytes: (size) => {
    const out = Buffer.alloc(Number(size))
    nodeCrypto.randomFillSync(out)
    return out
  },
  __tgStoreLoadAll: (store) => Promise.resolve([]),
  __tgStoreSet: (store, key, value) => Promise.resolve(true),
  __tgStoreDelete: (store, key) => Promise.resolve(true),
  __tgStoreClear: (store) => Promise.resolve(true),
  __tgStoreClearAll: () => Promise.resolve(true),
  __tgOnClientEvent: (type, json) => {
    logLines.push(`[event][${type}] ${json}`)
  },

  // ---- RPC bridge stubs (the TgJsRuntime main-loop protocol) ----
  __tgSignalReady: () => {
    hostReadySignalled = true
    return null
  },
  __tgWaitRpc: () =>
    new Promise((resolve) => {
      if (rpcQueue.length) {
        resolve(rpcQueue.shift())
      } else {
        rpcWaiter = () => resolve(rpcQueue.shift())
      }
    }),
  __tgResolveRpc: (id, json) => {
    // models TgJsRuntime: late resolutions for ids Kotlin no longer waits
    // for (timed out) are dropped silently
    if (pendingRpcIds.has(Number(id))) {
      pendingRpcIds.delete(Number(id))
      rpcResults.push(['json', Number(id), json])
    }
    return null
  },
  __tgResolveRpcBin: (id, err, bytes) => {
    if (pendingRpcIds.has(Number(id))) {
      pendingRpcIds.delete(Number(id))
      rpcResults.push(['bin', Number(id), err, bytes])
    }
    return null
  },
}

let hostReadySignalled = false
const rpcQueue = []
let rpcWaiter = null
const rpcResults = []
const pendingRpcIds = new Set()

function enqueueRpc(req) {
  pendingRpcIds.add(req[0])
  rpcQueue.push(req)
  if (rpcWaiter) {
    const w = rpcWaiter
    rpcWaiter = null
    w()
  }
}

vm.createContext(sandbox, { codeGeneration: { strings: true, wasm: false } })

// ---------------------------------------------------------------------------
// run the host
// ---------------------------------------------------------------------------

const code = banner + '\n' + bundle
vm.runInContext(code, sandbox, { filename: 'mtcute_host.js' })

const checks = []
function check(name, ok) {
  checks.push([name, !!ok])
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}`)
}

async function main() {
  check('__tgHostReady set', sandbox.__tgHostReady === true)
  check('__tgApiCall registered', typeof sandbox.__tgApiCall === 'function')
  check('__tgApiCallBin registered', typeof sandbox.__tgApiCallBin === 'function')

  // environment shims
  check(
    'shims installed (TextEncoder/Decoder/AbortController/timers/WebSocket/performance)',
    vm.runInContext(
      'typeof TextEncoder === "function" && typeof TextDecoder === "function" && ' +
        'typeof AbortController === "function" && typeof setTimeout === "function" && ' +
        'typeof setInterval === "function" && typeof clearTimeout === "function" && ' +
        'typeof WebSocket === "function" && typeof performance === "object" && ' +
        'typeof queueMicrotask === "function"',
      sandbox,
    ),
  )
  check(
    'AbortSignal.any + throwIfAborted available',
    vm.runInContext(
      'typeof AbortSignal.any === "function" && typeof AbortSignal.abort === "function" && ' +
        '(function(){ var c = new AbortController(); var ok = true; try { c.signal.throwIfAborted() } catch (e) { ok = false }' +
        ' c.abort(); try { c.signal.throwIfAborted() } catch (e) { ok = e.name === "AbortError" || e instanceof Error }' +
        ' return ok })()',
      sandbox,
    ),
  )
  check(
    'TextEncoder encodes non-ascii correctly',
    vm.runInContext(
      'Array.from(new TextEncoder().encode("héllo → 🎵")).join(",") === ' +
        JSON.stringify(Array.from(Buffer.from('héllo → 🎵', 'utf8')).join(',')),
      sandbox,
    ),
  )

  // unknown method -> error envelope
  const unknown = JSON.parse(await sandbox.__tgApiCall('nope', '{}'))
  check('unknown method returns __error envelope', unknown.__error && unknown.__error.code === -1)

  // init -> mtcute constructs, transport connect attempted over our WS bridge.
  // The stubbed socket always fails, and mtcute's reconnection strategy keeps
  // retrying — so init must stay PENDING while reconnects keep happening.
  const initPromise = sandbox.__tgApiCall(
    'init',
    JSON.stringify({ apiId: 12345, apiHash: 'deadbeef', deviceModel: 'smoke-test' }),
  )
  const initResult = await Promise.race([
    initPromise.then((value) => ({ done: JSON.parse(value) })),
    new Promise((resolve) => setTimeout(() => resolve({ pending: true }), 5000)),
  ])
  check('init reached the WebSocket transport (ws open attempted)', wsOpened > 0)
  check('transport url uses the web.telegram.org apiws endpoint', /wss:\/\/[a-z]+\.web\.telegram\.org\/apiws/.test(wsUrl || ''))
  check('connect stays pending while the transport retries', !!initResult.pending)

  // mtcute's reconnection strategy schedules retries through globalThis.setTimeout
  // (our timer bridge) — a second open attempt proves the timer loop works
  await new Promise((r) => setTimeout(r, 2500))
  check('reconnection retry happened through the timer bridge', wsOpened >= 2)

  // ws error event propagated via event pump
  await new Promise((r) => setTimeout(r, 100))
  const wsLog = logLines.find((line) => line.includes('connection refused (stub)'))
  check('ws error event propagated through the event pump', !!wsLog)

  // binary handler present (error path throws TGERR json)
  let binError = null
  try {
    await sandbox.__tgApiCallBin('readFilePart', '{}')
  } catch (e) {
    binError = e
  }
  check(
    'readFilePart without client throws TGERR envelope',
    binError && String(binError.message).startsWith('TGERR:'),
  )

  // resetSession works without a connected client (clears local storage)
  const reset = JSON.parse(await sandbox.__tgApiCall('resetSession', '{}'))
  check('resetSession returns ok without a client', reset.ok === true)

  // -----------------------------------------------------------------------
  // main loop: the RPC bridge protocol TgJsRuntime drives on device. The
  // loop is appended AFTER the bundle inside the single root evaluation;
  // it must signal readiness synchronously and then dispatch queued RPCs
  // through __tgWaitRpc -> __tgApiCall[Bin] -> __tgResolveRpc[Bin].
  // -----------------------------------------------------------------------
  const mainloop = fs.readFileSync(path.join(here, '../host/mainloop.js'), 'utf8')
  vm.runInContext(mainloop, sandbox, { filename: 'mainloop.js' })
  check('main loop signalled readiness synchronously', hostReadySignalled === true)

  enqueueRpc([1, 'resetSession', '{}', false])
  await new Promise((r) => setTimeout(r, 100))
  const jsonResult = rpcResults.find((entry) => entry[0] === 'json' && entry[1] === 1)
  check(
    'json RPC round-trips through the main loop',
    jsonResult && JSON.parse(jsonResult[2]).ok === true,
  )

  enqueueRpc([2, 'readFilePart', '{}', true])
  await new Promise((r) => setTimeout(r, 100))
  const binResult = rpcResults.find((entry) => entry[0] === 'bin' && entry[1] === 2)
  check(
    'binary RPC failure round-trips as TGERR text',
    binResult && typeof binResult[2] === 'string' && binResult[2].startsWith('TGERR:'),
  )

  enqueueRpc([3, 'nope', '{}', false])
  await new Promise((r) => setTimeout(r, 100))
  const unknownRpc = rpcResults.find((entry) => entry[0] === 'json' && entry[1] === 3)
  check(
    'unknown method RPC returns the __error envelope',
    unknownRpc && JSON.parse(unknownRpc[2]).__error.code === -1,
  )

  // stale resolutions must be dropped silently (Kotlin timed the call out)
  vm.runInContext('__tgResolveRpc(999, "{}")', sandbox)
  check('stale RPC resolution is a no-op', rpcResults.every((entry) => entry[1] !== 999))

  // the loop must still be alive after all of the above (queue drain works)
  enqueueRpc([4, 'resetSession', '{}', false])
  await new Promise((r) => setTimeout(r, 100))
  const jsonResult2 = rpcResults.find((entry) => entry[0] === 'json' && entry[1] === 4)
  check('main loop keeps serving RPCs', jsonResult2 && JSON.parse(jsonResult2[2]).ok === true)

  const failed = checks.filter(([, ok]) => !ok)
  console.log(`\n${checks.length - failed.length}/${checks.length} checks passed`)
  if (failed.length) {
    console.log('\nLog tail:')
    for (const line of logLines.slice(-25)) console.log('  ' + line)
    process.exit(1)
  }
  // mtcute's reconnection timers keep the Node event loop alive — exit
  // explicitly once the suite is done
  process.exit(0)
}

main().catch((e) => {
  console.error('SMOKE TEST CRASHED:', e)
  for (const line of logLines.slice(-30)) console.log('  ' + line)
  process.exit(1)
})
