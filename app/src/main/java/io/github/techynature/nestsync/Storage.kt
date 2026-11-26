package io.github.techynature.nestsync

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** One folder the person syncs. */
data class RepoEntry(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val path: String,
    val lastOk: Boolean? = null,
    val lastTitle: String? = null,
    val lastDetails: List<String> = emptyList(),
    val lastTime: Long = 0L,
    /** The files the last sync stopped on, so the repo screen can offer a choice for each. */
    val lastClashes: List<Clash> = emptyList(),
)

/** Saves the repo list as JSON in the app's private settings. */
class RepoStore(context: Context) {
    private val prefs = context.getSharedPreferences("repos", Context.MODE_PRIVATE)

    fun load(): List<RepoEntry> {
        val raw = prefs.getString("list", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
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
                    lastClashes = readClashes(o),
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
            o.put(
                "lastClashes",
                JSONArray(r.lastClashes.map { JSONObject().put("path", it.path).put("kind", it.kind.name) }),
            )
            arr.put(o)
        }
        prefs.edit().putString("list", arr.toString()).apply()
    }

    private fun readClashes(o: JSONObject): List<Clash> {
        val arr = o.optJSONArray("lastClashes") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val c = arr.optJSONObject(i) ?: return@mapNotNull null
            val kind = runCatching { ClashKind.valueOf(c.optString("kind")) }.getOrDefault(ClashKind.BOTH_CHANGED)
            c.optString("path").takeIf { it.isNotEmpty() }?.let { Clash(it, kind) }
        }
    }
}

/**
 * Tokens per host. The token is encrypted with a key that lives in the Android
 * Keystore, so it never sits in plain text on disk.
 */
class CredentialStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("hosts", Context.MODE_PRIVATE)

    fun all(): List<HostCredential> {
        val raw = prefs.getString("list", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val token = decrypt(o.getString("token")) ?: return@mapNotNull null
                HostCredential(o.getString("host"), o.getString("username"), token)
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
                    .put("token", encrypt(it.token)),
            )
        }
        prefs.edit().putString("list", arr.toString()).apply()
    }

    // ---- Android Keystore AES-GCM ----

    private val alias = "nestsync-token-key"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(body, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String? = try {
        val (ivB64, bodyB64) = stored.split(":", limit = 2)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(ivB64, Base64.NO_WRAP)))
        String(cipher.doFinal(Base64.decode(bodyB64, Base64.NO_WRAP)), Charsets.UTF_8)
    } catch (e: Exception) {
        null
    }
}

/** Small app-wide settings: who commits are made as. */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var identity: Identity
        get() = Identity(
            prefs.getString("name", null) ?: "NestSync",
            prefs.getString("email", null) ?: "nestsync@localhost",
        )
        set(value) {
            prefs.edit().putString("name", value.name).putString("email", value.email).apply()
        }

    var cloneFolder: String
        get() = prefs.getString("cloneFolder", null) ?: "/storage/emulated/0/Stories"
        set(value) {
            prefs.edit().putString("cloneFolder", value).apply()
        }
}
