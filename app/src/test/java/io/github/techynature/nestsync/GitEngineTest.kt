package io.github.techynature.nestsync

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.RepositoryState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
}
