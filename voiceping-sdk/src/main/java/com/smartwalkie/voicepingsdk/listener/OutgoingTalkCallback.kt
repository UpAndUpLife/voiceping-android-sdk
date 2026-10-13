package com.smartwalkie.voicepingsdk.listener

import com.smartwalkie.voicepingsdk.exception.VoicePingException

/**
 * Interface to provide callback to outgoing talk action
 */
interface OutgoingTalkCallback {
    /**
     * Invoked after outgoing talk started.
     *
     * @param audioRecorder AudioRecorder
     */
    fun onOutgoingTalkStarted(audioRecorder: AudioRecorder)

    /**
     * Invoked when the router accepted the talk (START reply). Until then the talk is only
     * connecting: audio is recorded and buffered (about 5 s) but not sent yet.
     */
    fun onOutgoingTalkLive() {}

    /**
     * Invoked after outgoing talk stopped.
     */
    fun onOutgoingTalkStopped(isTooShort: Boolean, isTooLong: Boolean)

    /**
     * Invoked after download URL received.
     */
    fun onDownloadUrlReceived(downloadUrl: String)

    /**
     * Invoked on outgoing talk error. For a denied START the code is one of
     * ErrorCode.START_NOT_ALLOWED, START_BUSY, START_OFFLINE or START_ERROR.
     */
    fun onOutgoingTalkError(e: VoicePingException)
}