;(function () {
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
})();
