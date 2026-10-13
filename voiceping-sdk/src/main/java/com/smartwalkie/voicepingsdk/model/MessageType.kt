package com.smartwalkie.voicepingsdk.model

object MessageType {
    const val UNKNOWN = -1
    const val START_TALKING = 1
    const val STOP_TALKING = 2
    const val AUDIO = 3
    const val ACK_START = 6
    const val ACK_END = 7
    const val ACK_START_FAILED = 8
    const val SUBSCRIBE = 28
    const val UNSUBSCRIBE = 29
    const val PRESENCE = 33
    const val PEOPLE_SUB = 34
    const val TEAM_CHANGED = 35
    const val SITE_STATUS = 36

    @JvmStatic
    fun getText(messageType: Int): String {
        return when (messageType) {
            START_TALKING -> "START_TALKING"
            STOP_TALKING -> "STOP_TALKING"
            AUDIO -> "AUDIO"
            ACK_START -> "ACK_START"
            ACK_END -> "ACK_END"
            ACK_START_FAILED -> "ACK_START_FAILED"
            SUBSCRIBE -> "SUBSCRIBE"
            UNSUBSCRIBE -> "UNSUBSCRIBE"
            PRESENCE -> "PRESENCE"
            PEOPLE_SUB -> "PEOPLE_SUB"
            TEAM_CHANGED -> "TEAM_CHANGED"
            SITE_STATUS -> "SITE_STATUS"
            else -> "UNKNOWN"
        }
    }
}
