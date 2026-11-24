package io.github.techynature.nestsync

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Config
import org.eclipse.jgit.lib.RepositoryState
import org.eclipse.jgit.storage.file.FileBasedConfig
import org.eclipse.jgit.util.FS
import org.eclipse.jgit.util.SystemReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Runs real syncs between two clones of a local "server" repo. */
class GitEngineTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val me = Identity("Tester", "tester@example.com")
    private val noCreds: (String) -> HostCredential? = { null }

    private fun setup(): Triple<File, File, File> {
        val server = tmp.newFolder("server.git")
        Git.init().setBare(true).setDirectory(server).setInitialBranch("main").call().close()
        // Seed the server with one commit.
        val seed = tmp.newFolder("seed")
        Git.init().setDirectory(seed).setInitialBranch("main").call().use { g ->
            File(seed, "story.tw").writeText("line one\nline two\n")
            g.add().addFilepattern(".").call()
            g.commit().setMessage("seed").setAuthor("s", "s@x").setCommitter("s", "s@x").call()
            g.push().setRemote(server.path).add("main").call()
        }
        val a = tmp.newFolder("a")
        val b = tmp.newFolder("b")
        Git.cloneRepository().setURI(server.path).setDirectory(a).call().close()
        Git.cloneRepository().setURI(server.path).setDirectory(b).call().close()
        return Triple(server, a, b)
    }

    private fun sync(dir: File) = GitEngine.sync(dir, noCreds, me)

    @Test
    fun nothingToDo() {
        val (_, a, _) = setup()
        val r = sync(a)
        assertTrue(r.details.toString(), r.ok)
        assertEquals("Up to date", r.title)
    }

    @Test
    fun pushThenPull() {
        val (_, a, b) = setup()
        File(a, "chapter2.tw").writeText("new passage\n")
        val ra = sync(a)
        assertTrue(ra.details.toString(), ra.ok)
        assertEquals("Synced", ra.title)

        val rb = sync(b)
        assertTrue(rb.details.toString(), rb.ok)
        assertTrue(File(b, "chapter2.tw").exists())
    }

    @Test
    fun bothSidesChangeDifferentFiles() {
        val (_, a, b) = setup()
        File(a, "a.tw").writeText("from a\n")
        File(b, "b.tw").writeText("from b\n")
        assertTrue(sync(a).ok)
        val rb = sync(b)
        assertTrue(rb.details.toString(), rb.ok)
        assertTrue(File(b, "a.tw").exists())
        // A gets B's file too.
        assertTrue(sync(a).ok)
        assertTrue(File(a, "b.tw").exists())
    }

    @Test
    fun deletionsArePushed() {
        val (_, a, b) = setup()
        File(a, "story.tw").delete()
        assertTrue(sync(a).ok)
        assertTrue(sync(b).ok)
        assertFalse(File(b, "story.tw").exists())
    }

    @Test
    fun conflictIsReportedAndRolledBack() {
        val (_, a, b) = setup()
        File(a, "story.tw").writeText("line one\nA's version\n")
        File(b, "story.tw").writeText("line one\nB's version\n")
        assertTrue(sync(a).ok)
        val rb = sync(b)
        assertFalse(rb.ok)
        assertTrue(rb.title, rb.title.contains("Conflict"))
        Git.open(b).use { g ->
            assertEquals(RepositoryState.SAFE, g.repository.repositoryState)
            assertTrue(g.status().call().isClean)
        }
        // B's own words are still there, committed locally.
        assertTrue(File(b, "story.tw").readText().contains("B's version"))
    }

    /** A and B both edit story.tw; A also adds a new file. Returns (a, b) after B's sync stopped. */
    private fun stuckOnConflict(): Pair<File, File> {
        val (_, a, b) = setup()
        File(a, "story.tw").writeText("line one\nA's version\n")
        File(a, "mods/new.mod").apply { parentFile.mkdirs() }.writeText("new mod\n")
        assertTrue(sync(a).ok)
        File(b, "story.tw").writeText("line one\nB's version\n")
        File(b, "notes.txt").writeText("B's other edit\n")
        val first = sync(b)
        assertEquals(GitEngine.CONFLICT_TITLE, first.title)
        return a to b
    }

    @Test
    fun conflictKeepsBlockingUntilSettled() {
        val (_, b) = stuckOnConflict()
        // Syncing again the old way still can't bring the new file down.
        val again = sync(b)
        assertEquals(GitEngine.CONFLICT_TITLE, again.title)
        assertFalse(File(b, "mods/new.mod").exists())
    }

    @Test
    fun useServerCopyBringsEverythingDown() {
        val (a, b) = stuckOnConflict()
        val r = GitEngine.sync(b, noCreds, me, onConflict = ConflictChoice.USE_SERVER)
        assertTrue(r.details.toString(), r.ok)
        // The server's changes came down, including the file that didn't clash.
        assertEquals("line one\nA's version\n", File(b, "story.tw").readText())
        assertTrue(File(b, "mods/new.mod").exists())
        // B's copy was saved beside the repo, and B's other edit was still uploaded.
        val backups = File(b.parentFile, "NestSync backups/${b.name}")
        val saved = backups.walkTopDown().first { it.name == "story.tw" }
        assertTrue(saved.readText().contains("B's version"))
        Git.open(b).use { g ->
            assertEquals(RepositoryState.SAFE, g.repository.repositoryState)
            assertTrue(g.status().call().isClean)
        }
        assertTrue(sync(a).ok)
        assertTrue(File(a, "notes.txt").exists())
        assertEquals("line one\nA's version\n", File(a, "story.tw").readText())
    }

    @Test
    fun keepMineUploadsThisDevicesCopy() {
        val (a, b) = stuckOnConflict()
        val r = GitEngine.sync(b, noCreds, me, onConflict = ConflictChoice.KEEP_MINE)
        assertTrue(r.details.toString(), r.ok)
        assertEquals("line one\nB's version\n", File(b, "story.tw").readText())
        assertTrue(File(b, "mods/new.mod").exists())
        assertTrue(sync(a).ok)
        assertEquals("line one\nB's version\n", File(a, "story.tw").readText())
    }

    @Test
    fun useServerCopyWhenServerDeletedTheFile() {
        val (_, a, b) = setup()
        File(a, "story.tw").delete()
        assertTrue(sync(a).ok)
        File(b, "story.tw").writeText("line one\nB's version\n")
        assertEquals(GitEngine.CONFLICT_TITLE, sync(b).title)
        val r = GitEngine.sync(b, noCreds, me, onConflict = ConflictChoice.USE_SERVER)
        assertTrue(r.details.toString(), r.ok)
        assertFalse(File(b, "story.tw").exists())
        Git.open(b).use { g -> assertTrue(g.status().call().isClean) }
    }

    @Test
    fun severalLocalCommitsWithConflicts() {
        val (_, a, b) = setup()
        File(a, "story.tw").writeText("line one\nA's version\n")
        assertTrue(sync(a).ok)
        // Two earlier local commits on B that never got pushed.
        File(b, "story.tw").writeText("line one\nB first\n")
        assertEquals(GitEngine.CONFLICT_TITLE, sync(b).title)
        File(b, "story.tw").writeText("line one\nB second\n")
        assertEquals(GitEngine.CONFLICT_TITLE, sync(b).title)
        val r = GitEngine.sync(b, noCreds, me, onConflict = ConflictChoice.USE_SERVER)
        assertTrue(r.details.toString(), r.ok)
        assertEquals("line one\nA's version\n", File(b, "story.tw").readText())
        Git.open(b).use { g ->
            assertEquals(RepositoryState.SAFE, g.repository.repositoryState)
            assertEquals(0, GitEngine.unpushedCount(g))
        }
    }

    @Test
    fun unpushedCommitsAreCounted() {
        val (_, a, _) = setup()
        File(a, "new.tw").writeText("x\n")
        Git.open(a).use { g ->
            g.add().addFilepattern(".").call()
            g.commit().setMessage("local").setAuthor("t", "t@x").setCommitter("t", "t@x").call()
        }
        assertEquals(1, GitEngine.inspect(a).unpushed)
        assertEquals(0, GitEngine.inspect(a).changedFiles)
        val r = sync(a)
        assertTrue(r.details.toString(), r.ok)
        assertTrue(r.details.toString(), r.details.any { "waiting to upload" in it })
        assertEquals(0, GitEngine.inspect(a).unpushed)
    }

    @Test
    fun pushPermissionErrorIsExplained() {
        val msg = GitEngine.friendly(
            org.eclipse.jgit.api.errors.TransportException(
                "https://github.com/x/y.git: git-receive-pack not permitted on 'https://github.com/x/y.git/'",
            ),
        )
        assertTrue(msg, msg.contains("WRITE access"))
    }

    @Test
    fun sshUrlsAreConverted() {
        assertTrue(GitEngine.isSsh("git@github.com:Techy-Nature/NestSync.git"))
        assertEquals(
            "https://github.com/Techy-Nature/NestSync.git",
            GitEngine.sshToHttps("git@github.com:Techy-Nature/NestSync.git"),
        )
        assertEquals("https://git.gay/me/story.git", GitEngine.sshToHttps("ssh://git@git.gay/me/story.git"))
        assertFalse(GitEngine.isSsh("https://git.gay/me/story.git"))
        assertEquals("git.gay", GitEngine.hostOf("https://git.gay/me/story.git"))
    }

    // ---------- global git settings on a computer ----------

    /** Pretends ~/.gitconfig is [file], so the test doesn't depend on the machine running it. */
    private class FakeHomeReader(private val file: File) : SystemReader() {
        private val base = SystemReader.getInstance()
        override fun getHostname(): String = base.hostname
        override fun getenv(variable: String?): String? = base.getenv(variable)
        override fun getProperty(key: String?): String? = base.getProperty(key)
        override fun getCurrentTime(): Long = base.currentTime
        override fun getTimezone(`when`: Long): Int = base.getTimezone(`when`)
        override fun openSystemConfig(parent: Config?, fs: FS?): FileBasedConfig = base.openSystemConfig(parent, fs)
        override fun openJGitConfig(parent: Config?, fs: FS?): FileBasedConfig = base.openJGitConfig(parent, fs)
        override fun openUserConfig(parent: Config?, fs: FS?): FileBasedConfig = FileBasedConfig(parent, file, fs)
    }

    @After
    fun resetGitSettings() {
        SystemReader.setInstance(null)
    }

    @Test
    fun sshSigningInGlobalSettingsDoesNotBreakSync() {
        val (_, a, _) = setup()
        val globalConfig = tmp.newFile("gitconfig")
        val original = """
            [user]
            	name = Global Name
            	email = global@example.com
            [gpg]
            	format = ssh
            [commit]
            	gpgsign = true
        """.trimIndent() + "\n"
        globalConfig.writeText(original)

        // Without the fix, JGit can't read gpg.format = ssh and the sync fails.
        SystemReader.setInstance(FakeHomeReader(globalConfig))
        File(a, "one.tw").writeText("1\n")
        val before = sync(a)
        assertFalse(before.ok)
        assertTrue(before.details.toString(), before.details.any { "signed commits" in it })

        // With it, the sync works, and the commit uses the global name.
        SystemReader.setInstance(CommitSigning.IgnoreSigningReader(FakeHomeReader(globalConfig)))
        val after = GitEngine.sync(a, noCreds, me)
        assertTrue(after.details.toString(), after.ok)
        Git.open(a).use { g ->
            assertEquals("Global Name", g.log().setMaxCount(1).call().first().authorIdent.name)
        }
        // And the real settings file is untouched.
        assertEquals(original, globalConfig.readText())
    }
}
