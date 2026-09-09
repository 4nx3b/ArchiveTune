/*
 * ArchiveTune (2026) — REAL NETWORK integration test for the mtcute host bundle.
 *
 * Unlike smoke.js (which stubs the WS as a failure), this harness bridges the
 * bundle to the REAL Telegram servers through Node's `ws` package, mirroring
 * exactly what TgJsRuntime.kt does on Android:
 *
 *   - __tgWsOpen  -> real wss:// connection with subprotocol 'binary'
 *                    (OkHttp: manual Sec-WebSocket-Protocol header)
 *   - __tgWsSend  -> binary frames (Buffer from Int8Array)
 *   - events      -> same [0, id, kind, code, reason, Int8Array|null] protocol
 *   - __tgSetTimer/__tgCancelTimer -> Node timers through the event queue
 *   - crypto      -> real Node crypto (mirrors TgJsCrypto contracts:
 *                    sha1/sha256/hmac/pbkdf2/aes-ctr/aes-ige/pq/gzip/random)
 *   - storage     -> in-memory maps (mirrors TgJsStorage)
 *   - RPC         -> the MAIN_LOOP protocol: __tgWaitRpc / __tgResolveRpc[Bin]
 *
 * Then it invokes `init` through the RPC bridge, which runs
 * ensureClient -> client.connect() -> full MTProto auth-key negotiation
 * (req_pq -> req_DH_params -> set_client_DH_params -> bind_persistent_temp_key)
 * and getMe(). Even without valid api credentials this must COMPLETE with an
 * RPC error envelope (authorized:false), never hang.
 *
 * Usage: node test/real_network.js [apiId apiHash]
 */

'use strict'

const fs = require('fs')
const path = require('path')
const vm = require('vm')
const nodeCrypto = require('crypto')
const zlib = require('zlib')
const WebSocket = require('ws')

const here = path.dirname(__filename)
const banner = fs.readFileSync(path.join(here, '../host/banner.js'), 'utf8')
const bundle = fs.readFileSync(path.join(here, '../build/asset_bundle.js'), 'utf8')

const apiId = Number(process.argv[2] || 0)
const apiHash = String(process.argv[3] || '')

const VERBOSE = process.env.TG_VERBOSE !== '0'

function log(...args) {
  console.log('[harness]', ...args)
}

// ---------------------------------------------------------------------------
// bridge state (mirrors TgJsRuntime)
// ---------------------------------------------------------------------------

const queue = [] // bridgeEvents channel
let pollWaiter = null

function pushEvent(event) {
  queue.push(event)
  if (pollWaiter) {
    const w = pollWaiter
    pollWaiter = null
    w()
  }
}

function i8(buf) {
  if (buf == null) return null
  const u8 = new Uint8Array(buf)
  return new Int8Array(u8.buffer, u8.byteOffset, u8.byteLength)
}

function b(i8arr) {
  if (i8arr == null) return Buffer.alloc(0)
  if (Buffer.isBuffer(i8arr)) return i8arr
  const u8 = new Uint8Array(i8arr.buffer, i8arr.byteOffset, i8arr.byteLength)
  return Buffer.from(u8)
}

const wsLog = []
const sockets = new Map()
let nextWsId = 1

function wsOpen(id, url, protocol) {
  // mirrors OkHttp: manual Sec-WebSocket-Protocol: binary
  const headers = {}
  if (process.env.TG_BROWSER_HEADERS === '1') {
    headers['Origin'] = 'https://web.telegram.org'
    headers['User-Agent'] =
      'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36'
  }
  const socket = new WebSocket(url, protocol ? [protocol] : [], { headers })
  sockets.set(id, socket)
  socket.on('open', () => {
    if (VERBOSE) log(`ws#${id} OPEN`)
    pushEvent([0, id, 0, 0, '', null])
  })
  socket.on('message', (data, isBinary) => {
    if (!isBinary) {
      log('UNEXPECTED text frame from server')
      return
    }
    if (VERBOSE) {
      const head = b(data).subarray(0, 32).toString('hex')
      log(`ws#${id} RECV ${data.length}B: ${head}${data.length > 32 ? '...' : ''}`)
    }
    pushEvent([0, id, 1, 0, '', i8(data)])
  })
  socket.on('close', (code, reason) => {
    if (VERBOSE) log(`ws#${id} CLOSE code=${code} reason=${reason}`)
    sockets.delete(id)
    pushEvent([0, id, 2, code, String(reason || '')])
  })
  socket.on('unexpected-response', (_req, res) => {
    sockets.delete(id)
    pushEvent([0, id, 3, 0, `unexpected response ${res.statusCode}`, null])
  })
  socket.on('error', (err) => {
    if (sockets.get(id) !== socket) return
    sockets.delete(id)
    pushEvent([0, id, 3, 0, String(err.message || err), null])
  })
  wsLog.push({ id, url, protocol: protocol || null })
  return true
}

function wsSend(id, data) {
  const socket = sockets.get(id)
  if (!socket) return false
  if (VERBOSE) {
    const buf = b(data)
    log(`ws#${id} SEND ${buf.length}B: ${buf.subarray(0, 32).toString('hex')}${buf.length > 32 ? '...' : ''}`)
  }
  socket.send(b(data))
  return true
}

function wsClose(id, code, reason) {
  const socket = sockets.get(id)
  if (socket) {
    sockets.delete(id)
    socket.close(code, String(reason || ''))
  }
  return null
}

// ---- timers (mirrors scheduleTimer: event [1, timerId] via bridgeEvents) ----

const timers = new Map()

function setTimer(id, ms, repeat) {
  clearTimer(id)
  if (repeat) {
    timers.set(id, setInterval(() => pushEvent([1, id]), Math.max(1, Number(ms))))
  } else {
    timers.set(id, setTimeout(() => pushEvent([1, id]), Math.max(0, Number(ms))))
  }
  return true
}

function clearTimer(id) {
  const t = timers.get(Number(id))
  if (t) {
    clearTimeout(t)
    clearInterval(t)
    timers.delete(Number(id))
  }
  return true
}

// ---- crypto (mirrors TgJsCrypto contracts) ----

function aesCtr(key, iv, encrypt) {
  // streams are not chunked independently in the bridge: TgJsCrypto keeps a
  // CTR handle; mtcute only uses CTR for download encryption as a whole
  // stream. Mirror with node's CTR.
  const algo = nodeCrypto.createCipheriv(encrypt ? 'aes-256-ctr' : 'aes-256-ctr', key, iv.slice(0, 16))
  return { update: (data) => algo.update(b(data)) }
}

const ctrStreams = new Map()
let nextCtrHandle = 1

function aesIge(encrypt, key, iv, data) {
  // Mirrors TgJsCrypto.aesIge — MTProto AES-IGE:
  //   encrypt: c_i = E(p_i ^ c_{i-1}) ^ p_{i-1}
  //   decrypt: p_i = D(c_i ^ p_{i-1}) ^ c_{i-1}
  // seeded with c_0 = iv[0..16) and p_0 = iv[16..32). The chaining state
  // is the full previous output/input block (after the outer XOR).
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

function factorizePq(pq) {
  // mirrors TgJsCrypto.factorizePq: BigInteger(1, pq) -> p<=q, trimTo4 (BE)
  const n = BigInt('0x' + (b(pq).length ? b(pq).toString('hex') : '00'))
  const bgcd = (a, bb) => {
    while (bb) { const t = bb; bb = a % bb; a = t }
    return a
  }
  let factor = null
  for (let p = 2n; p < 100000n && p * p <= n; p++) {
    if (n % p === 0n) { factor = p; break }
  }
  if (factor === null && n > 1n) {
    // Pollard-Brent
    if (n % 2n === 0n) factor = 2n
    else {
      let seed = 1n
      outer: for (let attempt = 0; attempt < 64; attempt++) {
        let y = (seed = (seed * 48271n) % 0x7fffffffn) % (n - 1n) + 1n
        const c = (seed * 31n) % (n - 1n) + 1n
        const m = 128n
        let g = 1n
        let r = 1n
        let q = 1n
        let x = y
        while (g === 1n) {
          x = y
          for (let i = 0n; i < r; i++) y = (y * y + c) % n
          let k = 0n
          while (k < r && g === 1n) {
            const lim = m < r - k ? m : r - k
            for (let i = 0n; i < lim; i++) {
              y = (y * y + c) % n
              const diff = x > y ? x - y : y - x
              q = (q * diff) % n
            }
            g = bgcd(q, n)
            k += m
          }
          r *= 2n
          if (r > 0x40000000n) break
        }
        if (g !== 1n && g !== n) factor = g
        if (g === n) continue outer
        if (factor) break
      }
    }
  }
  const trimTo4 = (v) => {
    let hex = v.toString(16)
    if (hex.length % 2) hex = '0' + hex
    const bytes = Buffer.from(hex, 'hex')
    const last = bytes.slice(-4)
    const out = Buffer.alloc(4)
    last.copy(out, 4 - last.length)
    return out
  }
  if (factor === null || factor <= 1n || n / factor * factor !== n) {
    console.log('[harness] FACTORIZE FAILED for pq=' + n.toString())
    return [Buffer.from([0, 0, 0, 1]), trimTo4(n)]
  }
  const other = n / factor
  const small = factor <= other ? factor : other
  const large = factor <= other ? other : factor
  console.log('[harness] factorized pq=' + n.toString() + ' -> ' + small.toString() + ' x ' + large.toString())
  return [trimTo4(small), trimTo4(large)]
}

// ---- storage (in-memory) ----

const stores = new Map()

function storeGet(name) {
  if (!stores.has(name)) stores.set(name, new Map())
  return stores.get(name)
}

// ---- RPC main-loop protocol (mirrors MAIN_LOOP_JS) ----

const rpcRequests = []
let rpcWaiter = null
let nextCallId = 1
const pendingCalls = new Map()
let hostReadySignalled = false

const sandbox = {
  __tgLog: (level, tag, message) => {
    if (VERBOSE || level >= 30) console.log(`   [js ${level}][${tag}] ${message}`)
  },
  __tgSetTimer: (id, ms, repeat) => Promise.resolve(setTimer(id, ms, repeat)),
  __tgCancelTimer: (id) => clearTimer(id),
  __tgPollEvent: () =>
    new Promise((resolve) => {
      const drain = () => resolve(queue.length ? queue.shift() : null)
      if (queue.length) drain()
      else pollWaiter = () => resolve(queue.shift())
    }),
  __tgWsOpen: (id, url, protocol) => wsOpen(id, url, protocol),
  __tgWsSend: (id, data) => wsSend(id, data),
  __tgWsClose: (id, code, reason) => wsClose(id, code, reason),
  __tgSha1: (data) => i8(nodeCrypto.createHash('sha1').update(b(data)).digest()),
  __tgSha256: (data) => i8(nodeCrypto.createHash('sha256').update(b(data)).digest()),
  __tgHmacSha256: (data, key) => i8(nodeCrypto.createHmac('sha256', b(key)).update(b(data)).digest()),
  __tgPbkdf2: (password, salt, iterations, keylen, algo) => {
    const hash = algo === 'sha512' ? 'sha512' : 'sha1'
    return i8(nodeCrypto.pbkdf2Sync(b(password), b(salt), Number(iterations), Number(keylen), hash))
  },
  __tgAesCtrOpen: (key, iv, encrypt) => {
    const handle = nextCtrHandle++
    const stream = nodeCrypto.createCipheriv('aes-256-ctr', b(key), b(iv).slice(0, 16))
    ctrStreams.set(handle, { stream, encrypt })
    return handle
  },
  __tgAesCtrProcess: (handle, data) => {
    const entry = ctrStreams.get(Number(handle))
    if (!entry) throw new Error('bad aes ctr handle')
    return i8(entry.stream.update(b(data)))
  },
  __tgAesCtrClose: (handle) => {
    ctrStreams.delete(Number(handle))
    return true
  },
  __tgAesIge: (encrypt, key, iv, data) => i8(aesIge(encrypt, b(key), b(iv), b(data))),
  __tgFactorizePq: (pq) => {
    const res = factorizePq(pq)
    return [i8(res[0]), i8(res[1])]
  },
  __tgGzip: (data, maxSize) => {
    const out = zlib.gzipSync(b(data), { level: 6 })
    if (out.length > Number(maxSize) || out.length >= b(data).length) return null
    return i8(out)
  },
  __tgGunzip: (data) => i8(zlib.gunzipSync(b(data))),
  __tgRandomBytes: (size) => i8(nodeCrypto.randomBytes(Number(size))),
  __tgStoreLoadAll: (store) => Promise.resolve(Array.from(storeGet(store).entries()).map(([k, v]) => [k, i8(v)])),
  __tgStoreSet: (store, key, value) => {
    storeGet(store).set(key, b(value))
    return Promise.resolve(true)
  },
  __tgStoreDelete: (store, key) => {
    storeGet(store).delete(key)
    return Promise.resolve(true)
  },
  __tgStoreClear: (store) => {
    storeGet(store).clear()
    return Promise.resolve(true)
  },
  __tgStoreClearAll: () => {
    stores.clear()
    return Promise.resolve(true)
  },
  __tgOnClientEvent: (type, json) => {
    console.log(`   [event ${type}] ${json}`)
  },
  __tgSignalReady: () => {
    hostReadySignalled = true
    return null
  },
  __tgWaitRpc: () =>
    new Promise((resolve) => {
      const drain = () => resolve(rpcRequests.length ? rpcRequests.shift() : null)
      if (rpcRequests.length) drain()
      else rpcWaiter = () => resolve(rpcRequests.shift())
    }),
  __tgResolveRpc: (id, json) => {
    const d = pendingCalls.get(Number(id))
    if (d) {
      pendingCalls.delete(Number(id))
      d(json)
    }
    return null
  },
  __tgResolveRpcBin: (id, err, bytes) => {
    const d = pendingCalls.get(Number(id))
    if (d) {
      pendingCalls.delete(Number(id))
      d(err ? { __tgErr: String(err) } : bytes)
    }
    return null
  },
}

// minimal globals every JS engine has (vm context provides these already)
sandbox.globalThis = sandbox

// The MAIN_LOOP_JS constant from TgJsRuntime.kt (must stay in sync)
const MAIN_LOOP_JS = `;(function () {
  'use strict'
  function rpcErrText(e) {
    var msg = (e && (e.message || e.text)) || String(e)
    return String(msg)
  }
  function dispatchRpc(req) {
    var id = req[0]
    var method = req[1]
    var params = req[2]
    if (req[3]) {
      Promise.resolve()
        .then(function () { return globalThis.__tgApiCallBin(method, params) })
        .then(
          function (bytes) { globalThis.__tgResolveRpcBin(id, null, bytes) },
          function (e) { globalThis.__tgResolveRpcBin(id, rpcErrText(e), null) },
        )
    } else {
      Promise.resolve()
        .then(function () { return globalThis.__tgApiCall(method, params) })
        .then(
          function (json) { globalThis.__tgResolveRpc(id, json) },
          function (e) { globalThis.__tgResolveRpc(id, '__bridge__' + rpcErrText(e)) },
        )
    }
  }
  globalThis.__tgSignalReady()
  ;(async function () {
    while (true) {
      var req
      try {
        req = await globalThis.__tgWaitRpc()
      } catch (e) {
        try { globalThis.__tgLog(40, 'rpc', 'waitRpc failed: ' + rpcErrText(e)) } catch (ignored) {}
        return
      }
      if (req == null) return
      try {
        dispatchRpc(req)
      } catch (e) {
        try { globalThis.__tgLog(40, 'rpc', 'dispatch failed: ' + rpcErrText(e)) } catch (ignored) {}
      }
    }
  })()
})();`

// ---------------------------------------------------------------------------
// run the bundle
// ---------------------------------------------------------------------------

const context = vm.createContext(sandbox)

log('evaluating banner + bundle + main loop (' + (banner.length + bundle.length) + ' chars)...')
vm.runInContext(banner, context, { filename: 'banner.js' })
vm.runInContext(bundle, context, { filename: 'bundle.js' })
vm.runInContext(MAIN_LOOP_JS, context, { filename: 'mainloop.js' })
log('evaluated; hostReady =', hostReadySignalled)

function call(method, paramsJson, timeoutMs) {
  return new Promise((resolve, reject) => {
    const id = nextCallId++
    const timer = setTimeout(() => {
      pendingCalls.delete(id)
      reject(new Error(`KOTLIN-SIDE TIMEOUT (${method}) after ${timeoutMs}ms`))
    }, timeoutMs)
    pendingCalls.set(id, (value) => {
      clearTimeout(timer)
      resolve(value)
    })
    rpcRequests.push([id, method, String(paramsJson), false])
    if (rpcWaiter) {
      const w = rpcWaiter
      rpcWaiter = null
      w()
    }
  })
}

async function main() {
  const initTimeout = 30000
  log(`calling init (apiId=${apiId})...`)
  const started = Date.now()
  let result
  try {
    result = await call('init', JSON.stringify({
      apiId,
      apiHash,
      deviceModel: 'ArchiveTune-NodeHarness',
      systemVersion: '1.0',
      appVersion: '1.0.0',
      langCode: 'en',
    }), initTimeout)
  } catch (e) {
    log('FAILED:', e.message)
    log('WS activity:', JSON.stringify(wsLog, null, 2))
    process.exit(1)
  }
  const elapsed = Date.now() - started
  log(`init completed in ${elapsed}ms ->`, result)
  if (typeof result === 'string' && result.startsWith('__bridge__')) {
    log('BRIDGE ERROR ENVELOPE:', result)
    process.exit(1)
  }
  let parsed = null
  try { parsed = JSON.parse(result) } catch (e) {
    log('non-JSON result!') 
    process.exit(1)
  }
  log('authorized:', parsed.authorized, '| warning:', parsed.warning ?? null)
  log('WS connections made:', wsLog.length, JSON.stringify(wsLog.map((w) => w.url)))

  // keep the process alive a moment to see reconnect/keepalive behaviour
  await new Promise((r) => setTimeout(r, 3000))
  log('done.')
  process.exit(0)
}

main().catch((e) => {
  log('harness crashed:', e)
  process.exit(1)
})
