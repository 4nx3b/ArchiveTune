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

  // ---------------------------------------------------------------------- URL
  // WHATWG URL / URLSearchParams subset. mtcute needs `new URL()` at runtime:
  // @fuman/net's ip.prettify() runs on EVERY connection open (log prefix) and
  // proxy/deeplink parsing uses more of it. QuickJS ships neither global.
  if (typeof globalThis.URL === 'undefined') {
    var SPECIAL_SCHEMES = {
      http: 80, https: 443, ws: 80, wss: 443, ftp: 21, file: null,
    }

    function TgUrlSearchParams(init) {
      this._pairs = []
      this._url = null
      if (init == null) return
      if (typeof init === 'string') {
        this._fromString(init)
      } else if (typeof init === 'object') {
        var self = this
        if (typeof init.forEach === 'function') {
          init.forEach(function (entry) {
            if (Array.isArray(entry)) self.append(String(entry[0]), String(entry[1]))
          })
        } else {
          for (var key in init) {
            if (Object.prototype.hasOwnProperty.call(init, key)) self.append(key, String(init[key]))
          }
        }
      }
    }
    TgUrlSearchParams.prototype._fromString = function (str) {
      var s = String(str)
      if (s.charAt(0) === '?') s = s.slice(1)
      this._pairs.length = 0
      if (!s) return
      var parts = s.split('&')
      for (var i = 0; i < parts.length; i++) {
        var part = parts[i]
        if (!part) continue
        var eq = part.indexOf('=')
        var k, v
        if (eq < 0) {
          k = part; v = ''
        } else {
          k = part.slice(0, eq); v = part.slice(eq + 1)
        }
        this._pairs.push([tgUrlDecode(k), tgUrlDecode(v.replace(/\+/g, ' '))])
      }
    }
    TgUrlSearchParams.prototype._sync = function () {
      if (this._url) this._url._setSearchFromParams(this)
    }
    TgUrlSearchParams.prototype.append = function (k, v) {
      this._pairs.push([String(k), String(v)])
      this._sync()
    }
    TgUrlSearchParams.prototype.set = function (k, v) {
      var key = String(k)
      var found = false
      for (var i = this._pairs.length - 1; i >= 0; i--) {
        if (this._pairs[i][0] === key) {
          if (found) { this._pairs.splice(i, 1) }
          else { this._pairs[i][1] = String(v); found = true }
        }
      }
      if (!found) this._pairs.push([key, String(v)])
      this._sync()
    }
    TgUrlSearchParams.prototype.get = function (k) {
      for (var i = 0; i < this._pairs.length; i++) {
        if (this._pairs[i][0] === String(k)) return this._pairs[i][1]
      }
      return null
    }
    TgUrlSearchParams.prototype.getAll = function (k) {
      var out = []
      for (var i = 0; i < this._pairs.length; i++) {
        if (this._pairs[i][0] === String(k)) out.push(this._pairs[i][1])
      }
      return out
    }
    TgUrlSearchParams.prototype.has = function (k) {
      for (var i = 0; i < this._pairs.length; i++) {
        if (this._pairs[i][0] === String(k)) return true
      }
      return false
    }
    TgUrlSearchParams.prototype['delete'] = function (k) {
      var key = String(k)
      for (var i = this._pairs.length - 1; i >= 0; i--) {
        if (this._pairs[i][0] === key) this._pairs.splice(i, 1)
      }
      this._sync()
    }
    TgUrlSearchParams.prototype.forEach = function (fn) {
      for (var i = 0; i < this._pairs.length; i++) fn(this._pairs[i][1], this._pairs[i][0], this)
    }
    TgUrlSearchParams.prototype.toString = function () {
      var out = ''
      for (var i = 0; i < this._pairs.length; i++) {
        if (i) out += '&'
        out += tgUrlEncodeQuery(this._pairs[i][0]) + '=' + tgUrlEncodeQuery(this._pairs[i][1])
      }
      return out
    }
    Object.defineProperty(TgUrlSearchParams.prototype, 'size', {
      get: function () { return this._pairs.length },
      enumerable: true,
    })

    var HEX_DIGITS = '0123456789ABCDEF'

    function tgUrlDecode(str) {
      var s = String(str)
      if (s.indexOf('%') < 0) return s
      var bytes = []
      for (var i = 0; i < s.length; i++) {
        var c = s.charAt(i)
        if (c === '%') {
          var hi = parseInt(s.slice(i + 1, i + 3), 16)
          if (!isNaN(hi)) {
            bytes.push(hi)
            i += 2
            continue
          }
        }
        bytes.push(s.charCodeAt(i) & 0xff)
      }
      return utf8Decode(new Uint8Array(bytes))
    }

    function tgUrlEncodeQuery(str) {
      return tgUrlEncodeComponent(str, false).replace(/%20/g, '+')
    }

    function tgUrlEncodeComponent(str, keepPathSafe) {
      var bytes = utf8Encode(String(str))
      var out = ''
      for (var i = 0; i < bytes.length; i++) {
        var b = bytes[i]
        var c = String.fromCharCode(b)
        var unreserved =
          (b >= 0x41 && b <= 0x5a) || (b >= 0x61 && b <= 0x7a) || (b >= 0x30 && b <= 0x39) ||
          c === '-' || c === '_' || c === '.' || c === '!' || c === '~' || c === '*' || c === "'" || c === '(' || c === ')'
        if (unreserved) {
          out += c
        } else if (keepPathSafe && (c === '$' || c === '&' || c === '+' || c === ',' || c === '/' || c === ':' || c === ';' || c === '=' || c === '?' || c === '@')) {
          out += c
        } else {
          out += '%' + HEX_DIGITS[b >> 4] + HEX_DIGITS[b & 15]
        }
      }
      return out
    }

    function TgUrl(input, base) {
      if (!(this instanceof TgUrl)) return new TgUrl(input, base)
      var parsed = tgUrlParse(String(input), base ? new TgUrl(base) : null)
      if (!parsed) {
        throw new TypeError('Invalid URL: ' + input)
      }
      this._scheme = parsed.scheme
      this._username = parsed.username
      this._password = parsed.password
      this._host = parsed.host
      this._port = parsed.port
      this._path = parsed.path
      this._query = parsed.query
      this._fragment = parsed.fragment
      this._opaque = parsed.opaque
      this._searchParams = null
    }

    function tgUrlParse(input, baseUrl) {
      var s = input.replace(/[\t\n\r]/g, '')
      if (!s) return null
      var schemeMatch = /^([a-zA-Z][a-zA-Z0-9+.-]*):/.exec(s)
      var scheme
      var rest
      if (schemeMatch) {
        scheme = schemeMatch[1].toLowerCase()
        rest = s.slice(schemeMatch[0].length)
      } else if (baseUrl) {
        if (s.charAt(0) === '/' && s.charAt(1) === '/') {
          s = baseUrl._scheme + ':' + s
        } else if (s.charAt(0) === '/') {
          s = baseUrl._scheme + '://' + baseUrl._authority() + s
        } else if (s.charAt(0) === '?') {
          s = baseUrl._scheme + '://' + baseUrl._authority() + baseUrl._path + s
        } else if (s.charAt(0) === '#') {
          s = baseUrl._hrefNoFragment() + s
        } else {
          var dir = baseUrl._path.slice(0, baseUrl._path.lastIndexOf('/') + 1)
          s = baseUrl._scheme + '://' + baseUrl._authority() + dir + s
        }
        return tgUrlParse(s, null)
      } else {
        return null
      }

      var special = Object.prototype.hasOwnProperty.call(SPECIAL_SCHEMES, scheme)
      var authority = ''
      var path = ''
      var opaque = ''
      var afterAuthority = rest

      if (rest.charAt(0) === '/' && rest.charAt(1) === '/') {
        var endAuth = -1
        for (var i = 2; i < rest.length; i++) {
          var ch = rest.charAt(i)
          if (ch === '/' || ch === '?' || ch === '#') { endAuth = i; break }
        }
        if (endAuth < 0) endAuth = rest.length
        authority = rest.slice(2, endAuth)
        afterAuthority = rest.slice(endAuth)
      } else if (special) {
        // WHATWG tolerates http:/path — normalize to http://path
        if (rest.charAt(0) === '/') {
          return tgUrlParse(scheme + ':' + '/' + rest, null)
        }
        authority = ''
        afterAuthority = rest
      } else {
        // opaque path (tg://deep, mailto:...)
        var endOpaque = -1
        for (var j = 0; j < rest.length; j++) {
          var c2 = rest.charAt(j)
          if (c2 === '?' || c2 === '#') { endOpaque = j; break }
        }
        if (endOpaque < 0) endOpaque = rest.length
        opaque = rest.slice(0, endOpaque)
        afterAuthority = rest.slice(endOpaque)
      }

      var username = ''
      var password = ''
      var host = ''
      var port = ''
      if (authority) {
        var hostPart = authority
        var at = authority.lastIndexOf('@')
        if (at >= 0) {
          var userinfo = authority.slice(0, at)
          hostPart = authority.slice(at + 1)
          var colon = userinfo.indexOf(':')
          if (colon < 0) {
            username = tgUrlDecode(userinfo)
          } else {
            username = tgUrlDecode(userinfo.slice(0, colon))
            password = tgUrlDecode(userinfo.slice(colon + 1))
          }
        }
        if (hostPart.charAt(0) === '[') {
          var close = hostPart.indexOf(']')
          if (close < 0) return null
          host = hostPart.slice(0, close + 1).toLowerCase()
          var after = hostPart.slice(close + 1)
          if (after.charAt(0) === ':') port = after.slice(1)
        } else {
          var pc = hostPart.lastIndexOf(':')
          if (pc >= 0 && /^\d*$/.test(hostPart.slice(pc + 1))) {
            host = hostPart.slice(0, pc).toLowerCase()
            port = hostPart.slice(pc + 1)
          } else {
            host = hostPart.toLowerCase()
          }
        }
        if (special && !host && scheme !== 'file') return null
        if (port) {
          if (!/^\d+$/.test(port)) return null
          var portNum = parseInt(port, 10)
          if (Object.prototype.hasOwnProperty.call(SPECIAL_SCHEMES, scheme) &&
            SPECIAL_SCHEMES[scheme] === portNum) {
            port = ''
          }
        }
      }

      var endPath = -1
      for (var k = 0; k < afterAuthority.length; k++) {
        var c3 = afterAuthority.charAt(k)
        if (c3 === '?' || c3 === '#') { endPath = k; break }
      }
      if (endPath < 0) endPath = afterAuthority.length
      path = afterAuthority.slice(0, endPath)
      var tail = afterAuthority.slice(endPath)
      var query = ''
      var fragment = ''
      if (tail.charAt(0) === '?') {
        var endQuery = tail.indexOf('#')
        if (endQuery < 0) {
          query = tail.slice(1)
        } else {
          query = tail.slice(1, endQuery)
          fragment = tail.slice(endQuery + 1)
        }
      } else if (tail.charAt(0) === '#') {
        fragment = tail.slice(1)
      }

      if (opaque) {
        return {
          scheme: scheme, username: username, password: password, host: host, port: port,
          path: opaque, query: query, fragment: fragment, opaque: true,
        }
      }

      if (special) {
        path = path.replace(/\\/g, '/')
        if (path.charAt(0) !== '/') path = '/' + path
        path = tgNormalizePath(path)
      } else {
        if (authority && path && path.charAt(0) !== '/') path = '/' + path
      }

      return {
        scheme: scheme, username: username, password: password, host: host, port: port,
        path: path, query: query, fragment: fragment, opaque: false,
      }
    }

    function tgNormalizePath(path) {
      var segs = path.split('/')
      var out = []
      for (var i = 0; i < segs.length; i++) {
        var seg = segs[i]
        if (seg === '.') {
          if (i === segs.length - 1) out.push('')
          continue
        }
        if (seg === '..') {
          if (out.length > 1) out.pop()
          if (i === segs.length - 1) out.push('')
          continue
        }
        out.push(seg)
      }
      var res = out.join('/')
      if (res.charAt(0) !== '/') res = '/' + res
      return res
    }

    function defineUrlPart(proto, name, getFn, setFn) {
      Object.defineProperty(proto, name, {
        get: getFn,
        set: setFn,
        enumerable: true,
        configurable: true,
      })
    }

    defineUrlPart(TgUrl.prototype, 'protocol', function () {
      return this._scheme + ':'
    }, function (value) {
      var m = /^([a-zA-Z][a-zA-Z0-9+.-]*):?/.exec(String(value))
      if (!m) return
      var old = this._href()
      var reparsed = tgUrlParse(m[1].toLowerCase() + ':' + old.slice(old.indexOf(':') + 1), null)
      if (reparsed) tgUrlCopyParts(this, reparsed)
    })

    defineUrlPart(TgUrl.prototype, 'username', function () {
      return this._username
    }, function (value) {
      this._username = tgUrlEncodeComponent(String(value), true)
    })

    defineUrlPart(TgUrl.prototype, 'password', function () {
      return this._password
    }, function (value) {
      this._password = tgUrlEncodeComponent(String(value), true)
    })

    TgUrl.prototype._authority = function () {
      var auth = this._host
      if (this._port) auth += ':' + this._port
      var userinfo = ''
      if (this._username || this._password) {
        userinfo = this._username
        if (this._password) userinfo += ':' + this._password
        userinfo += '@'
      }
      return userinfo + auth
    }

    TgUrl.prototype._href = function () {
      var href = this._scheme + ':'
      if (this._opaque) {
        href += this._path
      } else if (this._host) {
        href += '//' + this._authority() + this._path
      } else if (Object.prototype.hasOwnProperty.call(SPECIAL_SCHEMES, this._scheme)) {
        if (this._scheme === 'file') {
          href += '//' + this._path
        } else {
          href += this._path
        }
      } else {
        href += this._path
      }
      if (this._searchParams && this._searchParams.size) {
        href += '?' + this._searchParams.toString()
      } else if (this._query) {
        href += '?' + this._query
      }
      if (this._fragment) href += '#' + this._fragment
      return href
    }

    TgUrl.prototype._hrefNoFragment = function () {
      var saved = this._fragment
      this._fragment = ''
      var href = this._href()
      this._fragment = saved
      return href
    }

    defineUrlPart(TgUrl.prototype, 'host', function () {
      return this._host + (this._port ? ':' + this._port : '')
    }, function (value) {
      var parsed = tgUrlParse('http://' + String(value), null)
      if (parsed && parsed.host) {
        this._host = parsed.host
        this._port = parsed.port
      }
    })

    defineUrlPart(TgUrl.prototype, 'hostname', function () {
      return this._host
    }, function (value) {
      var parsed = tgUrlParse('http://' + String(value), null)
      if (parsed && parsed.host) this._host = parsed.host
    })

    defineUrlPart(TgUrl.prototype, 'port', function () {
      return this._port
    }, function (value) {
      var v = String(value).replace(/^:/, '')
      if (!v) { this._port = ''; return }
      if (!/^\d+$/.test(v)) return
      var n = parseInt(v, 10)
      if (Object.prototype.hasOwnProperty.call(SPECIAL_SCHEMES, this._scheme) &&
        SPECIAL_SCHEMES[this._scheme] === n) {
        this._port = ''
      } else {
        this._port = String(n)
      }
    })

    defineUrlPart(TgUrl.prototype, 'pathname', function () {
      return this._path
    }, function (value) {
      if (this._opaque) return
      var v = String(value)
      this._path = v.charAt(0) === '/' ? tgNormalizePath(v) : '/' + tgNormalizePath(v)
    })

    TgUrl.prototype._setSearchFromParams = function (params) {
      this._query = params.toString()
      if (params._url === this) this._searchParams = params
    }

    defineUrlPart(TgUrl.prototype, 'search', function () {
      var q = this._searchParams ? this._searchParams.toString() : this._query
      return q ? '?' + q : ''
    }, function (value) {
      var v = String(value)
      if (v.charAt(0) === '?') v = v.slice(1)
      this._query = v
      this._searchParams = null
    })

    defineUrlPart(TgUrl.prototype, 'hash', function () {
      return this._fragment ? '#' + this._fragment : ''
    }, function (value) {
      var v = String(value)
      if (v.charAt(0) === '#') v = v.slice(1)
      this._fragment = v
    })

    defineUrlPart(TgUrl.prototype, 'origin', function () {
      if (Object.prototype.hasOwnProperty.call(SPECIAL_SCHEMES, this._scheme) && this._scheme !== 'file') {
        return this._scheme + '://' + this.host
      }
      return 'null'
    })

    defineUrlPart(TgUrl.prototype, 'searchParams', function () {
      if (!this._searchParams) {
        this._searchParams = new TgUrlSearchParams(this._query)
        this._searchParams._url = this
      }
      return this._searchParams
    })

    defineUrlPart(TgUrl.prototype, 'href', function () {
      return this._href()
    }, function (value) {
      var parsed = tgUrlParse(String(value), null)
      if (parsed) {
        tgUrlCopyParts(this, parsed)
      } else {
        throw new TypeError('Invalid URL: ' + value)
      }
    })

    function tgUrlCopyParts(url, parts) {
      url._scheme = parts.scheme
      url._username = parts.username
      url._password = parts.password
      url._host = parts.host
      url._port = parts.port
      url._path = parts.path
      url._query = parts.query
      url._fragment = parts.fragment
      url._opaque = parts.opaque
      url._searchParams = null
    }

    TgUrl.prototype.toString = function () {
      return this._href()
    }
    TgUrl.prototype.toJSON = function () {
      return this._href()
    }

    globalThis.URL = TgUrl
    globalThis.URLSearchParams = TgUrlSearchParams
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
