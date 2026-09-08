/*
 * ArchiveTune (2026) — mtcute host glue (bundled after banner.js by build.sh).
 *
 * Bridges the mtcute MTProto client to Kotlin via the global bindings set up by
 * TgJsRuntime (crypto/storage/timers/WebSocket/event pump — see banner.js).
 *
 * Exposes to Kotlin:
 *   globalThis.__tgApiCall(method, paramsJson) -> Promise<string>  JSON envelope
 *   globalThis.__tgApiCallBin(method, paramsJson) -> Promise<Int8Array>
 *
 * Client events are pushed to Kotlin through __tgOnClientEvent(type, json)
 * (a binding defined by TgJsRuntime before this bundle is evaluated).
 *
 * mtcute is MIT licensed (https://github.com/mtcute/mtcute) — see the
 * THIRD-PARTY notice in the generated asset header.
 */

import Long from 'long'

import { TelegramClient } from '@mtcute/core/client.js'
import {
  IntermediatePacketCodec,
  ObfuscatedPacketCodec,
  getMarkedPeerId,
  type ICorePlatform,
  type IStorageDriver,
  type ITelegramStorageProvider,
} from '@mtcute/core'
import type { ICryptoProvider, Logger } from '@mtcute/core/utils.js'

import { connectWs } from '@fuman/net'

// ---------------------------------------------------------------------------
// small binary helpers (bridge boundary is always Int8Array)
// ---------------------------------------------------------------------------

function toI8(u8: Uint8Array): Int8Array {
  return new Int8Array(u8.buffer, u8.byteOffset, u8.byteLength)
}

function toU8(i8: Int8Array | null | undefined): Uint8Array {
  if (i8 == null) return new Uint8Array(0)
  return new Uint8Array(i8.buffer, i8.byteOffset, i8.byteLength)
}

/** int64 (Long | bigint | number) -> unsigned decimal string */
function longToU64String(value: unknown): string {
  if (value == null) return '0'
  if (typeof value === 'bigint') {
    return (value < 0n ? value + (1n << 64n) : value).toString()
  }
  if (typeof value === 'number') {
    return longToU64String(BigInt(Math.trunc(value)))
  }
  const anyLong = value as { toUnsigned?: () => { toString: () => string }; toString: () => string }
  if (typeof anyLong.toUnsigned === 'function') {
    return anyLong.toUnsigned().toString()
  }
  const text = anyLong.toString()
  if (text.startsWith('-')) {
    return (BigInt(text) + (1n << 64n)).toString()
  }
  return text
}

/** unsigned decimal string -> signed-bits Long */
function u64ToLong(text: string): Long {
  return Long.fromString(String(text), true)
}

const B64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/'

function bytesToB64(bytes: Uint8Array): string {
  let out = ''
  for (let i = 0; i < bytes.length; i += 3) {
    const b0 = bytes[i]
    const b1 = bytes[i + 1]
    const b2 = bytes[i + 2]
    out += B64[b0 >> 2]
    out += B64[((b0 & 3) << 4) | ((b1 ?? 0) >> 4)]
    out += b1 === undefined ? '=' : B64[((b1 & 15) << 2) | ((b2 ?? 0) >> 6)]
    out += b2 === undefined ? '=' : B64[b2! & 63]
  }
  return out
}

function b64ToBytes(text: string): Uint8Array {
  const clean = text.replace(/=+$/, '')
  const len = Math.floor((clean.length * 6) / 8)
  const out = new Uint8Array(len)
  let bits = 0
  let acc = 0
  let pos = 0
  for (let i = 0; i < clean.length; i++) {
    const v = B64.indexOf(clean[i])
    if (v < 0) continue
    acc = (acc << 6) | v
    bits += 6
    if (bits >= 8) {
      bits -= 8
      if (pos < len) out[pos++] = (acc >> bits) & 0xff
    }
  }
  return out
}

function textEncoder(): TextEncoder {
  return new TextEncoder()
}

function textDecoder(): TextDecoder {
  return new TextDecoder()
}

// ---------------------------------------------------------------------------
// crypto provider (native, via bridge)
// ---------------------------------------------------------------------------

class BridgeCryptoProvider implements ICryptoProvider {
  initialize(): void {}

  sha1(data: Uint8Array): Uint8Array {
    return toU8(globalThis.__tgSha1(toI8(data)))
  }

  sha256(data: Uint8Array): Uint8Array {
    return toU8(globalThis.__tgSha256(toI8(data)))
  }

  hmacSha256(data: Uint8Array, key: Uint8Array): Uint8Array {
    return toU8(globalThis.__tgHmacSha256(toI8(data), toI8(key)))
  }

  pbkdf2(password: Uint8Array, salt: Uint8Array, iterations: number, keylen = 64, algo = 'sha1'): Uint8Array {
    return toU8(globalThis.__tgPbkdf2(toI8(password), toI8(salt), iterations, keylen, algo))
  }

  createAesCtr(key: Uint8Array, iv: Uint8Array, encrypt: boolean) {
    const handle = globalThis.__tgAesCtrOpen(toI8(key), toI8(iv), encrypt)
    return {
      process: (data: Uint8Array): Uint8Array => toU8(globalThis.__tgAesCtrProcess(handle, toI8(data))),
      close: () => globalThis.__tgAesCtrClose(handle),
    }
  }

  createAesIge(key: Uint8Array, iv: Uint8Array) {
    return {
      encrypt: (data: Uint8Array): Uint8Array =>
        toU8(globalThis.__tgAesIge(true, toI8(key), toI8(iv), toI8(data))),
      decrypt: (data: Uint8Array): Uint8Array =>
        toU8(globalThis.__tgAesIge(false, toI8(key), toI8(iv), toI8(data))),
    }
  }

  factorizePQ(pq: Uint8Array): [Uint8Array, Uint8Array] {
    const res = globalThis.__tgFactorizePq(toI8(pq)) as Int8Array[]
    return [toU8(res[0]), toU8(res[1])]
  }

  gzip(data: Uint8Array, maxSize: number): Uint8Array | null {
    const out = globalThis.__tgGzip(toI8(data), maxSize) as Int8Array | null
    return out == null ? null : toU8(out)
  }

  gunzip(data: Uint8Array): Uint8Array {
    return toU8(globalThis.__tgGunzip(toI8(data)))
  }

  randomFill(buf: Uint8Array): void {
    const rnd = toU8(globalThis.__tgRandomBytes(buf.length) as Int8Array)
    buf.set(rnd)
  }

  randomBytes(size: number): Uint8Array {
    return toU8(globalThis.__tgRandomBytes(size) as Int8Array)
  }
}

// ---------------------------------------------------------------------------
// storage provider (file-backed via bridge, write-through)
// ---------------------------------------------------------------------------

const STORES = ['kv', 'authKeys', 'authKeysTemp', 'peers', 'refMessages'] as const
type Store = (typeof STORES)[number]

interface StoredPeerInfo {
  id: number
  accessHash: string
  isMin: boolean
  usernames: string[]
  updated: number
  phone?: string
  complete: string
}

interface StoredRefMessage {
  chatId: number
  msgId: number
}

interface StoredTempKey {
  key: string
  expires: number
}

class BridgeStorageDriver implements IStorageDriver {
  private data: Map<Store, Map<string, Uint8Array>> = new Map()

  async load(): Promise<void> {
    for (const store of STORES) {
      const entries = (await globalThis.__tgStoreLoadAll(store)) as unknown[]
      const map = new Map<string, Uint8Array>()
      for (const entry of entries ?? []) {
        if (Array.isArray(entry) && entry.length >= 2) {
          map.set(String(entry[0]), toU8(entry[1] as Int8Array))
        }
      }
      this.data.set(store, map)
    }
  }

  async save(): Promise<void> {
    // write-through storage: nothing to flush
  }

  async destroy(): Promise<void> {
    this.data.clear()
  }

  setup(_log: Logger, _platform: ICorePlatform): void {}

  private store(name: Store): Map<string, Uint8Array> {
    let map = this.data.get(name)
    if (!map) {
      map = new Map()
      this.data.set(name, map)
    }
    return map
  }

  async getRaw(store: Store, key: string): Promise<Uint8Array | null> {
    return this.store(store).get(key) ?? null
  }

  async setRaw(store: Store, key: string, value: Uint8Array): Promise<void> {
    this.store(store).set(key, value)
    await globalThis.__tgStoreSet(store, key, toI8(value))
  }

  async deleteRaw(store: Store, key: string): Promise<void> {
    this.store(store).delete(key)
    await globalThis.__tgStoreDelete(store, key)
  }

  async clearRaw(store: Store): Promise<void> {
    this.store(store).clear()
    await globalThis.__tgStoreClear(store)
  }

  async clearAllRaw(): Promise<void> {
    for (const store of STORES) {
      this.store(store).clear()
    }
    if (typeof globalThis.__tgStoreClearAll === 'function') {
      await globalThis.__tgStoreClearAll()
    }
  }

  entries(store: Store): [string, Uint8Array][] {
    return Array.from(this.store(store).entries())
  }
}

const driver = new BridgeStorageDriver()

function decodeJson<T>(bytes: Uint8Array): T {
  return JSON.parse(textDecoder().decode(bytes)) as T
}

function encodeJson(value: unknown): Uint8Array {
  return textEncoder().encode(JSON.stringify(value))
}

async function clearTempByDc(dc: number): Promise<void> {
  for (const [key] of driver.entries('authKeysTemp')) {
    if (key.startsWith(`${dc}:`)) {
      await driver.deleteRaw('authKeysTemp', key)
    }
  }
}

const storage: ITelegramStorageProvider = {
  driver,
  kv: {
    set: (key: string, value: Uint8Array) => driver.setRaw('kv', key, value),
    get: async (key: string) => driver.getRaw('kv', key),
    delete: (key: string) => driver.deleteRaw('kv', key),
    deleteAll: () => driver.clearRaw('kv'),
  },
  authKeys: {
    set: (dc: number, key: Uint8Array | null) =>
      key == null ? driver.deleteRaw('authKeys', String(dc)) : driver.setRaw('authKeys', String(dc), key),
    get: async (dc: number) => driver.getRaw('authKeys', String(dc)),
    setTemp: (dc: number, idx: number, key: Uint8Array | null, expires: number) => {
      const mapKey = `${dc}:${idx}`
      if (key == null) {
        return driver.deleteRaw('authKeysTemp', mapKey)
      }
      const entry: StoredTempKey = { key: bytesToB64(key), expires }
      return driver.setRaw('authKeysTemp', mapKey, encodeJson(entry))
    },
    getTemp: async (dc: number, idx: number, now: number) => {
      const raw = await driver.getRaw('authKeysTemp', `${dc}:${idx}`)
      if (raw == null) return null
      const entry = decodeJson<StoredTempKey>(raw)
      if (entry.expires <= now) return null
      return b64ToBytes(entry.key)
    },
    deleteByDc: (dc: number) => {
      void driver.deleteRaw('authKeys', String(dc))
      void clearTempByDc(dc)
    },
    deleteAll: () => {
      void driver.clearRaw('authKeys')
      void driver.clearRaw('authKeysTemp')
    },
  },
  peers: {
    store: (peer) => {
      const info: StoredPeerInfo = {
        id: peer.id,
        accessHash: peer.accessHash,
        isMin: peer.isMin,
        usernames: peer.usernames,
        updated: peer.updated,
        phone: peer.phone,
        complete: bytesToB64(peer.complete),
      }
      return driver.setRaw('peers', `p:${peer.id}`, encodeJson(info))
    },
    getById: async (id: number) => {
      const raw = await driver.getRaw('peers', `p:${id}`)
      if (raw == null) return null
      const info = decodeJson<StoredPeerInfo>(raw)
      return { ...info, complete: b64ToBytes(info.complete) }
    },
    getByUsername: async (username: string) => {
      for (const [, raw] of driver.entries('peers')) {
        const info = decodeJson<StoredPeerInfo>(raw)
        if (info.usernames.includes(username) && !info.isMin) {
          return { ...info, complete: b64ToBytes(info.complete) }
        }
      }
      return null
    },
    getByPhone: async (phone: string) => {
      for (const [, raw] of driver.entries('peers')) {
        const info = decodeJson<StoredPeerInfo>(raw)
        if (info.phone === phone && !info.isMin) {
          return { ...info, complete: b64ToBytes(info.complete) }
        }
      }
      return null
    },
    deleteAll: () => driver.clearRaw('peers'),
  },
  refMessages: {
    store: (peerId: number, chatId: number, msgId: number) => {
      const entry: StoredRefMessage = { chatId, msgId }
      return driver.setRaw('refMessages', `r:${peerId}`, encodeJson(entry))
    },
    getByPeer: async (peerId: number) => {
      const raw = await driver.getRaw('refMessages', `r:${peerId}`)
      if (raw == null) return null
      const entry = decodeJson<StoredRefMessage>(raw)
      return [entry.chatId, entry.msgId] as [number, number]
    },
    delete: (chatId: number, msgIds: number[]) => {
      for (const [key, raw] of driver.entries('refMessages')) {
        const entry = decodeJson<StoredRefMessage>(raw)
        if (entry.chatId === chatId && msgIds.includes(entry.msgId)) {
          void driver.deleteRaw('refMessages', key)
        }
      }
    },
    deleteByPeer: (peerId: number) => driver.deleteRaw('refMessages', `r:${peerId}`),
    deleteAll: () => driver.clearRaw('refMessages'),
  },
}

// ---------------------------------------------------------------------------
// platform
// ---------------------------------------------------------------------------

class BridgePlatform implements ICorePlatform {
  private readonly deviceModel: string

  constructor(deviceModel: string) {
    this.deviceModel = deviceModel
  }

  beforeExit(fn: () => void): () => void {
    return () => void fn
  }

  log(_color: number, level: number, tag: string, fmt: string, args: unknown[]): void {
    globalThis.__tgLog(level, tag, fmt + (args.length ? ' ' + args.join(' ') : ''))
  }

  getDefaultLogLevel(): number | null {
    return 2
  }

  getDeviceModel(): string {
    return this.deviceModel
  }
}

// ---------------------------------------------------------------------------
// transport (WebSocket over the bridge, same wire protocol as web clients)
// ---------------------------------------------------------------------------

const WS_SUBDOMAINS: Record<number, string> = {
  1: 'pluto',
  2: 'venus',
  3: 'aurora',
  4: 'vesta',
  5: 'flora',
}

class BridgeWebSocketTransport {
  async connect(dc: { id: number; testMode?: boolean }, abortSignal: AbortSignal) {
    const subdomain = WS_SUBDOMAINS[dc.id] ?? 'venus'
    const url = `wss://${subdomain}.web.telegram.org/apiws${dc.testMode ? '_test' : ''}`
    return connectWs({ url, protocols: 'binary' }, abortSignal)
  }

  packetCodec() {
    return new ObfuscatedPacketCodec(new IntermediatePacketCodec())
  }
}

// ---------------------------------------------------------------------------
// client lifecycle
// ---------------------------------------------------------------------------

interface TrackJson {
  chatId: number
  messageId: number
  docId: string
  accessHash: string
  fileReference: string
  dcId: number
  uniqueFileId: string
  title: string
  performer: string | null
  fileName: string
  mimeType: string
  durationSeconds: number
  sizeBytes: number
  dateSeconds: number
  albumCoverStripped: string | null
  hasThumbnail: boolean
  isAudio: boolean
}

interface CachedFile {
  location: Record<string, unknown>
  dcId: number
  fileSize: number
}

let client: TelegramClient | null = null
let lastPhone: string | null = null
let lastPhoneCodeHash: string | null = null
const fileCache = new Map<string, CachedFile>()
let newMessageListenerAttached = false

async function ensureClient(params: Record<string, unknown>): Promise<TelegramClient> {
  if (client) return client
  const deviceModel = String(params.deviceModel ?? 'ArchiveTune')
  client = new TelegramClient({
    apiId: Number(params.apiId),
    apiHash: String(params.apiHash),
    crypto: new BridgeCryptoProvider(),
    transport: new BridgeWebSocketTransport() as never,
    platform: new BridgePlatform(deviceModel),
    storage,
  })
  await client.connect()
  if (!newMessageListenerAttached) {
    newMessageListenerAttached = true
    client.onNewMessage.add((message) => {
      try {
        pushNewMessage(message)
      } catch (e) {
        globalThis.__tgLog(40, 'updates', String(e))
      }
    })
  }
  return client
}

function ensureClientAsAny(): any {
  if (!client) throw new Error('client is not initialized')
  return client
}

// ---------------------------------------------------------------------------
// message -> track mapping
// ---------------------------------------------------------------------------

function docThumbStripped(doc: any): string | null {
  const thumbs = doc?.thumbs
  if (!Array.isArray(thumbs)) return null
  for (const thumb of thumbs) {
    if (thumb?._ === 'photoStrippedSize') {
      return bytesToB64(new Uint8Array(thumb.bytes ?? []))
    }
  }
  return null
}

function docHasDownloadableThumb(doc: any): boolean {
  const thumbs = doc?.thumbs
  if (!Array.isArray(thumbs)) return false
  return thumbs.some(
    (thumb: any) => thumb?._ === 'photoSize' && thumb.type && thumb.type !== 'j' && thumb.type !== 'k',
  )
}

const AUDIO_EXTENSIONS = [
  'flac', 'wav', 'wave', 'aiff', 'aif', 'ape', 'alac', 'wv', 'tak', 'tta',
  'dsf', 'dff', 'shn', 'mp3', 'm4a', 'aac', 'ogg', 'oga', 'opus', 'wma', 'mka',
]

function isAudioDocument(mimeType: string, fileName: string): boolean {
  if (mimeType.startsWith('audio/')) return true
  const ext = fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase()
  return AUDIO_EXTENSIONS.includes(ext)
}

function trackFromRawMessage(message: any, requireAudioAttribute: boolean): TrackJson | null {
  if (message?._ !== 'message') return null
  const media = message.media
  if (media?._ !== 'messageMediaDocument') return null
  const doc = media.document
  if (doc?._ !== 'document') return null

  let title = ''
  let performer: string | null = null
  let duration = 0
  let isAudioAttribute = false
  let fileName = ''
  for (const attr of doc.attributes ?? []) {
    if (attr?._ === 'documentAttributeAudio') {
      title = String(attr.title ?? '')
      performer = attr.performer == null ? null : String(attr.performer)
      duration = Number(attr.duration ?? 0)
      isAudioAttribute = true
    } else if (attr?._ === 'documentAttributeFilename') {
      fileName = String(attr.fileName ?? '')
    }
  }

  const mimeType = String(doc.mimeType ?? '')
  if (!fileName) fileName = `file_${doc.id}.bin`
  if (requireAudioAttribute && !isAudioAttribute) return null
  if (!isAudioAttribute && !isAudioDocument(mimeType, fileName)) return null

  return {
    chatId: getMarkedPeerId(message.peerId),
    messageId: Number(message.id),
    docId: longToU64String(doc.id),
    accessHash: longToU64String(doc.accessHash),
    fileReference: bytesToB64(new Uint8Array(doc.fileReference ?? [])),
    dcId: Number(doc.dcId ?? 2),
    uniqueFileId: `${longToU64String(doc.id)}:${doc.dcId}`,
    title,
    performer,
    fileName,
    mimeType,
    durationSeconds: duration,
    sizeBytes: Number(doc.size ?? 0),
    dateSeconds: Number(message.date ?? 0),
    albumCoverStripped: docThumbStripped(doc),
    hasThumbnail: docHasDownloadableThumb(doc),
    isAudio: isAudioAttribute,
  }
}

// ---------------------------------------------------------------------------
// file downloads (precise chunks, TDLib ReadFilePart parity)
// ---------------------------------------------------------------------------

function fileCacheKey(chatId: number, messageId: number, thumb: boolean): string {
  return `${chatId}:${messageId}:${thumb ? 'thumb' : 'file'}`
}

async function resolveFile(chatId: number, messageId: number, thumb: boolean): Promise<CachedFile> {
  const key = fileCacheKey(chatId, messageId, thumb)
  const cached = fileCache.get(key)
  if (cached) return cached

  const active = ensureClientAsAny()
  const messages = (await active.getMessages(chatId, [messageId])) as any[]
  const message = messages?.[0]
  if (!message || !message.raw) throw new Error(`message ${chatId}/${messageId} not found`)
  const raw = message.raw
  const media = raw?.media
  if (media?._ !== 'messageMediaDocument' || media.document?._ !== 'document') {
    throw new Error('message has no document')
  }
  const doc = media.document

  let thumbSize = ''
  if (thumb) {
    const thumbs = Array.isArray(doc.thumbs) ? doc.thumbs : []
    const photo = thumbs.find(
      (t: any) => t?._ === 'photoSize' && t.type && t.type !== 'j' && t.type !== 'k',
    )
    if (!photo) throw new Error('document has no downloadable thumbnail')
    thumbSize = String(photo.type)
  }

  const cachedFile: CachedFile = {
    location: {
      _: 'inputDocumentFileLocation',
      id: u64ToLong(longToU64String(doc.id)),
      accessHash: u64ToLong(longToU64String(doc.accessHash)),
      fileReference: new Uint8Array(doc.fileReference ?? []),
      thumbSize,
    },
    dcId: Number(doc.dcId ?? 2),
    fileSize: Number(doc.size ?? 0),
  }
  fileCache.set(key, cachedFile)
  return cachedFile
}

function isFileReferenceError(error: any): boolean {
  const text = String(error?.text ?? error?.message ?? '')
  return text.includes('FILE_REFERENCE') || text.includes('FILE_MIGRATE')
}

function dropFileCache(chatId: number, messageId: number): void {
  fileCache.delete(fileCacheKey(chatId, messageId, false))
  fileCache.delete(fileCacheKey(chatId, messageId, true))
}

async function readFilePart(
  chatId: number,
  messageId: number,
  thumb: boolean,
  offset: number,
  limit: number,
): Promise<Uint8Array> {
  const active = ensureClientAsAny()
  let file: CachedFile
  try {
    file = await resolveFile(chatId, messageId, thumb)
  } catch (e) {
    dropFileCache(chatId, messageId)
    throw e
  }
  for (let attempt = 0; attempt < 3; attempt++) {
    try {
      return await active.downloadChunk({
        location: file.location as never,
        dcId: file.dcId,
        offset,
        limit,
      })
    } catch (error) {
      if (isFileReferenceError(error)) {
        dropFileCache(chatId, messageId)
        file = await resolveFile(chatId, messageId, thumb)
        continue
      }
      throw error
    }
  }
  throw new Error('readFilePart exhausted retries')
}

async function downloadFullFile(chatId: number, messageId: number, thumb: boolean): Promise<Uint8Array> {
  const file = await resolveFile(chatId, messageId, thumb)
  if (file.fileSize <= 0) return new Uint8Array(0)
  const chunks: Uint8Array[] = []
  const CHUNK = 512 * 1024
  let offset = 0
  while (offset < file.fileSize && chunks.length < 64) {
    const limit = Math.min(CHUNK, file.fileSize - offset)
    const part = await readFilePart(chatId, messageId, thumb, offset, limit)
    chunks.push(part)
    offset += part.length
    if (part.length === 0) break
  }
  const total = chunks.reduce((sum, c) => sum + c.length, 0)
  const out = new Uint8Array(total)
  let pos = 0
  for (const chunk of chunks) {
    out.set(chunk, pos)
    pos += chunk.length
  }
  return out
}

// ---------------------------------------------------------------------------
// client events -> Kotlin
// ---------------------------------------------------------------------------

interface PromptButtonJson {
  text: string
  callbackData?: string
  url?: string
}

function promptFromRawMessage(message: any): { text: string; rows: PromptButtonJson[][] } | null {
  const markup = message?.replyMarkup
  if (markup?._ !== 'replyInlineMarkup') return null
  const rows: PromptButtonJson[][] = []
  for (const row of markup.rows ?? []) {
    const buttons: PromptButtonJson[] = []
    for (const button of row?.buttons ?? []) {
      const text = String(button?.text ?? '').trim()
      if (!text) continue
      if (button._ === 'keyboardButtonCallback') {
        buttons.push({ text, callbackData: bytesToB64(new Uint8Array(button.data ?? [])) })
      } else if (button._ === 'keyboardButtonUrl') {
        buttons.push({ text, url: String(button.url ?? '') })
      }
    }
    if (buttons.length) rows.push(buttons)
  }
  if (!rows.length) return null
  const textContent = message?.content
  const text = textContent?._ === 'messageText' ? String(textContent?.text?.text ?? '') : ''
  return { text, rows }
}

function pushNewMessage(message: any): void {
  try {
    const raw = message?.raw
    if (!raw || raw._ !== 'message') return
    const chatId = Number(message?.chat?.id ?? getMarkedPeerId(raw.peerId))
    const messageId = Number(message?.id ?? raw.id ?? 0)
    if (!chatId || !messageId) return
    const track = trackFromRawMessage(raw, false)
    const prompt = track ? null : promptFromRawMessage(raw)
    if (!track && !prompt) return
    globalThis.__tgOnClientEvent(
      'newMessage',
      JSON.stringify({ chatId, messageId, track, prompt }),
    )
  } catch (e) {
    globalThis.__tgLog(40, 'events', String(e))
  }
}

// ---------------------------------------------------------------------------
// search helpers
// ---------------------------------------------------------------------------

function chatEntryFromRawChannel(raw: any, markedChatId: number): Record<string, unknown> {
  const usernames = Array.isArray(raw?.usernames)
    ? raw.usernames
        .filter((u: any) => u?._ === 'username' && u.flags?.active)
        .map((u: any) => String(u.username))
    : raw?.username
      ? [String(raw.username)]
      : []
  const photo = raw?.photo?._ === 'chatPhoto' ? raw.photo : null
  return {
    chatId: markedChatId,
    title: String(raw?.title ?? ''),
    username: usernames[0] ?? null,
    memberCount: Number(raw?.participantsCount ?? 0),
    isBroadcastChannel: Boolean(raw?.broadcast),
    isSupergroup: !raw?.broadcast,
    photoStripped: photo?.strippedThumb ? bytesToB64(new Uint8Array(photo.strippedThumb)) : null,
    photoDownloadable: photo != null,
  }
}

function chatEntryFromChatDto(chat: any): Record<string, unknown> {
  const type = String(chat?.type ?? '')
  const isChannel = type === 'channel'
  const isSupergroup = type === 'supergroup'
  const usernames = (chat?.usernames ?? []) as Array<{ username: string }>
  const photoThumb = chat?.photo?.thumb
  return {
    chatId: Number(chat?.id ?? 0),
    title: String(chat?.title ?? chat?.firstName ?? ''),
    username: usernames[0]?.username ?? chat?.username ?? null,
    memberCount: Number(chat?.membersCount ?? 0),
    isBroadcastChannel: isChannel,
    isSupergroup: isSupergroup || isChannel,
    photoStripped: photoThumb ? bytesToB64(new Uint8Array(photoThumb)) : null,
    photoDownloadable: Boolean(chat?.photo),
  }
}

// ---------------------------------------------------------------------------
// API method handlers (JSON protocol used by the Kotlin layer)
// ---------------------------------------------------------------------------

function errorInfo(error: any): { code: number; message: string } {
  const message = String(error?.text ?? error?.message ?? String(error))
  const code = Number(error?.code ?? 0) || (/^4\d\d/.test(message) ? 400 : 0)
  return { code, message }
}

const handlers: Record<string, (params: any) => Promise<unknown> | unknown> = {
  async init(params) {
    await ensureClient(params)
    const active = ensureClientAsAny()
    try {
      const me = await active.getMe()
      return {
        initialized: true,
        authorized: true,
        me: {
          id: Number(me.id),
          firstName: me.firstName ?? '',
          lastName: me.lastName ?? '',
          username: me.username ?? null,
          phoneNumber: me.phoneNumber ?? null,
          isBot: Boolean(me.isBot),
        },
      }
    } catch (error: any) {
      const info = errorInfo(error)
      const unregistered =
        info.message.includes('AUTH_KEY_UNREGISTERED') ||
        info.message.includes('AUTH_KEY_DUPLICATED') ||
        info.message.includes('SESSION_REVOKED') ||
        info.message.includes('USER_DEACTIVATED')
      return {
        initialized: true,
        authorized: false,
        me: null,
        warning: unregistered ? null : info.message,
      }
    }
  },

  async sendCode(params) {
    const active = ensureClientAsAny()
    const phone = String(params.phone ?? '')
    const result = await active.sendCode({ phone })
    lastPhone = phone
    if (!result || typeof (result as any).phoneCodeHash !== 'string') {
      // already authorized — sendCode returned the current User
      return { authorized: true }
    }
    lastPhoneCodeHash = (result as any).phoneCodeHash
    return {
      phoneCodeHash: (result as any).phoneCodeHash,
      type: (result as any).type,
      nextType: (result as any).nextType,
      timeout: Number((result as any).timeout ?? 0),
      length: Number((result as any).length ?? 0),
    }
  },

  async signIn(params) {
    const active = ensureClientAsAny()
    const phone = String(params.phone ?? lastPhone ?? '')
    const phoneCodeHash = String(params.phoneCodeHash ?? lastPhoneCodeHash ?? '')
    try {
      await active.signIn({ phone, phoneCodeHash, phoneCode: String(params.code ?? '') })
      return { ok: true, needsPassword: false }
    } catch (error: any) {
      const info = errorInfo(error)
      if (info.message.includes('SESSION_PASSWORD_NEEDED')) {
        return { ok: false, needsPassword: true }
      }
      throw error
    }
  },

  async getPasswordHint() {
    const active = ensureClientAsAny()
    const hint = await active.getPasswordHint()
    return { hint: hint ?? null }
  },

  async checkPassword(params) {
    const active = ensureClientAsAny()
    await active.checkPassword(String(params.password ?? ''))
    return { ok: true }
  },

  async resendCode(params) {
    const active = ensureClientAsAny()
    const phone = String(params.phone ?? lastPhone ?? '')
    const phoneCodeHash = String(params.phoneCodeHash ?? lastPhoneCodeHash ?? '')
    const result = await active.resendCode({ phone, phoneCodeHash })
    if (result && typeof (result as any).phoneCodeHash === 'string') {
      lastPhoneCodeHash = (result as any).phoneCodeHash
    }
    return {
      phoneCodeHash: (result as any)?.phoneCodeHash ?? phoneCodeHash,
      type: (result as any)?.type,
      nextType: (result as any)?.nextType,
      timeout: Number((result as any)?.timeout ?? 0),
    }
  },

  async logOut() {
    const active = ensureClientAsAny()
    await active.logOut()
    fileCache.clear()
    await driver.clearAllRaw()
    return { ok: true }
  },

  async getMe() {
    const active = ensureClientAsAny()
    const me = await active.getMe()
    return {
      id: Number(me.id),
      firstName: me.firstName ?? '',
      lastName: me.lastName ?? '',
      username: me.username ?? null,
      phoneNumber: me.phoneNumber ?? null,
      isBot: Boolean(me.isBot),
    }
  },

  async searchChats(params) {
    const active = ensureClientAsAny()
    const query = String(params.query ?? '').trim()
    const results: Record<string, unknown>[] = []

    const inviteMatch = query.match(
      /((?:https?:\/\/)?t(?:elegram)?\.me\/(?:\+|joinchat\/|add\/)[A-Za-z0-9_-]+)/i,
    )
    if (inviteMatch) {
      const link = inviteMatch[1].startsWith('http') ? inviteMatch[1] : `https://${inviteMatch[1]}`
      try {
        const join = await active.joinChat(link)
        if (join?.status === 'ok' && join.chat) {
          results.push(chatEntryFromChatDto(join.chat))
        }
      } catch (e) {
        globalThis.__tgLog(30, 'search', `invite join failed: ${errorInfo(e).message}`)
      }
    }

    if (results.length === 0) {
      const usernameMatch = query.match(/(?:https?:\/\/)?t(?:elegram)?\.me\/([A-Za-z0-9_]{3,})/i)
      const username = (
        usernameMatch ? usernameMatch[1] : query.startsWith('@') ? query.slice(1) : query
      ).trim()
      if (username && /^[A-Za-z0-9_]{3,}$/.test(username)) {
        try {
          const chat = await active.getChat(username)
          if (chat) results.push(chatEntryFromChatDto(chat))
        } catch (e) {
          globalThis.__tgLog(30, 'search', `resolve ${username} failed: ${errorInfo(e).message}`)
        }
      }
    }

    if (results.length === 0) {
      try {
        const found = await active.call({
          _: 'contacts.search',
          q: query,
          limit: Math.max(1, Math.min(30, Number(params.limit ?? 15))),
        })
        const rawChats: any[] = []
        for (const peer of [...(found.myResults ?? []), ...(found.results ?? [])]) {
          if (peer?._ === 'peerChannel') {
            const raw = (found.chats ?? []).find((c: any) => c?._ === 'channel' && c.id === peer.channelId)
            if (raw) rawChats.push(raw)
          } else if (peer?._ === 'peerChat') {
            const raw = (found.chats ?? []).find((c: any) => c?._ === 'chat' && c.id === peer.chatId)
            if (raw) rawChats.push(raw)
          }
        }
        for (const raw of rawChats) {
          const marked =
            raw._ === 'channel'
              ? getMarkedPeerId({ _: 'peerChannel', channelId: raw.id })
              : -raw.id
          results.push(chatEntryFromRawChannel(raw, marked))
        }
      } catch (e) {
        globalThis.__tgLog(30, 'search', `contacts.search failed: ${errorInfo(e).message}`)
      }
    }

    const seen = new Set<number>()
    const unique = results.filter((entry) => {
      const id = Number(entry.chatId)
      if (seen.has(id)) return false
      seen.add(id)
      return true
    })
    return { results: unique }
  },

  async getChat(params) {
    const active = ensureClientAsAny()
    const chat = await active.getChat(Number(params.chatId))
    return chatEntryFromChatDto(chat)
  },

  async openChat() {
    return { ok: true }
  },

  async fetchAudioPage(params) {
    const active = ensureClientAsAny()
    const chatId = Number(params.chatId)
    const fromMessageId = Number(params.fromMessageId ?? 0)
    const limit = Math.max(1, Math.min(100, Number(params.limit ?? 100)))
    const isMusicFilter = params.filter !== 'document'
    const filter = isMusicFilter ? 'inputMessagesFilterMusic' : 'inputMessagesFilterDocument'
    const peer = await active.resolvePeer(chatId)
    const found = await active.call({
      _: 'messages.search',
      peer,
      q: '',
      filter: { _: filter },
      minDate: 0,
      maxDate: 0,
      offsetId: fromMessageId,
      addOffset: 0,
      limit,
      maxId: 0,
      minId: 0,
      hash: Long.ZERO,
    })
    const messages = (found?.messages ?? []) as any[]
    const tracks = messages
      .map((message) => trackFromRawMessage(message, isMusicFilter))
      .filter((track): track is TrackJson => track !== null)
    const nextFromMessageId = messages.length ? Number(messages[messages.length - 1].id) : 0
    return { tracks, nextFromMessageId }
  },

  async getHistoryHasMessages(params) {
    const active = ensureClientAsAny()
    const chatId = Number(params.chatId)
    const peer = await active.resolvePeer(chatId)
    const history = await active.call({
      _: 'messages.getHistory',
      peer,
      offsetId: 0,
      offsetDate: 0,
      addOffset: 0,
      limit: Math.max(1, Math.min(100, Number(params.limit ?? 100))),
      maxId: 0,
      minId: 0,
      hash: Long.ZERO,
    })
    return { hasMessages: (history?.messages ?? []).length > 0 }
  },

  async resolveTrack(params) {
    const active = ensureClientAsAny()
    const chatId = Number(params.chatId)
    const messageId = Number(params.messageId)
    const messages = (await active.getMessages(chatId, [messageId])) as any[]
    const message = messages?.[0]
    if (!message || !message.raw) return { track: null }
    const track = trackFromRawMessage(message.raw, false)
    return { track }
  },

  async resolveBot(params) {
    const active = ensureClientAsAny()
    const username = String(params.username ?? '')
      .replace(/^@/, '')
      .trim()
      .toLowerCase()
    if (!username) return { chatId: 0, userId: 0, firstName: '', isBot: false }
    const inputUser = await active.resolveUser(username)
    if (inputUser?._ !== 'inputUser') {
      return { chatId: 0, userId: 0, firstName: '', isBot: false }
    }
    const users = await active.call({ _: 'users.getUsers', id: [inputUser] })
    const user = (users ?? [])[0]
    const photoThumb = user?.photo?.strippedThumb
    return {
      chatId: Number(inputUser.userId),
      userId: Number(inputUser.userId),
      firstName: String(user?.firstName ?? ''),
      isBot: Boolean(user?.bot),
      photoStripped: photoThumb ? bytesToB64(new Uint8Array(photoThumb)) : null,
    }
  },

  async fetchBotCommands(params) {
    const active = ensureClientAsAny()
    const chatId = Number(params.chatId)
    const inputUser = await active.resolveUser(chatId)
    if (inputUser?._ !== 'inputUser') return { commands: [] }
    const full = await active.call({ _: 'users.getFullUser', id: inputUser })
    const commands = (full?.fullUser?.botInfo?.commands ?? []).map((command: any) => ({
      command: String(command?.command ?? ''),
      description: String(command?.description ?? ''),
    }))
    return { commands }
  },

  async sendTextMessage(params) {
    const active = ensureClientAsAny()
    const message = await active.sendText(Number(params.chatId), String(params.text ?? ''))
    return { messageId: Number(message.id), date: Math.floor(message.date.getTime() / 1000) }
  },

  async pressInlineButton(params) {
    const active = ensureClientAsAny()
    const chatId = Number(params.chatId)
    const messageId = Number(params.messageId)
    const data = b64ToBytes(String(params.callbackData ?? ''))
    try {
      await active.getCallbackAnswer({ chatId, messageId, data, timeout: 10_000 })
      return { ok: true }
    } catch (e: any) {
      // bots commonly answer callbacks by editing the message or showing an
      // alert; timeouts are expected and non-fatal for the flow
      globalThis.__tgLog(20, 'bots', `callback answer: ${errorInfo(e).message}`)
      return { ok: true }
    }
  },

  async forwardMessages(params) {
    const active = ensureClientAsAny()
    const toChatId = Number(params.toChatId)
    const fromChatId = Number(params.fromChatId)
    const messageIds = (params.messageIds ?? []).map((id: unknown) => Number(id))
    const result = await active.forwardMessagesById({
      toChatId,
      fromChatId,
      messages: messageIds,
    })
    return { messageIds: (result ?? []).map((message: any) => Number(message?.id ?? 0)) }
  },

  async fileSize(params) {
    const file = await resolveFile(Number(params.chatId), Number(params.messageId), Boolean(params.thumb))
    return { fileSize: file.fileSize, dcId: file.dcId }
  },

  async resetSession() {
    fileCache.clear()
    await driver.clearAllRaw()
    return { ok: true }
  },

  async destroy() {
    if (client) {
      await client.destroy()
      client = null
    }
    fileCache.clear()
    return { ok: true }
  },
}

// ---------------------------------------------------------------------------
// binary handlers
// ---------------------------------------------------------------------------

const binaryHandlers: Record<string, (params: any) => Promise<Uint8Array>> = {
  readFilePart: (params) =>
    readFilePart(
      Number(params.chatId),
      Number(params.messageId),
      Boolean(params.thumb),
      Number(params.offset ?? 0),
      Math.max(1, Math.min(1024 * 1024, Number(params.limit ?? 64 * 1024))),
    ),
  downloadFullFile: (params) =>
    downloadFullFile(Number(params.chatId), Number(params.messageId), Boolean(params.thumb)),
  downloadChatPhoto: async (params) => {
    const active = ensureClientAsAny()
    const chat = await active.getChat(Number(params.chatId))
    const photo = chat?.photo
    if (!photo) return new Uint8Array(0)
    const size = params.big ? photo.big : photo.small
    if (!size) return new Uint8Array(0)
    return await active.downloadAsBuffer(size)
  },
}

// ---------------------------------------------------------------------------
// registration
// ---------------------------------------------------------------------------

globalThis.__tgApiCall = async function (method: string, paramsJson: string): Promise<string> {
  const handler = handlers[method]
  if (!handler) {
    return JSON.stringify({ __error: { code: -1, message: `unknown method: ${method}` } })
  }
  try {
    let params: any = {}
    if (paramsJson) {
      try {
        params = JSON.parse(paramsJson)
      } catch {
        params = {}
      }
    }
    const result = await handler(params)
    return JSON.stringify(result ?? {})
  } catch (error: any) {
    return JSON.stringify({ __error: errorInfo(error) })
  }
}

globalThis.__tgApiCallBin = async function (method: string, paramsJson: string): Promise<Int8Array> {
  const handler = binaryHandlers[method]
  if (!handler) {
    throw new Error(`TGERR:${JSON.stringify({ code: -1, message: `unknown binary method: ${method}` })}`)
  }
  let params: any = {}
  if (paramsJson) {
    try {
      params = JSON.parse(paramsJson)
    } catch {
      params = {}
    }
  }
  try {
    const result = await handler(params)
    return toI8(result)
  } catch (error: any) {
    throw new Error(`TGERR:${JSON.stringify(errorInfo(error))}`)
  }
}

globalThis.__tgHostReady = true
