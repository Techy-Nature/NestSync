package io.github.techynature.nestsync

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID

/** One folder the person syncs. */
data class RepoEntry(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val path: String,
    val lastOk: Boolean? = null,
    val lastTitle: String? = null,
    val lastDetails: List<String> = emptyList(),
    val lastTime: Long = 0L,
)

/** Where NestSync keeps its own files on this computer. */
object AppDirs {
    val isMac: Boolean = System.getProperty("os.name").orEmpty().lowercase().contains("mac")

    val home: File = File(System.getProperty("user.home"))

    /**
     * macOS: ~/Library/Application Support/NestSync
     * Linux: $XDG_CONFIG_HOME/NestSync, or ~/.config/NestSync
     */
    val config: File by lazy {
        val dir = if (isMac) {
            File(home, "Library/Application Support/NestSync")
        } else {
            val xdg = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }
            File(xdg ?: File(home, ".config").path, "NestSync")
        }
        dir.mkdirs()
        ownerOnly(dir, isDirectory = true)
        dir
    }

    /** Makes a file or folder readable only by the person's own account (like chmod 600 / 700). */
    fun ownerOnly(file: File, isDirectory: Boolean = false) {
        try {
            Files.setPosixFilePermissions(
                file.toPath(),
                PosixFilePermissions.fromString(if (isDirectory) "rwx------" else "rw-------"),
            )
        } catch (_: Exception) {
            // Not a POSIX file system: leave the default permissions.
        }
    }

    /** Writes the whole file at once, so a crash never leaves half a file behind. */
    fun writeAtomically(target: File, text: String, private: Boolean = false) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text)
        if (private) ownerOnly(tmp)
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}

/** Saves the repo list as JSON in repos.json. */
class RepoStore {
    private val file = File(AppDirs.config, "repos.json")

    fun load(): List<RepoEntry> {
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val details = o.optJSONArray("lastDetails")
                RepoEntry(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    path = o.getString("path"),
                    lastOk = if (o.has("lastOk")) o.getBoolean("lastOk") else null,
                    lastTitle = o.optString("lastTitle").takeIf { it.isNotEmpty() },
                    lastDetails = if (details == null) emptyList() else (0 until details.length()).map { details.getString(it) },
                    lastTime = o.optLong("lastTime", 0L),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun save(list: List<RepoEntry>) {
        val arr = JSONArray()
        list.forEach { r ->
            val o = JSONObject()
            o.put("id", r.id)
            o.put("name", r.name)
            o.put("path", r.path)
            r.lastOk?.let { o.put("lastOk", it) }
            r.lastTitle?.let { o.put("lastTitle", it) }
            o.put("lastDetails", JSONArray(r.lastDetails))
            o.put("lastTime", r.lastTime)
            arr.put(o)
        }
        AppDirs.writeAtomically(file, arr.toString(2))
    }
}

/**
 * Tokens per host, in hosts.json. The file (and its folder) can only be read by your
 * own account, the same way git's credential store and the GitHub CLI keep tokens.
 */
class CredentialStore {
    private val file = File(AppDirs.config, "hosts.json")

    fun all(): List<HostCredential> {
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                HostCredential(o.getString("host"), o.getString("username"), o.getString("token"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun forHost(host: String): HostCredential? =
        all().firstOrNull { it.host.equals(host, ignoreCase = true) }

    fun put(cred: HostCredential) {
        val list = all().filterNot { it.host.equals(cred.host, ignoreCase = true) } + cred
        write(list)
    }

    fun remove(host: String) = write(all().filterNot { it.host.equals(host, ignoreCase = true) })

    private fun write(list: List<HostCredential>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject()
                    .put("host", it.host.lowercase())
                    .put("username", it.username)
                    .put("token", it.token),
            )
        }
        AppDirs.writeAtomically(file, arr.toString(2), private = true)
    }
}

/** Small app-wide settings: who commits are made as, and where clones go. */
class AppSettings {
    private val file = File(AppDirs.config, "settings.json")

    private fun read(): JSONObject = try {
        if (file.exists()) JSONObject(file.readText()) else JSONObject()
    } catch (e: Exception) {
        JSONObject()
    }

    private fun edit(change: JSONObject.() -> Unit) {
        val o = read()
        o.change()
        AppDirs.writeAtomically(file, o.toString(2))
    }

    var identity: Identity
        get() {
            val o = read()
            return Identity(
                o.optString("name").ifBlank { "NestSync" },
                o.optString("email").ifBlank { "nestsync@localhost" },
            )
        }
        set(value) = edit { put("name", value.name); put("email", value.email) }

    var cloneFolder: String
        get() = read().optString("cloneFolder").ifBlank { File(AppDirs.home, "Stories").path }
        set(value) = edit { put("cloneFolder", value) }
}
