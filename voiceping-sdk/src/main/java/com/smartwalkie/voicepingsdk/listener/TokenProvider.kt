package com.smartwalkie.voicepingsdk.listener

/**
 * Supplies a fresh token when the router rejects the current one with
 * HTTP 401 statusText "TokenExpired" on WS upgrade.
 *
 * Implementations should call TokenCallback asynchronously (from a
 * coroutine, worker thread, etc.). While the SDK awaits the new token,
 * reconnecting is paused. On TokenCallback.onError the SDK resumes the
 * normal backoff.
 */
fun interface TokenProvider {
    fun onTokenExpired(callback: TokenCallback)
}

interface TokenCallback {
    fun onToken(token: String)
    fun onError(e: Throwable)
}
