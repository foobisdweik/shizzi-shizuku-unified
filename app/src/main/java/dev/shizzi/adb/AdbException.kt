// Adapted from RikkaApps/Shizuku under Apache-2.0. Modified for standalone Shizzi.
package dev.shizzi.adb

@Suppress("NOTHING_TO_INLINE")
inline fun adbError(message: Any): Nothing = throw AdbException(message.toString())

open class AdbException : Exception {

    constructor(message: String, cause: Throwable?) : super(message, cause)
    constructor(message: String) : super(message)
    constructor(cause: Throwable) : super(cause)
    constructor()
}

class AdbInvalidPairingCodeException : AdbException()

class AdbKeyException(cause: Throwable) : AdbException(cause)

class AdbPairingRequiredException(cause: Throwable) : AdbException(cause)
