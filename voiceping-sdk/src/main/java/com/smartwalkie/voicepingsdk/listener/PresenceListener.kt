package com.smartwalkie.voicepingsdk.listener

/**
 * Router events that arrive on the open connection. Callbacks fire on the main thread.
 *
 * Versions are opaque strings from the router; keep the newest per id so an older reply never
 * overwrites a newer event.
 */
interface PresenceListener {
    /** Reply to [com.smartwalkie.voicepingsdk.VoicePing.peopleSub]: the current state of every allowed id. */
    fun onPresenceSnapshot(people: List<PersonPresence>, sites: List<SiteVersion>)

    /** One person changed. */
    fun onPresence(person: PersonPresence)

    /** The team changed: refetch the team list, then call peopleSub again. */
    fun onTeamChanged()

    /** Duty changed at a site this connection listens to. */
    fun onSiteStatus(site: SiteVersion)
}

data class PersonPresence(val id: String, val online: Boolean, val version: String)

/** Duty version of a site; [id] is the bare site id, without the "site:" prefix. */
data class SiteVersion(val id: String, val version: String)
