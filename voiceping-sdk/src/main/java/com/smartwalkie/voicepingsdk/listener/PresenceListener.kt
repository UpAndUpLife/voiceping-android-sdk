package com.smartwalkie.voicepingsdk.listener

/**
 * Router events that arrive on the open connection. Callbacks fire on the main thread.
 *
 * Versions are opaque strings from the router; keep the newest per id so an older reply never
 * overwrites a newer event.
 */
interface PresenceListener {
    /**
     * Reply to [com.smartwalkie.voicepingsdk.VoicePing.peopleSub]: the current state of every
     * allowed id, and each channel whose version differs from the one sent.
     */
    fun onPresenceSnapshot(people: List<PersonPresence>, channels: List<ChannelVersion>)

    /** One person changed. */
    fun onPresence(person: PersonPresence)

    /** An app event from the backend, forwarded unread by the router. [payload] is its JSON. */
    fun onNotify(payload: String)
}

data class PersonPresence(val id: String, val online: Boolean, val version: String)

/** Version of a channel; [id] is the full channel id the app subscribed with. */
data class ChannelVersion(val id: String, val version: String)
