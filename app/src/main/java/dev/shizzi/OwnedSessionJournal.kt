package dev.shizzi

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID

internal data class OwnedSessionRecord(val bootId: String, val types: Set<TetheringType>) {
    fun encode(): String = JSONObject().apply {
        put("version", 1)
        put("boot", bootId)
        put("types", org.json.JSONArray(types.map { it.name }.sorted()))
    }.toString()

    companion object {
        fun decode(text: String): OwnedSessionRecord {
            val json = JSONObject(text)
            require(json.getInt("version") == 1) { "Unsupported ownership journal version" }
            val boot = json.getString("boot")
            require(BOOT_PATTERN.matches(boot)) { "Invalid ownership journal boot ID" }
            val values = json.getJSONArray("types")
            require(values.length() <= TetheringType.entries.size) { "Invalid ownership journal types" }
            val types = (0 until values.length()).map { TetheringType.valueOf(values.getString(it)) }.toSet()
            return OwnedSessionRecord(boot, types)
        }

        private val BOOT_PATTERN = Regex("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}")
    }
}

internal fun ownedTypesForRecovery(record: OwnedSessionRecord, bootId: String): Set<TetheringType> =
    if (record.bootId == bootId) record.types else emptySet()

/** Records pending starts before Android receives them. Only the shell helper reads this file. */
object OwnedSessionJournal {
    private const val PATH = "/data/local/tmp/shizzi-owned-session.json"
    private const val SHELL_UID = 2000
    private const val MAX_BYTES = 4096

    @Synchronized
    fun writeOwned(types: Set<TetheringType>) {
        check(Os.getuid() == SHELL_UID) { "Only the shell helper can write session ownership" }
        val record = OwnedSessionRecord(currentBootId(), types)
        verifyExistingTarget()
        val temporary = "$PATH.${UUID.randomUUID()}.tmp"
        try {
            val fd = Os.open(temporary, OsConstants.O_WRONLY or OsConstants.O_CREAT or
                OsConstants.O_EXCL or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC, 384)
            FileOutputStream(fd).use { stream ->
                verify(Os.fstat(fd))
                stream.write(record.encode().toByteArray(Charsets.UTF_8))
                stream.flush()
                stream.fd.sync()
            }
            verifyExistingTarget()
            Os.rename(temporary, PATH)
        } finally {
            runCatching { Os.remove(temporary) }
        }
    }

    /** Call only for a new helper, after taking the singleton lock. */
    @Synchronized
    fun recoverOwnedDownstreams(context: Context): String? = runCatching {
        check(Os.getuid() == SHELL_UID) { "Only the shell helper can recover session ownership" }
        val record = read() ?: return@runCatching null
        val types = ownedTypesForRecovery(record, currentBootId())
        if (types.isEmpty()) {
            writeOwned(emptySet())
            return@runCatching null
        }
        val problem = SessionTeardown(context).releaseDownstream(types)
        if (problem == null) {
            // This preference belonged to the recorded session. Do not clear it without a journal.
            TetheringPreferenceApi(context).setPreferTestNetworks(false)
            writeOwned(emptySet())
        }
        problem
    }.getOrElse { "Could not recover Shizzi's recorded tethering: ${it.message}" }

    private fun currentBootId(): String = File("/proc/sys/kernel/random/boot_id").readText().trim()

    private fun read(): OwnedSessionRecord? {
        val fd = try {
            Os.open(PATH, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC, 0)
        } catch (error: ErrnoException) {
            if (error.errno == OsConstants.ENOENT) return null
            throw error
        }
        return FileInputStream(fd).use { stream ->
            verify(Os.fstat(fd))
            val bytes = ByteArray(MAX_BYTES + 1)
            var length = 0
            while (length < bytes.size) {
                val count = stream.read(bytes, length, bytes.size - length)
                if (count < 0) break
                length += count
            }
            check(length <= MAX_BYTES) { "Ownership journal is too large" }
            OwnedSessionRecord.decode(String(bytes, 0, length, Charsets.UTF_8))
        }
    }

    private fun verifyExistingTarget() {
        try {
            verify(Os.lstat(PATH))
        } catch (error: ErrnoException) {
            if (error.errno != OsConstants.ENOENT) throw error
        }
    }

    private fun verify(stat: StructStat) {
        check(OsConstants.S_ISREG(stat.st_mode)) { "Ownership journal is not a regular file" }
        check(stat.st_uid == SHELL_UID) { "Ownership journal has another owner" }
        check(stat.st_nlink == 1L) { "Ownership journal has multiple links" }
        check(stat.st_mode and 511 == 384) { "Ownership journal must have mode 0600" }
    }
}
