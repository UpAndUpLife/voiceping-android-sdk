package com.smartwalkie.voicepingsdk

import android.content.Context
import android.os.Build
import android.os.HandlerThread
import java.security.MessageDigest
import com.smartwalkie.voicepingsdk.callback.ConnectCallback
import com.smartwalkie.voicepingsdk.callback.DisconnectCallback
import com.smartwalkie.voicepingsdk.listener.ConnectionStateListener
import com.smartwalkie.voicepingsdk.listener.IncomingTalkListener
import com.smartwalkie.voicepingsdk.listener.OutgoingTalkCallback
import com.smartwalkie.voicepingsdk.listener.PresenceListener
import com.smartwalkie.voicepingsdk.listener.TokenProvider
import com.smartwalkie.voicepingsdk.model.AudioParam

/**
 * Main class of VoicePing.
 */
object VoicePing {
    private const val DEFAULT_SERVER_URL: String = "wss://router-lite.voiceping.info"
    private const val MAX_PEOPLE_IDS = 1000
    private lateinit var audioParam: AudioParam
    private lateinit var player: Player
    private lateinit var connection: Connection
    private lateinit var recorder: Recorder

    private var userId: String? = null

    // Channels the app listens to via subscribe() and the last peopleSub() request. Both are
    // re-sent on every CONNECTED transition: the router keeps neither across a socket.
    private val activeChannels = linkedSetOf<String>()
    private var lastPeopleIds: List<String>? = null
    private var lastPeopleChannels: Map<String, String> = emptyMap()

    /**
     * Stable per-install device identifier. The router replaces a socket quietly when the
     * new one carries the same deviceId, so callers MUST pass this string when minting
     * the JWT (POST /ptt/token body.deviceId).
     * SHA-256 hex of Build.MANUFACTURER + MODEL + FINGERPRINT — no permissions,
     * fixed 64-char length, no build-info leakage in logs / server keys.
     */
    val deviceId: String by lazy {
        val raw = "${Build.MANUFACTURER}_${Build.MODEL}_${Build.FINGERPRINT}"
        MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    /**
     * Initialize VoicePing. This process should be done in onCreate of your Application class
     *
     * @param context   Application Context
     */
    fun init(context: Context) {
        init(context, AudioParam.Builder().build())
    }

    /**
     * Initialize VoicePing. This process should be done in onCreate of your Application class
     *
     * @param context   Application Context
     * @param audioParam Audio parameters
     */
    fun init(context: Context, audioParam: AudioParam) {
        this.audioParam = audioParam
        val voicePingThread = HandlerThread("VoicePingThread", Thread.MAX_PRIORITY)
        voicePingThread.start()
        player = Player(context, audioParam, DEFAULT_SERVER_URL, voicePingThread.looper)
        connection = OkConnection(context, player, voicePingThread.looper)
        recorder = Recorder(context, connection, audioParam, voicePingThread.looper)
        connection.setOutgoingAudioListener(recorder)
        connection.setOnConnectedHook(Runnable { replaySubscriptions() })
    }

    /**
     * Connect using a backend-minted identity token (12 h JWT {uid, deviceId, iat, exp}).
     * The router authenticates via the JWT sent in the `token` header.
     *
     * @param serverUrl Server WebSocket URL (e.g. from BuildConfig.PTT_ROUTER_URL)
     * @param jwt       HS256 JWT minted by your backend
     * @param userId    User ID as known to your backend (the JWT uid); used as sender of talks
     * @param callback  ConnectCallback
     */
    fun connect(serverUrl: String, jwt: String, userId: String, callback: ConnectCallback) {
        val realServerUrl = when {
            serverUrl.isBlank() -> DEFAULT_SERVER_URL
            serverUrl.endsWith("/") -> serverUrl.substring(0, serverUrl.length - 1)
            else -> serverUrl
        }
        this.userId = userId
        player.setServerUrl(realServerUrl)
        recorder.setUserId(userId)
        connection.connect(realServerUrl, jwt, callback)
    }

    /**
     * Replace the token and reconnect once with it. Use after a re-login, or to refresh
     * ahead of expiry. Reconnecting also restores a connection stopped by an error such as
     * ErrorCode.UNAUTHORIZED. Subscriptions are re-sent after the socket opens.
     * For the expiry the router reports on connect, set a TokenProvider instead.
     *
     * @param newJwt Freshly minted JWT from your backend (same uid and deviceId)
     */
    fun updateToken(newJwt: String) {
        connection.updateToken(newJwt)
    }

    /**
     * Disconnect from server. This method can be assumed as sign out from server. After
     * disconnected from server, the user will not receive any incoming message.
     *
     * @param callback Callback
     */
    fun disconnect(callback: DisconnectCallback) {
        synchronized(activeChannels) {
            activeChannels.clear()
            lastPeopleIds = null
            lastPeopleChannels = emptyMap()
        }
        connection.disconnect(callback)
    }

    /**
     * Get current connection state.
     *
     * @return Connection state
     */
    fun getConnectionState(): ConnectionState {
        return connection.connectionState
    }

    /**
     * Set ConnectionStateListener to VoicePing.
     *
     * onConnectionStateChanged(CONNECTED) fires on every reconnect. The SDK re-sends channel
     * subscriptions and the last peopleSub() itself.
     *
     * onConnectionError fires once and the SDK stops reconnecting for:
     * ErrorCode.SESSION_TAKEN (4409, another device signed in), ErrorCode.UNAUTHORIZED
     * (token rejected) and ErrorCode.TOKEN_EXPIRED (expired, no TokenProvider set).
     * Sign in again, then call updateToken().
     *
     * @param listener ConnectionStateListener
     */
    fun setConnectionStateListener(listener: ConnectionStateListener?) {
        connection.setConnectionStateListener(listener)
    }

    /**
     * Register a PresenceListener. Callbacks fire on the main thread.
     * Presence snapshot, presence deltas and app notify events arrive here.
     */
    fun setPresenceListener(listener: PresenceListener?) {
        connection.setPresenceListener(listener)
    }

    /**
     * Supply a TokenProvider so the SDK can transparently recover from expired
     * JWTs. When the router rejects the socket with HTTP 401 "TokenExpired",
     * the SDK invokes provider.onTokenExpired(callback); reply with a fresh
     * JWT via callback.onToken(jwt) or callback.onError(e).
     *
     * While awaiting the fresh token, reconnecting is paused. On error, the SDK resumes
     * the normal backoff: do not retry-loop the mint call from the provider itself.
     */
    fun setTokenProvider(provider: TokenProvider?) {
        connection.setTokenProvider(provider)
    }

    /**
     * Listen to a group channel: its talks are heard and its notify events arrive as onNotify.
     * Re-sent automatically after every reconnect.
     *
     * @param channel Full channel id, as the app's backend names it
     */
    fun subscribe(channel: String) {
        val uid = userId ?: return
        synchronized(activeChannels) { activeChannels.add(channel) }
        if (connection.connectionState == ConnectionState.CONNECTED) {
            connection.send(MessageHelper.createSubscribeMessage(uid, channel))
        }
    }

    /**
     * Stop listening to a group channel.
     */
    fun unsubscribe(channel: String) {
        val uid = userId ?: return
        synchronized(activeChannels) { activeChannels.remove(channel) }
        if (connection.connectionState == ConnectionState.CONNECTED) {
            connection.send(MessageHelper.createUnsubscribeMessage(uid, channel))
        }
    }

    /**
     * Replace the whole presence subscription. The router answers with onPresenceSnapshot
     * (current state of every id you may see, and of each channel whose version differs),
     * then sends onPresence deltas. Call again whenever the app's list changes.
     * Re-sent automatically after every reconnect.
     *
     * @param ids      Person ids to watch (at most 1000; extra ids are dropped)
     * @param channels Channel id to the version the app already holds
     */
    fun peopleSub(ids: List<String>, channels: Map<String, String>) {
        val uid = userId ?: return
        val limited = ids.take(MAX_PEOPLE_IDS)
        synchronized(activeChannels) {
            lastPeopleIds = limited
            lastPeopleChannels = channels.toMap()
        }
        if (connection.connectionState == ConnectionState.CONNECTED) {
            connection.send(MessageHelper.createPeopleSubMessage(uid, limited, channels))
        }
    }

    private fun replaySubscriptions() {
        val uid = userId ?: return
        val channels: List<String>
        val ids: List<String>?
        val versions: Map<String, String>
        synchronized(activeChannels) {
            channels = activeChannels.toList()
            ids = lastPeopleIds
            versions = lastPeopleChannels
        }
        for (channel in channels) {
            connection.send(MessageHelper.createSubscribeMessage(uid, channel))
        }
        if (ids != null) {
            connection.send(MessageHelper.createPeopleSubMessage(uid, ids, versions))
        }
    }

    /**
     * Set IncomingTalkListener to VoicePing to do some advanced techniques to the incoming audio
     * data.
     *
     * @param listener IncomingTalkListener
     */
    fun setIncomingTalkListener(listener: IncomingTalkListener?) {
        player.setIncomingTalkListener(listener)
    }

    /**
     * Start PTT Talk using VoicePing's PTT functionality.
     *
     * @param receiverId  Receiver user id for private channel, or a group channel id
     * @param channelType ChannelType.PRIVATE or ChannelType.GROUP
     * @param callback    OutgoingTalkCallback
     */
    fun startTalking(receiverId: String, channelType: Int, callback: OutgoingTalkCallback?) {
        recorder.startTalking(receiverId, channelType, callback, null, null)
    }

    /**
     * Start PTT Talk using VoicePing's PTT functionality. Save recorded audio to the destination
     * path at the end of the talk. The saved file can be played using VoicePingPlayer.
     *
     * @param receiverId      Receiver user id for private channel, or a group channel id
     * @param channelType     ChannelType.PRIVATE or ChannelType.GROUP
     * @param callback        OutgoingTalkCallback
     * @param destinationPath Destination path
     */
    fun startTalking(
        receiverId: String,
        channelType: Int,
        callback: OutgoingTalkCallback?,
        destinationPath: String?
    ) {
        recorder.startTalking(receiverId, channelType, callback, destinationPath, null)
    }

    /**
     * Start PTT Talk using VoicePing's PTT functionality. Save recorded audio to the destination
     * path at the end of the talk. The saved file can be played using VoicePingPlayer. Add custom
     * audio recorder using CustomAudioRecorder.
     *
     * @param receiverId      Receiver user id for private channel, or a group channel id
     * @param channelType     ChannelType.PRIVATE or ChannelType.GROUP
     * @param callback        OutgoingTalkCallback
     * @param destinationPath Destination path
     * @param recorder        CustomAudioRecorder
     */
    fun startTalking(
        receiverId: String,
        channelType: Int,
        callback: OutgoingTalkCallback?,
        destinationPath: String?,
        recorder: CustomAudioRecorder?
    ) {
        this.recorder.startTalking(
            receiverId,
            channelType,
            callback,
            destinationPath,
            recorder
        )
    }

    /**
     * Stop PTT Talk.
     */
    fun stopTalking() {
        recorder.stopTalking()
    }

    /**
     * Get AudioParam that is used in this VoicePing instance.
     *
     * @return AudioParam
     */
    fun getAudioParam(): AudioParam {
        return audioParam
    }

    fun setAudioParam(audioParam: AudioParam) {
        this.audioParam = audioParam
        player.setAudioParam(audioParam)
        recorder.setAudioParam(audioParam)
    }

    /**
     * Mute from specific channel.
     *
     * @param targetId    Sender ID (Private) or Group ID (Group)
     * @param channelType ChannelType.PRIVATE or ChannelType.GROUP
     */
    fun mute(targetId: String, channelType: Int) {
        player.mute(targetId, channelType)
    }

    /**
     * Mute all channels.
     */
    fun muteAll() {
        player.muteAll()
    }

    /**
     * Unmute to specific channel.
     *
     * @param targetId    Sender ID (Private) or Group ID (Group)
     * @param channelType ChannelType.PRIVATE or ChannelType.GROUP
     */
    fun unmute(targetId: String, channelType: Int) {
        player.unmute(targetId, channelType)
    }

    /**
     * Unmute all channels.
     */
    fun unmuteAll() {
        player.unmuteAll()
    }
}
