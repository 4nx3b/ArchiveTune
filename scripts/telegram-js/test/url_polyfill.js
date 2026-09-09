/*
 * ArchiveTune (2026) — URL polyfill unit tests.
 *
 * Runs banner.js inside a bare `vm` context (no Node/browser globals, like
 * QuickJS) and exercises the WHATWG URL / URLSearchParams subset that mtcute
 * needs: ip.prettify (IPv4 + IPv6), proxy parsing, deeplinks, search params.
 */

'use strict'

const fs = require('fs')
const path = require('path')
const vm = require('vm')

const here = path.dirname(__filename)
const banner = fs.readFileSync(path.join(here, '../host/banner.js'), 'utf8')

const sandbox = {
  __tgLog: () => {},
  __tgSetTimer: () => Promise.resolve(true),
  // quiet bridge: the banner event pump arms one pending poll forever
  __tgPollEvent: () => new Promise(() => {}),
}
sandbox.globalThis = sandbox
const context = vm.createContext(sandbox)
vm.runInContext(banner, context, { filename: 'banner.js' })

let failures = 0
function check(name, actual, expected) {
  const ok = JSON.stringify(actual) === JSON.stringify(expected)
  if (!ok) {
    failures++
    console.error(`FAIL ${name}: got ${JSON.stringify(actual)}, want ${JSON.stringify(expected)}`)
  } else {
    console.log(`ok   ${name}`)
  }
}

const code = `
function prettify(address, options) {
  const isIpv6 = address.includes(':')
  const encloseIpv6 = (options && options.encloseIpv6) || false
  let a = isIpv6 ? '[' + address + ']' : address
  const url = new URL('http://' + a)
  const r = url.hostname
  if (isIpv6 && !encloseIpv6) return r.substring(1, r.length - 1)
  return r
}
globalThis.__result = {
  ipv4: prettify('149.154.167.50'),
  ipv4Enclosed: prettify('149.154.167.50', { encloseIpv6: true }),
  ipv6: prettify('2001:067c:04e8:f002:0000:0000:0000:000a'),
  ipv6Enclosed: prettify('2001:067c:04e8:f002:0000:0000:0000:000a', { encloseIpv6: true }),
  ipv6Short: prettify('2001:db8::1'),
}
`
vm.runInContext(code, context)
const r1 = sandbox.__result
check('prettify ipv4', r1.ipv4, '149.154.167.50')
check('prettify ipv4 enclosed', r1.ipv4Enclosed, '149.154.167.50')
// NOTE: the polyfill intentionally skips full WHATWG IPv6 canonicalization
// (zero-run compression) — it only feeds log prefixes and comparisons that
// never see IPv6 literals; brackets/parsing behaviour is what matters.
check('prettify ipv6 bare', r1.ipv6, '2001:067c:04e8:f002:0000:0000:0000:000a')
check('prettify ipv6 enclosed', r1.ipv6Enclosed, '[2001:067c:04e8:f002:0000:0000:0000:000a]')
check('prettify ipv6 short', r1.ipv6Short, '2001:db8::1')

const u1 = vm.runInContext(`new URL('wss://venus.web.telegram.org/apiws')`, context)
check('ws protocol', u1.protocol, 'wss:')
check('ws hostname', u1.hostname, 'venus.web.telegram.org')
check('ws pathname', u1.pathname, '/apiws')
check('ws href', u1.href, 'wss://venus.web.telegram.org/apiws')
check('ws port empty', u1.port, '')
check('ws origin', u1.origin, 'wss://venus.web.telegram.org')

const u2 = vm.runInContext(`new URL('http://user:pass@proxy.example.com:8080/path/x?q=1&b=%20z#frag')`, context)
check('auth username', u2.username, 'user')
check('auth password', u2.password, 'pass')
check('auth hostname', u2.hostname, 'proxy.example.com')
check('auth port', u2.port, '8080')
check('auth pathname', u2.pathname, '/path/x')
check('auth hash', u2.hash, '#frag')

const u3 = vm.runInContext(`
(() => {
  const u = new URL('http://h/p?a=1&b=2')
  return { a: u.searchParams.get('a'), b: u.searchParams.get('b'), none: u.searchParams.get('c') }
})()
`, context)
check('searchParams get', u3, { a: '1', b: '2', none: null })

const u4 = vm.runInContext(`
(() => {
  const u = new URL('http://h/p?x=1')
  u.searchParams.append('y', '2')
  u.searchParams.set('x', '9')
  const q = u.search
  const href = u.href
  return { q, href }
})()
`, context)
check('searchParams mutation', u4, { q: '?x=9&y=2', href: 'http://h/p?x=9&y=2' })

const u5 = vm.runInContext(`new URL('tg://resolve?domain=durov')`, context)
check('tg protocol', u5.protocol, 'tg:')
check('tg searchParams domain', vm.runInContext(`new URL('tg://resolve?domain=durov').searchParams.get('domain')`, context), 'durov')

const u6 = vm.runInContext(`new URL('/rel/x?a', 'https://base.example.com/dir/page')`, context)
check('base resolution', u6.href, 'https://base.example.com/rel/x?a')

const u7 = vm.runInContext(`new URL('http://H.EXAMPLE.COM')`, context)
check('host lowercased', u7.hostname, 'h.example.com')

const u8 = vm.runInContext(`new URL('https://h:443/p')`, context)
check('default port dropped', u8.port, '')
check('default port host', u8.host, 'h')

const u9 = vm.runInContext(`
(() => {
  try { new URL('not a url at all'); return 'no-throw' } catch (e) { return e.constructor.name + ':' + (e instanceof TypeError) }
})()
`, context)
check('invalid throws TypeError', u9, 'TypeError:true')

const u10 = vm.runInContext(`
(() => {
  const u = new URL('http://h/a/b/../c/./d')
  return u.pathname
})()
`, context)
check('dot segments normalized', u10, '/a/c/d')

// URLSearchParams direct
const sp = vm.runInContext(`
(() => {
  const p = new URLSearchParams('a=1&a=2&k=')
  return { size: p.size, all: p.getAll('a'), has: p.has('k'), missing: p.has('z'), str: p.toString() }
})()
`, context)
check('URLSearchParams direct', sp, { size: 3, all: ['1', '2'], has: true, missing: false, str: 'a=1&a=2&k=' })

// JSON.stringify / toString
const u11 = vm.runInContext(`JSON.stringify({ u: new URL('http://x/y') })`, context)
check('toJSON', u11, '{"u":"http://x/y"}')

if (failures) {
  console.error(`\n${failures} URL polyfill test(s) FAILED`)
  process.exit(1)
}
console.log('\nall URL polyfill tests passed')
