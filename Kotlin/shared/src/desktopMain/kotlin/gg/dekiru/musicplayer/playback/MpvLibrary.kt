package gg.dekiru.musicplayer.playback

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.ptr.LongByReference

/**
 * The C library's `setlocale`. libmpv refuses to start (mpv_create returns
 * NULL, "Non-C locale detected") unless numbers are formatted the C way, and
 * the JVM sets the locale from the environment (en_GB, de_DE…).
 */
@Suppress("FunctionName")
internal interface CLibrary : Library {
    fun setlocale(category: Int, locale: String?): String?

    companion object {
        /** LC_NUMERIC: 1 with glibc and musl (Linux), 4 on macOS. */
        fun lcNumeric(osName: String): Int = if (osName.lowercase().let { "mac" in it || "darwin" in it }) 4 else 1

        /** Numbers formatted the C way, as libmpv requires. Never throws. */
        fun useCNumericLocale(): Result<Unit> = runCatching {
            val c = Native.load("c", CLibrary::class.java)
            c.setlocale(lcNumeric(System.getProperty("os.name").orEmpty()), "C") ?: error("setlocale returned NULL")
            Unit
        }
    }
}

/**
 * The parts of libmpv's client API (`mpv/client.h`) [MpvEngine] uses,
 * through JNA. Every call is thread-safe on libmpv's side.
 */
@Suppress("FunctionName", "LocalVariableName")
internal interface MpvLibrary : Library {
    fun mpv_client_api_version(): NativeLong
    fun mpv_create(): Pointer?
    fun mpv_initialize(ctx: Pointer): Int
    fun mpv_terminate_destroy(ctx: Pointer)
    fun mpv_set_option_string(ctx: Pointer, name: String, data: String): Int

    /** `args`: the command and its arguments, ending with null. */
    fun mpv_command(ctx: Pointer, args: Array<String?>): Int
    fun mpv_get_property(ctx: Pointer, name: String, format: Int, data: LongByReference): Int
    fun mpv_observe_property(ctx: Pointer, reply_userdata: Long, name: String, format: Int): Int
    fun mpv_request_log_messages(ctx: Pointer, min_level: String): Int
    fun mpv_wait_event(ctx: Pointer, timeout: Double): Pointer
    fun mpv_wakeup(ctx: Pointer)
    fun mpv_error_string(error: Int): String

    companion object {
        // mpv_event_id
        const val EVENT_SHUTDOWN = 1
        const val EVENT_LOG_MESSAGE = 2
        const val EVENT_START_FILE = 6
        const val EVENT_END_FILE = 7
        const val EVENT_FILE_LOADED = 8
        const val EVENT_SEEK = 20
        const val EVENT_PLAYBACK_RESTART = 21
        const val EVENT_PROPERTY_CHANGE = 22

        // mpv_format
        const val FORMAT_NONE = 0
        const val FORMAT_FLAG = 3
        const val FORMAT_INT64 = 4
        const val FORMAT_DOUBLE = 5

        /** The client API major versions this binding fits: 1 (libmpv.so.1, mpv ≤ 0.34) and 2 (libmpv.so.2). */
        val SUPPORTED_MAJOR = 1..2

        /** Names to try, in order: the plain name, then the versioned files a runtime-only package installs. */
        fun candidateNames(osName: String): List<String> {
            val os = osName.lowercase()
            return when {
                "mac" in os || "darwin" in os -> listOf("mpv", "/opt/homebrew/lib/libmpv.dylib", "/usr/local/lib/libmpv.dylib")
                else -> listOf("mpv", "libmpv.so.2", "libmpv.so.1")
            }
        }

        /** libmpv, or why it couldn't be loaded. Never throws. */
        fun load(): Result<MpvLibrary> {
            val failures = mutableListOf<String>()
            for (name in candidateNames(System.getProperty("os.name").orEmpty())) {
                try {
                    val lib = Native.load(name, MpvLibrary::class.java, mapOf(Library.OPTION_STRING_ENCODING to "UTF-8"))
                    val version = lib.mpv_client_api_version().toLong()
                    val major = (version shr 16).toInt()
                    if (major !in SUPPORTED_MAJOR) {
                        failures += "$name: client API $major.${version and 0xffff} isn't supported"
                        continue
                    }
                    return Result.success(lib)
                } catch (e: Throwable) {
                    // UnsatisfiedLinkError (not there), or JNA's own native part missing.
                    failures += "$name: " + (e.message?.lines()?.filter { it.isNotBlank() }?.joinToString(" ")?.take(300) ?: e::class.simpleName)
                }
            }
            return Result.failure(IllegalStateException("libmpv not found (${failures.joinToString("; ")})"))
        }
    }
}

/** An mpv_event, read where libmpv put it (64-bit layout). */
internal class MpvRawEvent(private val p: Pointer) {
    val id: Int get() = p.getInt(0)
    val error: Int get() = p.getInt(4)
    val replyUserdata: Long get() = p.getLong(8)
    val data: Pointer? get() = p.getPointer(16)
}
