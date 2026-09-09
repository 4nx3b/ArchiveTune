/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * QuickJS host for the mtcute (MTProto) client — replaces the TDLib native
 * library. Runs app/src/main/assets/telegram/mtcute_host.js (mtcute bundled
 * with environment shims) and bridges everything the MTProto client needs to
 * Kotlin: WebSocket transport (OkHttp), timers, crypto (TgJsCrypto), persistent
 * storage (TgJsStorage) and a queued event pump.
 *
 * Protocol (see scripts/telegram-js/host/banner.js):
 *   Kotlin -> JS:  __tgWaitRpc() -> [id, method, jsonParams, isBinary]
 *                  (async binding; the JS main loop dispatches each request)
 *   JS -> Kotlin:  __tgResolveRpc(id, jsonString)              (JSON results)
 *                  __tgResolveRpcBin(id, err, Int8Array)        (binary results)
 *                  __tgSignalReady()                            (startup ack)
 *   events:         __tgPollEvent() -> [type, ...]  (socket frames + timers)
 *                  __tgOnClientEvent(type, json)    (new messages for bots)
 *
 * The whole host runs inside ONE long-lived root evaluation ("the JS
 * process"). That is a hard requirement of quickjs-kt 1.0.14: a root
 * evaluate() keeps draining the JS job queue while it is in flight and waits
 * for the async-binding jobs created in its session — the banner's event pump
 * arms exactly such a never-settling job (__tgPollEvent), so a short-lived
 * evaluate() would hang on it forever (that was the "RuntimeStartFailed"
 * login hang). RPC calls therefore never evaluate anything themselves; they
 * are queued through __tgWaitRpc and completed through __tgResolveRpc[Bin],
 * and the always-in-flight root evaluation keeps timers, WebSocket frames
 * and the event pump live between calls.
 */

package moe.rukamori.archivetune.telegram

import android.content.Context
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.function
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class TelegramJsException(message: String) : java.io.IOException(message)

internal object TgJsRuntime {
    private const val TAG = "TgJsRuntime"

    private const val ASSET_PATH = "telegram/mtcute_host.js"

    /** Bounds "bundle parsed + host signalled readiness" (sync JS phase only). */
    private const val STARTUP_TIMEOUT_MS = 60_000L

    private const val CALL_TIMEOUT_MS = 120_000L

    private const val TGERR_MARKER = "TGERR:"

    /** Prefix the JS main loop adds to JSON-path errors that are not envelopes. */
    private const val BRIDGE_ERROR_PREFIX = "__bridge__"

    /**
     * Appended after the mtcute bundle inside the single root evaluation.
     * Signals readiness synchronously, then forever takes RPC requests from
     * the Kotlin bridge and dispatches each one as an independent promise
     * chain, so a slow RPC never blocks the queue. Kept in sync with
     * scripts/telegram-js/host/mainloop.js (that copy exists for the Node
     * smoke test; check_mainloop_sync.js verifies they match).
     */
    private const val MAIN_LOOP_JS = ";(function () {\n" +
        "  'use strict'\n" +
        "  function rpcErrText(e) {\n" +
        "    var msg = (e && (e.message || e.text)) || String(e)\n" +
        "    return String(msg)\n" +
        "  }\n" +
        "  function dispatchRpc(req) {\n" +
        "    var id = req[0]\n" +
        "    var method = req[1]\n" +
        "    var params = req[2]\n" +
        "    if (req[3]) {\n" +
        "      Promise.resolve()\n" +
        "        .then(function () { return globalThis.__tgApiCallBin(method, params) })\n" +
        "        .then(\n" +
        "          function (bytes) { globalThis.__tgResolveRpcBin(id, null, bytes) },\n" +
        "          function (e) { globalThis.__tgResolveRpcBin(id, rpcErrText(e), null) },\n" +
        "        )\n" +
        "    } else {\n" +
        "      Promise.resolve()\n" +
        "        .then(function () { return globalThis.__tgApiCall(method, params) })\n" +
        "        .then(\n" +
        "          function (json) { globalThis.__tgResolveRpc(id, json) },\n" +
        "          function (e) { globalThis.__tgResolveRpc(id, '__bridge__' + rpcErrText(e)) },\n" +
        "        )\n" +
        "    }\n" +
        "  }\n" +
        "  globalThis.__tgSignalReady()\n" +
        "  ;(async function () {\n" +
        "    while (true) {\n" +
        "      var req\n" +
        "      try {\n" +
        "        req = await globalThis.__tgWaitRpc()\n" +
        "      } catch (e) {\n" +
        "        try { globalThis.__tgLog(40, 'rpc', 'waitRpc failed: ' + rpcErrText(e)) } catch (ignored) {}\n" +
        "        return\n" +
        "      }\n" +
        "      if (req == null) return\n" +
        "      try {\n" +
        "        dispatchRpc(req)\n" +
        "      } catch (e) {\n" +
        "        try { globalThis.__tgLog(40, 'rpc', 'dispatch failed: ' + rpcErrText(e)) } catch (ignored) {}\n" +
        "      }\n" +
        "    }\n" +
        "  })()\n" +
        "})();"

    /** Events produced by the Kotlin side, consumed by the JS event pump. */
    private val bridgeEvents = Channel<List<Any?>>(capacity = Channel.UNLIMITED)

    /** Client-level events produced by the JS side (new messages for bots). */
    private val _clientEvents =
        MutableSharedFlow<Pair<String, String>>(
            replay = 0,
            extraBufferCapacity = 256,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    val clientEvents: SharedFlow<Pair<String, String>> = _clientEvents.asSharedFlow()

    private val _runtimeState = MutableStateFlow(State.Stopped)
    val runtimeState: StateFlow<State> = _runtimeState.asStateFlow()

    enum class State { Stopped, Starting, Running, Failed }

    private var appContext: Context? = null

    private var quickJs: QuickJs? = null

    private var jsScope: CoroutineScope? = null

    private var loopJob: Job? = null

    private val startMutex = Mutex()

    /** Kotlin -> JS RPC queue; drained by the JS main loop (__tgWaitRpc). */
    private var rpcRequests = Channel<RpcRequest>(capacity = Channel.UNLIMITED)

    /** JS -> Kotlin completions, keyed by call id (__tgResolveRpc[Bin]). */
    private val pendingCalls = ConcurrentHashMap<Long, CompletableDeferred<Any?>>()

    private val nextCallId = AtomicLong(1)

    /** "ready" once the JS host signalled readiness, "failed: …" on early death. */
    private var hostReady = CompletableDeferred<String>()

    /** Last start/loop failure reason, surfaced through the login error UI. */
    @Volatile
    var lastStartError: String? = null
        private set

    private val json = Json { ignoreUnknownKeys = true }

    // ---- timers ---------------------------------------------------------------

    private val timerJobs = ConcurrentHashMap<Int, Job>()

    // ---- websockets ------------------------------------------------------------

    private val sockets = ConcurrentHashMap<Int, WebSocket>()
    private var nextSocketId = 1

    private val httpClient by lazy {
        OkHttpClient
            .Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    val isRunning: Boolean
        get() = _runtimeState.value == State.Running

    // ---------------------------------------------------------------------------
    // lifecycle
    // ---------------------------------------------------------------------------

    /**
     * Boots the QuickJS host and waits (bounded) for the JS side to signal
     * readiness. The mtcute bundle + the main-loop wrapper are evaluated as
     * ONE long-lived root evaluation, which quickjs-kt keeps draining while
     * it is in flight — that is what keeps the event pump, timers and
     * WebSocket transport alive between RPCs.
     */
    suspend fun start(context: Context): Boolean =
        startMutex.withLock {
            if (isRunning) return true

            // a previous start may have left a half-alive instance in Failed
            // state (thread + QuickJS still around) — tear it down first
            closeInternal()

            val ctx = context.applicationContext
            appContext = ctx
            TgJsStorage.attach(ctx)
            _runtimeState.value = State.Starting
            lastStartError = null
            rpcRequests = Channel(capacity = Channel.UNLIMITED)
            pendingCalls.clear()
            hostReady = CompletableDeferred()

            val executor =
                Executors.newSingleThreadExecutor { runnable ->
                    // mtcute + the TL schema parser use deeper JS stacks than the
                    // JVM default; mirror the cipher engine's oversized stack.
                    // (stackSize is only settable via this constructor)
                    Thread(null, runnable, "tg-js-quickjs", 32L * 1024 * 1024).apply {
                        isDaemon = true
                    }
                }
            val dispatcher = executor.asCoroutineDispatcher()
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            jsScope = scope

            try {
                val instance = QuickJs.create(dispatcher)
                // generous for the ~1.3 MB mtcute bundle + TL schema, but safe for
                // 32-bit ABIs (armeabi-v7a) where address space is constrained
                instance.memoryLimit = 256L * 1024 * 1024
                instance.maxStackSize = 16L * 1024 * 1024
                quickJs = instance
                val source =
                    ctx.assets.open(ASSET_PATH).use { it.readBytes().toString(Charsets.UTF_8) }
                withContext(dispatcher) { registerBindings(instance) }

                loopJob =
                    scope.launch {
                        try {
                            // Never returns on the happy path: the main loop's
                            // __tgWaitRpc job and the pump's __tgPollEvent job
                            // (both bound to this root session) stay active, so
                            // quickjs-kt keeps this evaluation in flight and
                            // keeps draining its job queue.
                            instance.evaluate<String?>(source + "\n" + MAIN_LOOP_JS + "\n;undefined;")
                            onLoopEnded("host root evaluation returned unexpectedly")
                        } catch (e: CancellationException) {
                            throw e // normal close()/restart() teardown
                        } catch (e: Exception) {
                            onLoopEnded("host evaluation failed: ${e.message ?: e.javaClass.simpleName}")
                        }
                    }

                val outcome = withTimeoutOrNull(STARTUP_TIMEOUT_MS) { hostReady.await() }
                if (outcome == "ready") {
                    _runtimeState.value = State.Running
                    Timber.tag(TAG).i("mtcute host runtime started")
                    true
                } else {
                    lastStartError = outcome ?: "host startup timed out after ${STARTUP_TIMEOUT_MS / 1000}s"
                    Timber.tag(TAG).e("mtcute host failed to start: %s", lastStartError)
                    closeInternal()
                    _runtimeState.value = State.Failed
                    false
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "failed to start the mtcute host runtime")
                lastStartError = e.message ?: e.javaClass.simpleName
                closeInternal()
                _runtimeState.value = State.Failed
                false
            }
        }

    suspend fun restart(context: Context): Boolean {
        close()
        return start(context)
    }

    suspend fun close() {
        startMutex.withLock {
            closeInternal()
        }
        _runtimeState.value = State.Stopped
    }

    private fun closeInternal() {
        jsScope?.cancel()
        jsScope = null
        loopJob = null
        runCatching { quickJs?.close() }
        quickJs = null
        failPendingCalls("runtime closed")
        rpcRequests.close()
        sockets.values.forEach { runCatching { it.cancel() } }
        sockets.clear()
        timerJobs.values.forEach { it.cancel() }
        timerJobs.clear()
        TgJsCrypto.clear()
    }

    /** Called when the root evaluation ends on its own — it never should. */
    private fun onLoopEnded(reason: String) {
        if (hostReady.isActive) {
            hostReady.complete("failed: $reason")
        }
        if (_runtimeState.value == State.Running) {
            lastStartError = reason
            _runtimeState.value = State.Failed
            Timber.tag(TAG).e("mtcute host loop ended: %s", reason)
            failPendingCalls(reason)
        }
    }

    private fun failPendingCalls(reason: String) {
        val pending = pendingCalls.values.toList()
        pendingCalls.clear()
        for (deferred in pending) {
            deferred.completeExceptionally(TelegramJsException("mtcute host is not running ($reason)"))
        }
    }

    // ---------------------------------------------------------------------------
    // bindings
    // ---------------------------------------------------------------------------

    private fun registerBindings(instance: QuickJs) {
        // -- logging (sync)
        instance.function("__tgLog") { args ->
            val level = args.arg(0).asLongCompat()
            val tag = args.arg(1).asStringCompat()
            val message = args.arg(2).asStringCompat()
            logLine(level.toInt(), tag, message)
            null
        }

        // -- websockets (sync: OkHttp is async under the hood)
        instance.function("__tgWsOpen") { args ->
            val id = args.arg(0).asLongCompat().toInt()
            val url = args.arg(1).asStringCompat()
            wsOpen(id, url)
        }
        instance.function("__tgWsSend") { args ->
            val id = args.arg(0).asLongCompat().toInt()
            val data = args.arg(1).asByteArrayCompat()
            val socket = sockets[id]
            if (socket == null) {
                false
            } else {
                socket.send(data.toByteString())
                true
            }
        }
        instance.function("__tgWsClose") { args ->
            val id = args.arg(0).asLongCompat().toInt()
            val code = args.arg(1).asLongCompat().toInt()
            val reason = args.arg(2).asStringCompat()
            val socket = sockets.remove(id)
            if (socket != null) {
                socket.close(code, reason.take(120))
            }
            null
        }

        // -- crypto (sync, all native)
        instance.function("__tgSha1") { args -> TgJsCrypto.sha1(args.arg(0).asByteArrayCompat()) }
        instance.function("__tgSha256") { args -> TgJsCrypto.sha256(args.arg(0).asByteArrayCompat()) }
        instance.function("__tgHmacSha256") { args ->
            TgJsCrypto.hmacSha256(args.arg(0).asByteArrayCompat(), args.arg(1).asByteArrayCompat())
        }
        instance.function("__tgPbkdf2") { args ->
            TgJsCrypto.pbkdf2(
                args.arg(0).asByteArrayCompat(),
                args.arg(1).asByteArrayCompat(),
                args.arg(2).asLongCompat().toInt(),
                args.arg(3).asLongCompat().toInt(),
                args.arg(4).asStringCompat(),
            )
        }
        instance.function("__tgAesCtrOpen") { args ->
            TgJsCrypto.aesCtrOpen(
                args.arg(0).asByteArrayCompat(),
                args.arg(1).asByteArrayCompat(),
                args.arg(2).asBooleanCompat(),
            )
        }
        instance.function("__tgAesCtrProcess") { args ->
            TgJsCrypto.aesCtrProcess(args.arg(0).asLongCompat().toInt(), args.arg(1).asByteArrayCompat())
        }
        instance.function("__tgAesCtrClose") { args ->
            TgJsCrypto.aesCtrClose(args.arg(0).asLongCompat().toInt())
            null
        }
        instance.function("__tgAesIge") { args ->
            TgJsCrypto.aesIge(
                args.arg(0).asBooleanCompat(),
                args.arg(1).asByteArrayCompat(),
                args.arg(2).asByteArrayCompat(),
                args.arg(3).asByteArrayCompat(),
            )
        }
        instance.function("__tgFactorizePq") { args ->
            val pq = args.arg(0).asByteArrayCompat()
            val (p, q) = TgJsCrypto.factorizePq(pq)
            listOf(p, q)
        }
        instance.function("__tgGzip") { args ->
            TgJsCrypto.gzip(args.arg(0).asByteArrayCompat(), args.arg(1).asLongCompat().toInt())
        }
        instance.function("__tgGunzip") { args -> TgJsCrypto.gunzip(args.arg(0).asByteArrayCompat()) }
        instance.function("__tgRandomBytes") { args -> TgJsCrypto.randomBytes(args.arg(0).asLongCompat().toInt()) }

        // -- timers (async: the promise resolves immediately, the fire comes later)
        instance.asyncFunction("__tgSetTimer") { args ->
            val id = args.arg(0).asLongCompat()
            val ms = args.arg(1).asLongCompat()
            val repeat = args.arg(2).asBooleanCompat()
            scheduleTimer(id, ms, repeat)
        }
        instance.function("__tgCancelTimer") { args ->
            timerJobs.remove(args.arg(0).asLongCompat().toInt())?.cancel()
            null
        }

        // -- event pump (async: suspends until the next bridge event)
        instance.asyncFunction("__tgPollEvent") { _ ->
            bridgeEvents.receive()
        }

        // -- storage (async: disk writes stay off the JS thread)
        instance.asyncFunction("__tgStoreLoadAll") { args ->
            TgJsStorage.loadAll(args.arg(0).asStringCompat())
        }
        instance.asyncFunction("__tgStoreSet") { args ->
            TgJsStorage.set(
                args.arg(0).asStringCompat(),
                args.arg(1).asStringCompat(),
                args.arg(2).asByteArrayCompat(),
            )
        }
        instance.asyncFunction("__tgStoreDelete") { args ->
            TgJsStorage.delete(args.arg(0).asStringCompat(), args.arg(1).asStringCompat())
        }
        instance.asyncFunction("__tgStoreClear") { args ->
            TgJsStorage.clear(args.arg(0).asStringCompat())
        }
        instance.asyncFunction("__tgStoreClearAll") { _ ->
            TgJsStorage.clearAll()
        }

        // -- client events (sync: JS pushes new messages here)
        instance.function("__tgOnClientEvent") { args ->
            val type = args.arg(0).asStringCompat()
            val payload = args.arg(1).asStringCompat()
            if (type.isNotEmpty() && payload.isNotEmpty()) {
                _clientEvents.tryEmit(type to payload)
            }
            null
        }

        // -- RPC bridge (Kotlin -> JS requests, JS -> Kotlin completions)
        instance.function("__tgSignalReady") { _ ->
            hostReady.complete("ready")
            null
        }
        instance.asyncFunction("__tgWaitRpc") { _ ->
            val request = rpcRequests.receive()
            listOf(request.id, request.method, request.params, request.binary)
        }
        instance.function("__tgResolveRpc") { args ->
            val id = args.arg(0).asLongCompat()
            val payload = args.arg(1).asStringCompat()
            pendingCalls.remove(id)?.complete(payload)
            null
        }
        instance.function("__tgResolveRpcBin") { args ->
            val id = args.arg(0).asLongCompat()
            val error = args.arg(1).asStringCompat()
            val bytes = args.arg(2).asByteArrayCompat()
            pendingCalls.remove(id)?.complete(if (error.isEmpty()) bytes else error)
            null
        }
    }

    private fun logLine(level: Int, tag: String, message: String) {
        when {
            level >= 50 -> Timber.tag(TAG).e("[%s] %s", tag, message)
            level >= 40 -> Timber.tag(TAG).w("[%s] %s", tag, message)
            level >= 30 -> Timber.tag(TAG).i("[%s] %s", tag, message)
            else -> Timber.tag(TAG).d("[%s] %s", tag, message)
        }
    }

    private fun scheduleTimer(
        id: Long,
        ms: Long,
        repeat: Boolean,
    ): Boolean {
        val scope = jsScope ?: return false
        timerJobs.remove(id.toInt())?.cancel()
        val job =
            scope.launch {
                if (repeat) {
                    while (true) {
                        delay(ms.coerceAtLeast(1L))
                        bridgeEvents.trySend(listOf(1L, id))
                    }
                } else {
                    delay(ms.coerceAtLeast(0L))
                    bridgeEvents.trySend(listOf(1L, id))
                }
            }
        timerJobs[id.toInt()] = job
        return true
    }

    // ---------------------------------------------------------------------------
    // websockets
    // ---------------------------------------------------------------------------

    private fun wsOpen(
        id: Int,
        url: String,
    ): Boolean {
        val request =
            try {
                // Telegram's apiws endpoint requires the "binary" subprotocol and
                // answers 404 when it is missing; OkHttp passes manually set
                // upgrade headers through.
                Request
                    .Builder()
                    .url(url)
                    .header("Sec-WebSocket-Protocol", "binary")
                    .build()
            } catch (e: IllegalArgumentException) {
                Timber.tag(TAG).w(e, "invalid ws url %s", url)
                return false
            }
        val listener =
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    bridgeEvents.trySend(listOf(0L, id.toLong(), 0L, 0L, "", null))
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    bridgeEvents.trySend(listOf(0L, id.toLong(), 1L, 0L, "", bytes.toByteArray()))
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    sockets.remove(id)
                    bridgeEvents.trySend(listOf(0L, id.toLong(), 2L, code.toLong(), reason, null))
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    sockets.remove(id)
                    bridgeEvents.trySend(listOf(0L, id.toLong(), 3L, 0L, t.message ?: "socket failure", null))
                }
            }
        val socket =
            try {
                httpClient.newWebSocket(request, listener)
            } catch (e: IllegalArgumentException) {
                // some OkHttp versions reject manually set upgrade headers
                runCatching { sockets.remove(id) }
                val fallback = Request.Builder().url(url).build()
                httpClient.newWebSocket(fallback, listener)
            }
        sockets[id] = socket
        return true
    }

    // ---------------------------------------------------------------------------
    // JS invocation
    // ---------------------------------------------------------------------------

    suspend fun call(
        method: String,
        params: String = "{}",
        timeoutMs: Long = CALL_TIMEOUT_MS,
    ): JsonObject {
        val raw =
            invokeRpc(method, params, binary = false, timeoutMs) as? String
                ?: throw TelegramJsException("Malformed host response for $method")
        if (raw.startsWith(BRIDGE_ERROR_PREFIX)) {
            throw TelegramApiException(0, raw.removePrefix(BRIDGE_ERROR_PREFIX).substringBefore('\n'))
        }
        val parsed =
            try {
                json.parseToJsonElement(raw).jsonObject
            } catch (e: Exception) {
                throw TelegramJsException("Malformed host response for $method")
            }
        val error = parsed["__error"]
        if (error != null) {
            val obj = error.jsonObject
            throw TelegramApiException(
                obj["code"]?.jsonPrimitive?.intOrNull ?: 0,
                obj["message"]?.jsonPrimitive?.content ?: "unknown error",
            )
        }
        return parsed
    }

    suspend fun callBin(
        method: String,
        params: String = "{}",
        timeoutMs: Long = CALL_TIMEOUT_MS,
    ): ByteArray {
        when (val result = invokeRpc(method, params, binary = true, timeoutMs)) {
            is ByteArray -> return result
            is String -> throw binaryErrorFromMessage(result)
            else -> throw TelegramJsException("Malformed binary host response for $method")
        }
    }

    /**
     * Enqueues one RPC for the JS main loop and awaits the completion the JS
     * side reports through __tgResolveRpc[Bin]. Timed-out calls keep running
     * JS-side (late resolutions for stale ids are dropped), so mtcute's own
     * state machine always advances consistently.
     */
    private suspend fun invokeRpc(
        method: String,
        params: String,
        binary: Boolean,
        timeoutMs: Long,
    ): Any? {
        if (!isRunning) throw TelegramJsException("mtcute host is not running")
        val id = nextCallId.getAndIncrement()
        val deferred = CompletableDeferred<Any?>()
        pendingCalls[id] = deferred
        try {
            rpcRequests.trySend(RpcRequest(id, method, params, binary))
            return withTimeout(timeoutMs) { deferred.await() }
        } finally {
            pendingCalls.remove(id)
        }
    }

    private fun binaryErrorFromMessage(message: String): TelegramApiException {
        val marker = message.indexOf(TGERR_MARKER)
        if (marker >= 0) {
            val payload = message.substring(marker + TGERR_MARKER.length).substringBefore('\n')
            return try {
                val obj = json.parseToJsonElement(payload).jsonObject
                TelegramApiException(
                    obj["code"]?.jsonPrimitive?.intOrNull ?: 0,
                    obj["message"]?.jsonPrimitive?.content ?: payload,
                )
            } catch (ignored: Exception) {
                TelegramApiException(0, payload)
            }
        }
        return TelegramApiException(0, message.substringBefore('\n'))
    }

    // ---------------------------------------------------------------------------
    // argument marshalling helpers
    // ---------------------------------------------------------------------------

    private fun Array<Any?>?.arg(index: Int): Any? = this?.getOrNull(index)

    private fun Any?.asLongCompat(): Long =
        when (this) {
            null -> 0L
            is Long -> this
            is Int -> toLong()
            is Double -> toLong()
            is Float -> toLong()
            is Short -> toLong()
            is Byte -> toLong()
            is Number -> toLong()
            is String -> toLongOrNull() ?: 0L
            else -> 0L
        }

    private fun Any?.asStringCompat(): String = this as? String ?: ""

    private fun Any?.asBooleanCompat(): Boolean =
        when (this) {
            null -> false
            is Boolean -> this
            is Long -> this != 0L
            is Int -> this != 0
            is Double -> this != 0.0
            is String -> this == "true"
            else -> false
        }

    private fun Any?.asByteArrayCompat(): ByteArray = this as? ByteArray ?: ByteArray(0)

    private class RpcRequest(
        val id: Long,
        val method: String,
        val params: String,
        val binary: Boolean,
    )
}
