package com.smartwalkie.voicepingsdk

import com.smartwalkie.voicepingsdk.callback.ConnectCallback
import com.smartwalkie.voicepingsdk.callback.DisconnectCallback
import com.smartwalkie.voicepingsdk.listener.ConnectionStateListener
import com.smartwalkie.voicepingsdk.listener.OutgoingAudioListener
import com.smartwalkie.voicepingsdk.listener.PresenceListener
import com.smartwalkie.voicepingsdk.listener.TokenProvider

/**
 * Created by kukuhsain on 02/03/18.
 */
internal interface Connection {

    fun setConnectionStateListener(listener: ConnectionStateListener?)

    fun setOutgoingAudioListener(listener: OutgoingAudioListener?)

    fun setPresenceListener(listener: PresenceListener?)

    fun setTokenProvider(provider: TokenProvider?)

    /** Called on every CONNECTED transition (initial + reconnects). */
    fun setOnConnectedHook(hook: Runnable?)

    val serverUrl: String?

    fun connect(serverUrl: String, token: String, callback: ConnectCallback)

    /** Replaces the token and reconnects once to use it. */
    fun updateToken(token: String)

    val connectionState: ConnectionState

    fun disconnect(callback: DisconnectCallback?)

    fun send(data: ByteArray)
}
