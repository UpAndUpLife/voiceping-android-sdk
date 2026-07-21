package com.smartwalkie.voicepingsdk.listener

/**
 * Presence event listener. Fires on router PRESENCE messages after subscribing to a channel.
 * `sync` fires once per SUBSCRIBE (initial snapshot). enter/leave/update fire as deltas.
 */
interface PresenceListener {
    fun onPresenceSync(channel: String, members: List<PresenceMember>)
    fun onPresenceEnter(channel: String, member: PresenceMember)
    fun onPresenceLeave(channel: String, member: PresenceMember)
    fun onPresenceUpdate(channel: String, member: PresenceMember)
}

data class PresenceMember(val clientId: String, val data: String)
