package com.smartwalkie.voicepingsdk.exception

/**
 * Created by kukuhsain on 17/11/17.
 */
object ErrorCode {
    const val UNKNOWN = 0
    const val INTERNET_DISCONNECTED = 1
    const val SOCKET_DISCONNECTED = 2
    const val ACK_START_TIMEOUT = 4
    const val ACK_END_TIMEOUT = 5
    const val CONNECTION_FAILURE = 6

    /** Router closed the socket with 4409: another device took the session. The SDK does not reconnect. */
    const val SESSION_TAKEN = 8

    /** Router rejected the token ("Unauthorized User"). The SDK does not reconnect; sign in again. */
    const val UNAUTHORIZED = 9

    /** Token expired and no TokenProvider could supply a fresh one. The SDK does not reconnect. */
    const val TOKEN_EXPIRED = 10

    /** START_FAILED "NotAllowed": the talk groups do not allow this talk. */
    const val START_NOT_ALLOWED = 11

    /** START_FAILED "Busy": someone else holds the floor of the site. */
    const val START_BUSY = 12

    /** START_FAILED "Offline": private target is offline (a missed talk is recorded). */
    const val START_OFFLINE = 13

    /** START_FAILED "Error": router failed to check the talk. */
    const val START_ERROR = 14
}
