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
    private lateinit var audioParam: AudioParam
    private lateinit var player: Player
    private lateinit var connection: Connection
    private lateinit var recorder: Recorder

    private var userId: String? = null
    private var company: String? = null
    // Non-null when connected via JWT overload; null in legacy company-prefix mode
    private var jwtToken: String? = null

    // Channels the app has subscribed to via subscribeChannel(). Replayed
    // automatically on every CONNECTED transition so reconnects (backoff-
    // driven or post-TokenExpired) restore audio + presence fanout without
    // app intervention. Router-side session-restore within grace makes the
    // replay idempotent; outside grace it is the only path back.
    private val activeChannels = linkedSetOf<String>()

    /**
     * Stable per-install device identifier used by the SDK for the WS `DeviceId`
     * header. Session-restore on the router keys by this value, so callers MUST
     * pass the same string when minting the JWT (POST /ptt/token body.deviceId).
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
        connection.setOnConnectedHook(Runnable { replayActiveChannels() })
    }

    /**
     * Connect using a backend-minted JWT. Channel and user IDs are sent verbatim (no prefix).
     * The router authenticates via the JWT passed as the VoicePingToken header.
     *
     * @param serverUrl Server WebSocket URL (e.g. from BuildConfig.PTT_ROUTER_URL)
     * @param jwt       HS256 JWT minted by your backend
     * @param userId    User ID as known to your backend (stored for startTalking/joinGroup)
     * @param callback  ConnectCallback
     */
    fun connect(serverUrl: String, jwt: String, userId: String, callback: ConnectCallback) {
        val realServerUrl = when {
            serverUrl.isBlank() -> DEFAULT_SERVER_URL
            serverUrl.endsWith("/") -> serverUrl.substring(0, serverUrl.length - 1)
            else -> serverUrl
        }
        this.userId = userId
        this.company = null
        this.jwtToken = jwt
        player.setServerUrl(realServerUrl)
        connection.connect(realServerUrl, jwt, deviceId, callback)
        recorder.setUserId(userId)
    }

    /**
     * Refresh the JWT without a full disconnect/reconnect cycle visible to the user.
     * Call this before your token expires (backend rotates every 30 min).
     * Simple impl: disconnects and reconnects with the new JWT.
     *
     * @param serverUrl  Same server URL used in connect()
     * @param newJwt     Freshly minted JWT from your backend
     * @param callback   ConnectCallback fired when reconnection completes
     */
    fun updateToken(serverUrl: String, newJwt: String, callback: ConnectCallback) {
        val uid = userId ?: run {
            callback.onFailed(com.smartwalkie.voicepingsdk.exception.VoicePingException(
                "Not connected", com.smartwalkie.voicepingsdk.exception.ErrorCode.CONNECTION_FAILURE))
            return
        }
        connection.disconnect(object : DisconnectCallback { override fun onDisconnected() {} })
        connect(serverUrl, newJwt, uid, callback)
    }

    /**
     * Connect to server (legacy company-prefix mode).
     * Token sent as "{company}_{userId}". Use JWT overload for new integrations.
     *
     * @param userId   User ID or Username
     * @param company  Company/tenant prefix
     * @param callback Callback
     */
    @Deprecated("Use JWT overload: connect(serverUrl, jwt, userId, callback)")
    fun connect(userId: String, company: String, callback: ConnectCallback) {
        this.userId = userId
        this.company = company
        this.jwtToken = null
        player.setServerUrl(DEFAULT_SERVER_URL)
        connection.connect(DEFAULT_SERVER_URL, getFullUserId(), deviceId, callback)
        recorder.setUserId(getFullUserId())
    }

    /**
     * Disconnect from server. This method can be assumed as sign out from server. After
     * disconnected from server, the user will not receive any incoming message.
     *
     * @param callback Callback
     */
    fun disconnect(callback: DisconnectCallback) {
        synchronized(activeChannels) { activeChannels.clear() }
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
     * IMPORTANT: onConnectionStateChanged(CONNECTED) fires on every reconnect,
     * not only after the initial connect(). Router drops all channel SUBSCRIBEs
     * on socket disconnect (identity channel is auto-re-entered by router within
     * a 20s grace window). Re-run your subscribe/enter loop on every CONNECTED
     * transition or presence and audio fanout will silently break.
     *
     * @param listener ConnectionStateListener
     */
    fun setConnectionStateListener(listener: ConnectionStateListener?) {
        connection.setConnectionStateListener(listener)
    }

    /**
     * Register a PresenceListener. Callbacks fire on the main thread.
     * `onPresenceSync` fires once per subscribeChannel() call as initial roster.
     * enter/leave/update fire as deltas.
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
     * While awaiting the fresh token, reconnect backoff is paused. On error,
     * the SDK resumes normal backoff — do not retry-loop the mint call
     * from the provider itself.
     */
    fun setTokenProvider(provider: TokenProvider?) {
        connection.setTokenProvider(provider)
    }

    /**
     * Subscribe to a presence channel. REQUIRED to receive audio on that channel
     * (site: or dm:) and required to see presence deltas (user: for DM peer status).
     * Channel string flows verbatim — no company_ prefix.
     */
    fun subscribeChannel(channel: String) {
        val uid = userId ?: return
        synchronized(activeChannels) { activeChannels.add(channel) }
        connection.send(MessageHelper.createSubscribeChannelMessage(uid, channel))
    }

    /**
     * Unsubscribe from a presence channel. Stops audio + presence delivery.
     */
    fun unsubscribeChannel(channel: String) {
        val uid = userId ?: return
        synchronized(activeChannels) { activeChannels.remove(channel) }
        connection.send(MessageHelper.createUnsubscribeChannelMessage(uid, channel))
    }

    private fun replayActiveChannels() {
        val uid = userId ?: return
        val snapshot = synchronized(activeChannels) { activeChannels.toList() }
        for (channel in snapshot) {
            connection.send(MessageHelper.createSubscribeChannelMessage(uid, channel))
        }
    }

    /**
     * Announce self as present on a channel with optional data payload (≤256B).
     * Do NOT call for `user:<self>` — router auto-ENTERs identity on connect.
     */
    fun enterChannel(channel: String, data: String = "") {
        val uid = userId ?: return
        connection.send(MessageHelper.createEnterMessage(uid, channel, data))
    }

    /**
     * Remove self presence from a channel. Refuses to LEAVE the identity channel
     * to prevent accidental self-offline (router manages user:<self> lifecycle).
     */
    fun leaveChannel(channel: String) {
        val uid = userId ?: return
        if (channel == "user:$uid") {
            android.util.Log.w("VoicePing", "Refusing to LEAVE identity channel user:$uid")
            return
        }
        connection.send(MessageHelper.createLeaveMessage(uid, channel))
    }

    /**
     * Update own presence data on a channel. Rate-limited server-side to 10/s per uid;
     * over-limit UPDATEs are silently dropped by router. Batch UI state changes.
     * @param data JSON string, ≤256B (router truncates past cap)
     */
    fun updatePresence(channel: String, data: String) {
        val uid = userId ?: return
        connection.send(MessageHelper.createUpdateMessage(uid, channel, data))
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
     * @param receiverId  Receiver ID for private channel, or Group ID for group channel
     * @param channelType ChannelType.PRIVATE or ChannelType.GROUP
     * @param callback    OutgoingTalkCallback
     */
    fun startTalking(receiverId: String, channelType: Int, callback: OutgoingTalkCallback?) {
        recorder.startTalking(getFullId(receiverId), channelType, callback, null, null)
    }

    /**
     * Start PTT Talk using VoicePing's PTT functionality. Save recorded audio to the destination
     * path at the end of the talk. The saved file can be played using VoicePingPlayer.
     *
     * @param receiverId      Receiver ID for private channel, or Group ID for group channel
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
        recorder.startTalking(getFullId(receiverId), channelType, callback, destinationPath, null)
    }

    /**
     * Start PTT Talk using VoicePing's PTT functionality. Save recorded audio to the destination
     * path at the end of the talk. The saved file can be played using VoicePingPlayer. Add custom
     * audio recorder using CustomAudioRecorder.
     *
     * @param receiverId      Receiver ID for private channel, or Group ID for group channel
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
            getFullId(receiverId),
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
     * Join a group channel.
     *
     * @param groupId Group ID
     */
    fun joinGroup(groupId: String) {
        connection.send(MessageHelper.createSubscribeMessage(getFullUserId(), getFullId(groupId)))
    }

    /**
     * Leave a group channel.
     *
     * @param groupId Group ID
     */
    fun leaveGroup(groupId: String) {
        connection.send(MessageHelper.createUnsubscribeMessage(getFullUserId(), getFullId(groupId)))
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
        player.mute(getFullId(targetId), channelType)
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
        player.unmute(getFullId(targetId), channelType)
    }

    /**
     * Unmute all channels.
     */
    fun unmuteAll() {
        player.unmuteAll()
    }

    private fun getFullUserId(): String = company?.let { "${it}_$userId" } ?: userId!!

    private fun getFullId(id: String): String = company?.let { "${it}_$id" } ?: id
}