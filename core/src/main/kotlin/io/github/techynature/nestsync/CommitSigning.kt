package io.github.techynature.nestsync

import org.eclipse.jgit.lib.Config
import org.eclipse.jgit.storage.file.FileBasedConfig
import org.eclipse.jgit.util.FS
import org.eclipse.jgit.util.SystemReader
import java.io.IOException

/**
 * NestSync never signs commits (the Android app can't, and JGit can't sign with SSH keys).
 *
 * On a computer, your global git settings (~/.gitconfig) might ask for signed commits,
 * for example `gpg.format = ssh` and `commit.gpgsign = true`. JGit doesn't understand
 * `gpg.format = ssh` and would stop every sync with "Invalid value: gpg.format=ssh".
 *
 * [install] makes NestSync read your global settings as usual (name, email, everything
 * else), with the signing settings switched off on top. It only changes what NestSync
 * sees in memory: your ~/.gitconfig file is never edited, and the git command line keeps
 * signing exactly as before.
 */
object CommitSigning {

    /** Call once at startup, before any git work. Safe to call twice. */
    fun install() {
        val current = SystemReader.getInstance()
        if (current !is IgnoreSigningReader) {
            SystemReader.setInstance(IgnoreSigningReader(current))
        }
    }

    /** The settings NestSync lays over your own, so nothing asks for a signature. */
    internal fun switchOffSigning(config: Config) {
        config.setString("gpg", null, "format", "openpgp")
        config.setBoolean("commit", null, "gpgsign", false)
        config.setBoolean("tag", null, "gpgsign", false)
        config.setBoolean("tag", null, "forceSignAnnotated", false)
    }

    /** Wraps JGit's normal SystemReader and puts a signing-off layer over the user config. */
    internal class IgnoreSigningReader(private val base: SystemReader) : SystemReader() {
        override fun getHostname(): String = base.hostname
        override fun getenv(variable: String?): String? = base.getenv(variable)
        override fun getProperty(key: String?): String? = base.getProperty(key)
        override fun getCurrentTime(): Long = base.currentTime
        override fun getTimezone(`when`: Long): Int = base.getTimezone(`when`)
        override fun openSystemConfig(parent: Config?, fs: FS?): FileBasedConfig = base.openSystemConfig(parent, fs)
        override fun openJGitConfig(parent: Config?, fs: FS?): FileBasedConfig = base.openJGitConfig(parent, fs)
        override fun openUserConfig(parent: Config?, fs: FS?): FileBasedConfig =
            IgnoreSigningConfig(base.openUserConfig(parent, fs), fs)
    }

    /**
     * Sits on top of the real user config. Everything is read from the real file (its
     * "base"), except the signing settings, which this layer turns off. Saving is refused,
     * so the real file is never written.
     */
    internal class IgnoreSigningConfig(
        real: FileBasedConfig,
        fs: FS?,
    ) : FileBasedConfig(real, real.file, fs) {
        init {
            switchOffSigning(this)
        }

        // Nothing of its own to read: JGit loads the real file (the base) by itself.
        override fun load() {}

        override fun isOutdated(): Boolean = false

        override fun save() {
            throw IOException("NestSync doesn't change your global git settings.")
        }
    }
}
