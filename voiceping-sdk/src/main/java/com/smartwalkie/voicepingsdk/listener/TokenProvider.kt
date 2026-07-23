package com.smartwalkie.voicepingsdk.listener

/**
 * Supplies a fresh JWT when the router rejects the current one with
 * HTTP 401 statusText "TokenExpired" on WS upgrade.
 *
 * Implementations should call TokenCallback asynchronously (from a
 * coroutine, worker thread, etc.). While the SDK awaits the new token,
 * reconnect backoff is paused so a slow refresh does not stack retries.
 * On TokenCallback.onError the SDK resumes normal backoff.
 */
fun interface TokenProvider {
    fun onTokenExpired(callback: TokenCallback)
}

interface TokenCallback {
    fun onToken(jwt: String)
    fun onError(e: Throwable)
}
