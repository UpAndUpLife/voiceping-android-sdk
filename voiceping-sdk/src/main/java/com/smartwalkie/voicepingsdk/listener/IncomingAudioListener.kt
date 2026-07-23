package com.smartwalkie.voicepingsdk.listener

import com.smartwalkie.voicepingsdk.exception.VoicePingException
import com.smartwalkie.voicepingsdk.model.Message

/**
 * Created by sirius on 7/11/17.
 */
interface IncomingAudioListener {

    fun onMessageReceived(message: Message)

    fun onConnectionFailure(e: VoicePingException)

    /**
     * Called when a gap in incoming AUDIO frames is detected for an active session.
     * Router may drop frames under backpressure/rate-limit; app can surface degraded quality.
     *
     * @param senderId    talker's user id
     * @param channelType PRIVATE/GROUP
     * @param gapMs       inter-arrival delay in ms (already exceeds expected frame period)
     */
    fun onAudioGapDetected(senderId: String, channelType: Int, gapMs: Long) {}
}