/*
 * ArchiveTune (2026) — mtcute host prelude.
 *
 * Environment shims required to run the mtcute (MTProto) bundle inside a bare
 * QuickJS runtime. Every platform facility (timers, WebSocket, events) is
 * bridged to Kotlin through the global bindings that TgJsRuntime defines
 * BEFORE this script is evaluated:
 *
 *   Sync bindings (Kotlin functions callable from JS, return values directly):
 *     __tgWsOpen(id, url, protocol) -> bool          begin an OkHttp WebSocket
 *     __tgWsSend(id, Int8Array) -> bool              send one binary frame
 *     __tgWsClose(id, code, reason)                  close the socket
 *     __tgLog(level, tag, message)                   diagnostics sink
 *     __tgSha1(Int8Array) -> Int8Array               crypto bridge (see TgJsCrypto)
 *     ... and the rest of the crypto bridge
 *
 *   Async bindings (return Promises; Kotlin may suspend):
 *     __tgSetTimer(timerId, ms, repeat) -> bool      arm a timer (event comes back)
 *     __tgCancelTimer(timerId)                       cancel it
 *     __tgPollEvent() -> [type, ...]                 wait for the next bridge event
 *     __tgStore*  storage bridge (see TgJsStorage)
 *
 * Events returned by __tgPollEvent (all binary payloads are Int8Array):
 *   [0, wsId, 0, 0, "", null]                      socket opened
 *   [0, wsId, 1, 0, "", Int8Array]                  binary frame received
 *   [0, wsId, 2, code, reason, null]                socket closed
 *   [0, wsId, 3, 0, message, null]                  socket failed
 *   [1, timerId]                                    timer fired
 *
 * The mtcute bundle proper is appended after this prelude by build.sh
 * (esbuild, IIFE) and registers globalThis.__tgApiCall(method, paramsJson).
 */

;(function () {
  'use strict'

  // ------------------------------------------------------------------ logging
  globalThis.console = {
    log: function () { __tgLog(20, 'js', Array.prototype.join.call(arguments, ' ')) },
    warn: function () { __tgLog(30, 'js', Array.prototype.join.call(arguments, ' ')) },
    error: function () { __tgLog(40, 'js', Array.prototype.join.call(arguments, ' ')) },
    debug: function () { __tgLog(10, 'js', Array.prototype.join.call(arguments, ' ')) },
    info: function () { __tgLog(20, 'js', Array.prototype.join.call(arguments, ' ')) },
  }

  // --------------------------------------------------------------- performance
  if (typeof globalThis.performance === 'undefined') {
    globalThis.performance = {
      now: function () { return Date.now() },
      timeOrigin: Date.now(),
    }
  }

  if (typeof globalThis.queueMicrotask === 'undefined') {
    globalThis.queueMicrotask = function (fn) { Promise.resolve().then(fn) }
  }

  // ---------------------------------------------------------------------- UTF8
  // Minimal WHATWG-compliant TextEncoder / TextDecoder (fatal=false).
  function utf8Encode(str) {
    var bytes = []
    for (var i = 0; i < str.length; i++) {
      var code = str.charCodeAt(i)
      if (code >= 0xd800 && code <= 0xdbff) {
        var next = str.charCodeAt(i + 1)
        if (next >= 0xdc00 && next <= 0xdfff) {
          code = 0x10000 + ((code - 0xd800) << 10) + (next - 0xdc00)
          i++
        } else {
          code = 0xfffd
        }
      } else if (code >= 0xdc00 && code <= 0xdfff) {
        code = 0xfffd
      }
      if (code <= 0x7f) {
        bytes.push(code)
      } else if (code <= 0x7ff) {
        bytes.push(0xc0 | (code >> 6), 0x80 | (code & 63))
      } else if (code <= 0xffff) {
        bytes.push(0xe0 | (code >> 12), 0x80 | ((code >> 6) & 63), 0x80 | (code & 63))
      } else {
        bytes.push(0xf0 | (code >> 18), 0x80 | ((code >> 12) & 63), 0x80 | ((code >> 6) & 63), 0x80 | (code & 63))
      }
    }
    return new Uint8Array(bytes)
  }

  function utf8Decode(bytes) {
    var out = ''
    var i = 0
    var n = bytes.length
    while (i < n) {
      var b = bytes[i]
      var code = 0
      var extra = 0
      if (b < 0x80) {
        code = b
      } else if (b >= 0xc2 && b <= 0xdf) {
        if (i + 1 >= n) { code = 0xfffd } else {
          code = b & 0x1f
          extra = 1
        }
      } else if (b >= 0xe0 && b <= 0xef) {
        if (i + 2 >= n) { code = 0xfffd } else {
          code = b & 0x0f
          extra = 2
        }
      } else if (b >= 0xf0 && b <= 0xf4) {
        if (i + 3 >= n) { code = 0xfffd } else {
          code = b & 0x07
          extra = 3
        }
      } else {
        code = 0xfffd
      }
      if (extra > 0) {
        for (var k = 1; k <= extra; k++) {
          var cb = bytes[i + k]
          if (cb === undefined || (cb & 0xc0) !== 0x80) { code = 0xfffd; extra = 0; break }
          code = (code << 6) | (cb & 0x3f)
        }
        if (extra > 0) { i += extra }
      }
      // UTF-16 encode
      if (code <= 0xffff) {
        out += String.fromCharCode(code)
      } else {
        code -= 0x10000
        out += String.fromCharCode(0xd800 + (code >> 10), 0xdc00 + (code & 0x3ff))
      }
      i++
    }
    return out
  }

  if (typeof globalThis.TextEncoder === 'undefined') {
    globalThis.TextEncoder = function TextEncoder() {
      this.encoding = 'utf-8'
    }
    globalThis.TextEncoder.prototype.encode = function (str) { return utf8Encode(String(str)) }
    globalThis.TextEncoder.prototype.encodeInto = function (str, dest) {
      var bytes = utf8Encode(str)
      var written = Math.min(bytes.length, dest.length)
      for (var i = 0; i < written; i++) dest[i] = bytes[i]
      return { read: written === bytes.length ? str.length : written, written: written }
    }
    globalThis.TextDecoder = function TextDecoder() {
      this.encoding = 'utf-8'
    }
    globalThis.TextDecoder.prototype.decode = function (buf) {
      if (buf instanceof ArrayBuffer) buf = new Uint8Array(buf)
      return utf8Decode(buf)
    }
  }

  // ------------------------------------------------------- AbortController shim
  if (typeof globalThis.AbortController === 'undefined') {
    function AbortSignalShim() {
      this._aborted = false
      this._reason = undefined
      this._listeners = []
    }
    Object.defineProperty(AbortSignalShim.prototype, 'aborted', {
      get: function () { return this._aborted },
      enumerable: true,
    })
    Object.defineProperty(AbortSignalShim.prototype, 'reason', {
      get: function () { return this._reason },
      enumerable: true,
    })
    AbortSignalShim.prototype.throwIfAborted = function () {
      if (this._aborted) throw this._reason
    }
    AbortSignalShim.prototype.addEventListener = function (type, fn) {
      if (type === 'abort' && typeof fn === 'function') this._listeners.push(fn)
    }
    AbortSignalShim.prototype.removeEventListener = function (type, fn) {
      if (type !== 'abort') return
      var idx = this._listeners.indexOf(fn)
      if (idx >= 0) this._listeners.splice(idx, 1)
    }
    AbortSignalShim.prototype._abort = function (reason) {
      if (this._aborted) return
      this._aborted = true
      if (reason === undefined) {
        var err = new Error('This operation was aborted')
        err.name = 'AbortError'
        reason = err
      }
      this._reason = reason
      var listeners = this._listeners.slice()
      this._listeners.length = 0
      for (var i = 0; i < listeners.length; i++) {
        try { listeners[i]({ type: 'abort' }) } catch (e) { __tgLog(40, 'abort', String(e)) }
      }
    }

    globalThis.AbortSignal = AbortSignalShim
    globalThis.AbortController = function AbortController() {
      this._signal = new AbortSignalShim()
    }
    Object.defineProperty(globalThis.AbortController.prototype, 'signal', {
      get: function () { return this._signal },
      enumerable: true,
    })
    globalThis.AbortController.prototype.abort = function (reason) {
      this._signal._abort(reason)
    }
    globalThis.AbortSignal.any = function (signals) {
      var combined = new AbortSignalShim()
      for (var i = 0; i < signals.length; i++) {
        var s = signals[i]
        if (s.aborted) {
          combined._abort(s.reason)
          return combined
        }
        s.addEventListener('abort', function (sig) {
          return function () { combined._abort(sig.reason) }
        }(s))
      }
      return combined
    }
    globalThis.AbortSignal.abort = function (reason) {
      var sig = new AbortSignalShim()
      sig._abort(reason)
      return sig
    }
    globalThis.AbortSignal.timeout = function (ms) {
      var controller = new globalThis.AbortController()
      globalThis.setTimeout(function () { controller.abort() }, ms)
      return controller.signal
    }
  }

  // --------------------------------------------------------------------- timers
  var timerCallbacks = new Map()
  var nextTimerId = 1
  globalThis.setTimeout = function (fn, ms) {
    if (typeof fn !== 'function') return 0
    var id = nextTimerId++
    timerCallbacks.set(id, { fn: fn, args: Array.prototype.slice.call(arguments, 2) })
    __tgSetTimer(id, Number(ms) || 0, false)
    return id
  }
  globalThis.setInterval = function (fn, ms) {
    if (typeof fn !== 'function') return 0
    var id = nextTimerId++
    timerCallbacks.set(id, { fn: fn, args: Array.prototype.slice.call(arguments, 2) })
    __tgSetTimer(id, Number(ms) || 0, true)
    return id
  }
  globalThis.clearTimeout = function (id) {
    if (id === undefined || id === null) return
    timerCallbacks.delete(Number(id))
    __tgCancelTimer(Number(id))
  }
  globalThis.clearInterval = globalThis.clearTimeout

  // ------------------------------------------------------------------ WebSocket
  var wsSockets = new Map()
  var nextWsId = 1

  function TgWebSocket(url, protocols) {
    this.url = String(url)
    this.readyState = 0 // CONNECTING
    this._listeners = {}
    this._id = nextWsId++
    wsSockets.set(this._id, this)
    try {
      __tgWsOpen(this._id, this.url, protocols ? String(protocols) : null)
    } catch (e) {
      this.readyState = 3
      __tgLog(40, 'ws', 'open failed: ' + String(e))
    }
  }
  Object.defineProperty(TgWebSocket.prototype, 'binaryType', {
    get: function () { return 'arraybuffer' },
    set: function () {},
  })
  TgWebSocket.prototype.addEventListener = function (type, fn) {
    if (typeof fn !== 'function') return
    if (!this._listeners[type]) this._listeners[type] = []
    this._listeners[type].push(fn)
  }
  TgWebSocket.prototype.removeEventListener = function (type, fn) {
    var list = this._listeners[type]
    if (!list) return
    var idx = list.indexOf(fn)
    if (idx >= 0) list.splice(idx, 1)
  }
  TgWebSocket.prototype.dispatchEvent = function (type, event) {
    var list = this._listeners[type]
    if (!list) return
    list = list.slice()
    for (var i = 0; i < list.length; i++) {
      try { list[i](event) } catch (e) { __tgLog(40, 'ws', 'listener failed: ' + String(e)) }
    }
  }
  TgWebSocket.prototype.send = function (data) {
    if (this.readyState !== 1) throw new Error('WebSocket is not open')
    var bytes
    if (data instanceof Int8Array) {
      bytes = data
    } else if (data instanceof Uint8Array) {
      bytes = new Int8Array(data.buffer, data.byteOffset, data.byteLength)
    } else if (data instanceof ArrayBuffer) {
      bytes = new Int8Array(data)
    } else {
      throw new Error('binary frames expected')
    }
    __tgWsSend(this._id, bytes)
  }
  TgWebSocket.prototype.close = function (code, reason) {
    if (this.readyState === 2 || this.readyState === 3) return
    this.readyState = 2
    try {
      __tgWsClose(this._id, code === undefined ? 1000 : Number(code), reason === undefined ? '' : String(reason))
    } catch (e) { /* ignore */ }
  }
  TgWebSocket._dispatch = function (id, kind, code, reason, data) {
    var socket = wsSockets.get(id)
    if (!socket) return
    if (kind === 0) {
      socket.readyState = 1
      socket.dispatchEvent('open', { type: 'open' })
    } else if (kind === 1) {
      var buffer = data ? data.buffer : new ArrayBuffer(0)
      socket.dispatchEvent('message', { type: 'message', data: buffer })
    } else if (kind === 2) {
      socket.readyState = 3
      socket.dispatchEvent('close', { type: 'close', code: code, reason: reason })
      wsSockets.delete(id)
    } else if (kind === 3) {
      socket.readyState = 3
      var err = new Error(reason || 'WebSocket error')
      socket.dispatchEvent('error', { type: 'error', error: err, message: err.message })
      wsSockets.delete(id)
    }
  }
  globalThis.WebSocket = TgWebSocket

  // ----------------------------------------------------------------- event loop
  // Pump for events originating on the Kotlin side (socket frames, timers).
  // Runs forever; errors never break the loop.
  function fireTimer(id) {
    var entry = timerCallbacks.get(id)
    if (!entry) return
    try { entry.fn.apply(null, entry.args) } catch (e) {
      __tgLog(40, 'timer', String((e && e.stack) || e))
    }
  }

  Promise.resolve().then(function pump() {
    __tgPollEvent().then(function (ev) {
      try {
        if (!ev) return
        var type = ev[0]
        if (type === 0) {
          TgWebSocket._dispatch(ev[1], ev[2], ev[3], ev[4], ev[5])
        } else if (type === 1) {
          fireTimer(ev[1])
        }
      } catch (e) {
        __tgLog(40, 'evtloop', String((e && e.stack) || e))
      }
      pump()
    }, function (err) {
      __tgLog(40, 'evtloop', 'poll failed: ' + String(err))
      pump()
    })
  })
})();
