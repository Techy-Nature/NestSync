package io.github.techynature.nestsync

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.RebaseCommand
import org.eclipse.jgit.api.RebaseResult
import org.eclipse.jgit.api.errors.TransportException
import org.eclipse.jgit.dircache.DirCacheEntry
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Username + token for one git host (for example "git.gay" or "github.com"). */
data class HostCredential(val host: String, val username: String, val token: String)

/** Outcome of one sync, shown to the person as-is. */
data class SyncOutcome(val ok: Boolean, val title: String, val details: List<String>)

/** Who commits are made as, when the repo has no user.name / user.email of its own. */
data class Identity(val name: String, val email: String)

/**
 * What a sync does when the server and this device changed the same lines of a file.
 *  - STOP: undo the pull, change nothing, and list the files (the old behaviour).
 *  - USE_SERVER: take the server's copy of those files. This device's copy is saved
 *    to a backup folder first. Every other file still syncs both ways.
 *  - KEEP_MINE: keep this device's copy of those files and upload it.
 */
enum class ConflictChoice { STOP, USE_SERVER, KEEP_MINE }

/**
 * Words that differ between the Android app and the desktop app. Android keeps these
 * defaults; the desktop app swaps them in once at startup.
 */
object Wording {
    /** "phone" or "computer", used in commit messages and explanations. */
    var device = "phone"
    /** Where the person runs git by hand to fix a conflict. */
    var terminal = "Termux"
    /** "Tap" on a phone, "Click" on a computer. */
    var tap = "Tap"
    /** Shown when the system won't let NestSync read or write a folder. */
    var folderBlocked = "Android blocked access to the folder. Give the app 'All files access' (see the banner at the top)."
}

/**
 * All the git work lives here. It only uses JGit and java.io, so the Android app and
 * the desktop app share it, and it can be tested on a normal computer.
 */
object GitEngine {

    /** Title of a sync that stopped on a conflict. The apps show the conflict buttons when they see it. */
    const val CONFLICT_TITLE = "Conflict, nothing was pulled or pushed"

    // ---------- URL helpers ----------

    /** Returns the host of a git remote URL ("git.gay", "github.com"), or null. */
    fun hostOf(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return try {
            URIish(url.trim()).host?.lowercase(Locale.ROOT)
        } catch (e: Exception) {
            null
        }
    }

    /** True when the URL uses SSH (git@host:path or ssh://), which this app can't use. */
    fun isSsh(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val u = url.trim()
        if (u.startsWith("ssh://")) return true
        if (u.startsWith("http://") || u.startsWith("https://")) return false
        // scp-like syntax: user@host:path
        return Regex("^[^/@]+@[^/:]+:.+").matches(u)
    }

    /** Turns "git@host:user/repo.git" or "ssh://git@host/user/repo.git" into the https form. */
    fun sshToHttps(url: String): String {
        val u = url.trim()
        if (!isSsh(u)) return u
        return try {
            val uri = URIish(u)
            val path = uri.path.trimStart('/')
            "https://${uri.host}/$path"
        } catch (e: Exception) {
            u
        }
    }

    // ---------- Repo inspection ----------

    /** Quick, offline summary of a folder: is it a git repo, which branch, how many changed files. */
    data class RepoInfo(
        val isRepo: Boolean,
        val branch: String?,
        val remoteUrl: String?,
        val changedFiles: Int,
        val error: String? = null,
        /** Commits made on this device that the server doesn't have yet (as of the last fetch). */
        val unpushed: Int = 0,
    )

    fun inspect(folder: File): RepoInfo {
        if (!folder.exists()) return RepoInfo(false, null, null, 0, "Folder not found")
        if (!File(folder, ".git").exists()) return RepoInfo(false, null, null, 0, "Not a git repo (no .git folder)")
        return try {
            Git.open(folder).use { git ->
                val repo = git.repository
                val status = git.status().call()
                val changed = status.uncommittedChanges.size + status.untracked.size
                RepoInfo(true, repo.branch, remoteUrl(repo), changed, unpushed = unpushedCount(git))
            }
        } catch (e: Exception) {
            RepoInfo(false, null, null, 0, friendly(e))
        }
    }

    fun remoteUrl(repo: Repository): String? =
        repo.config.getString("remote", "origin", "url")

    fun setRemoteUrl(folder: File, url: String) {
        Git.open(folder).use { git ->
            val cfg = git.repository.config
            cfg.setString("remote", "origin", "url", url.trim())
            if (cfg.getString("remote", "origin", "fetch") == null) {
                cfg.setString("remote", "origin", "fetch", "+refs/heads/*:refs/remotes/origin/*")
            }
            cfg.save()
        }
    }

    /**
     * Phone shared storage can't store the "executable" bit, which can make every file
     * look changed. core.fileMode=false tells git to ignore that (Termux needs the same).
     */
    fun relaxFileModes(folder: File) {
        try {
            Git.open(folder).use { git ->
                val cfg = git.repository.config
                if (cfg.getBoolean("core", null, "filemode", true)) {
                    cfg.setBoolean("core", null, "filemode", false)
                    cfg.save()
                }
            }
        } catch (_: Exception) {
        }
    }

    // ---------- Sync ----------

    /**
     * Same steps as the old Termux shortcut:
     *   git add -A ; git commit -m "..." ; git pull --rebase ; git push
     * If the pull hits a conflict, [onConflict] decides: STOP undoes the rebase so nothing
     * is lost and lists the files; USE_SERVER or KEEP_MINE settles each clash that way and
     * finishes the sync, so the rest of the server's changes still come down.
     */
    fun sync(
        folder: File,
        credentials: (host: String) -> HostCredential?,
        fallbackIdentity: Identity,
        commitMessage: String = defaultCommitMessage(),
        onConflict: ConflictChoice = ConflictChoice.STOP,
        progress: (String) -> Unit = {},
    ): SyncOutcome {
        val notes = mutableListOf<String>()
        try {
            if (!File(folder, ".git").exists()) {
                return SyncOutcome(false, "Not a git repo", listOf("No .git folder in ${folder.path}"))
            }
            Git.open(folder).use { git ->
                val repo = git.repository
                val url = remoteUrl(repo)
                    ?: return SyncOutcome(false, "No remote", listOf("This repo has no 'origin' remote. Add one in the repo's settings."))
                if (isSsh(url)) {
                    return SyncOutcome(
                        false, "SSH remote not supported",
                        listOf("The remote is $url", "${Wording.tap} the repo, then 'Switch remote to HTTPS'."),
                    )
                }
                val host = hostOf(url)
                val cred = host?.let(credentials)
                val cp: CredentialsProvider? = cred?.let { UsernamePasswordCredentialsProvider(it.username, it.token) }

                val fullBranch = repo.fullBranch
                if (fullBranch == null || !fullBranch.startsWith(Constants.R_HEADS)) {
                    return SyncOutcome(false, "Not on a branch", listOf("HEAD is detached. Check out a branch in ${Wording.terminal} first."))
                }
                val branch = Repository.shortenRefName(fullBranch)
                val remoteBranch = repo.config.getString("branch", branch, "merge")
                    ?.let { Repository.shortenRefName(it) } ?: branch
                val trackingRef = "refs/remotes/origin/$remoteBranch"

                // 1. Commit local changes
                progress("Checking for changes…")
                val status = git.status().call()
                val changed = (status.uncommittedChanges + status.untracked).size
                if (status.conflicting.isNotEmpty()) {
                    return SyncOutcome(
                        false, "Unfinished conflict",
                        listOf("These files still have conflict markers:") + status.conflicting.map { "• $it" } +
                            "Fix them in ${Wording.terminal} (git status), then sync again.",
                    )
                }
                if (changed > 0) {
                    progress("Committing $changed file(s)…")
                    git.add().addFilepattern(".").call()
                    git.add().addFilepattern(".").setUpdate(true).call() // picks up deletions
                    val who = identityFor(repo, fallbackIdentity)
                    val ident = PersonIdent(who.name, who.email)
                    git.commit().setMessage(commitMessage).setAuthor(ident).setCommitter(ident).call()
                    notes += "Committed $changed changed file(s)"
                } else {
                    val waiting = unpushedCount(git)
                    notes += if (waiting > 0) {
                        "No new edits, but $waiting earlier commit(s) are still waiting to upload"
                    } else {
                        "No local changes to commit"
                    }
                }

                // 2. Fetch
                progress("Fetching from $host…")
                git.fetch().setRemote("origin").setCredentialsProvider(cp).call()

                // 3. Rebase onto the remote branch (git pull --rebase)
                val remoteId = repo.resolve(trackingRef)
                val headBefore = repo.resolve(Constants.HEAD)
                if (remoteId == null) {
                    notes += "Branch '$remoteBranch' isn't on the server yet; it will be created"
                } else if (headBefore == null) {
                    // Brand new local repo with no commits: just take the remote's history.
                    git.checkout().setName(branch).setCreateBranch(true).setStartPoint(trackingRef).call()
                    notes += "Downloaded the existing history"
                } else {
                    val incoming = countCommits(git, headBefore, remoteId)
                    if (incoming > 0) {
                        progress("Pulling $incoming new commit(s)…")
                        var result = git.rebase().setUpstream(trackingRef).call()
                        val chosen = mutableListOf<String>()
                        var backupDir: File? = null
                        var rounds = 0
                        // Each local commit that clashes stops the rebase once; settle it and go on.
                        while (result.status == RebaseResult.Status.STOPPED && onConflict != ConflictChoice.STOP && rounds < 500) {
                            rounds++
                            val files = git.status().call().conflicting.sorted()
                            if (files.isEmpty()) {
                                // Stopped with nothing to settle (for example an empty commit): just move on.
                                result = git.rebase().setOperation(RebaseCommand.Operation.SKIP).call()
                                continue
                            }
                            if (onConflict == ConflictChoice.USE_SERVER && backupDir == null) {
                                backupDir = backupFolder(folder)
                            }
                            for (path in files) settleConflict(git, path, onConflict, backupDir)
                            chosen += files
                            result = git.rebase().setOperation(RebaseCommand.Operation.CONTINUE).call()
                            // Taking the server's copy can leave a local commit with nothing in it.
                            // The rebase then waits for that commit to be dropped.
                            while (result.status == RebaseResult.Status.NOTHING_TO_COMMIT &&
                                repo.repositoryState.isRebasing && rounds < 500
                            ) {
                                rounds++
                                result = git.rebase().setOperation(RebaseCommand.Operation.SKIP).call()
                            }
                        }
                        when (result.status) {
                            RebaseResult.Status.OK,
                            RebaseResult.Status.FAST_FORWARD,
                            RebaseResult.Status.UP_TO_DATE,
                            RebaseResult.Status.NOTHING_TO_COMMIT -> {
                                notes += "Pulled $incoming commit(s)"
                                if (chosen.isNotEmpty()) {
                                    val files = chosen.distinct()
                                    notes += if (onConflict == ConflictChoice.USE_SERVER) {
                                        "Used the server's copy of:"
                                    } else {
                                        "Kept your ${Wording.device}'s copy of:"
                                    }
                                    notes += files.map { "• $it" }
                                    if (onConflict == ConflictChoice.USE_SERVER && backupDir != null) {
                                        notes += "Your ${Wording.device}'s old copy is saved in ${backupDir.path}"
                                    }
                                }
                            }
                            RebaseResult.Status.STOPPED,
                            RebaseResult.Status.CONFLICTS -> {
                                val files = (result.conflicts ?: emptyList()) +
                                    git.status().call().conflicting
                                abortRebaseQuietly(git)
                                return SyncOutcome(
                                    false, CONFLICT_TITLE,
                                    notes + listOf(
                                        "The server and your ${Wording.device} both changed the same lines in:",
                                    ) + files.distinct().map { "• $it" } + listOf(
                                        "Your work is safe: your commit is still here, just not merged.",
                                        "Until this is settled, new changes from the server can't come down.",
                                        "Open the repo and pick 'Use server's copy' or 'Keep mine' to finish the sync.",
                                    ),
                                )
                            }
                            else -> {
                                abortRebaseQuietly(git)
                                val failing = result.failingPaths?.keys?.joinToString(", ").orEmpty()
                                return SyncOutcome(
                                    false, "Pull failed (${result.status})",
                                    notes + listOfNotNull(
                                        if (failing.isNotEmpty()) "Problem files: $failing" else null,
                                        "Your local commit is untouched.",
                                    ),
                                )
                            }
                        }
                    } else {
                        notes += "Nothing new on the server"
                    }
                }

                // 4. Push
                val headNow = repo.resolve(Constants.HEAD)
                val remoteNow = repo.resolve(trackingRef)
                val outgoing = when {
                    headNow == null -> 0
                    remoteNow == null -> -1 // whole branch is new
                    else -> countCommits(git, remoteNow, headNow)
                }
                if (outgoing == 0) {
                    notes += "Nothing to push"
                    return SyncOutcome(true, "Up to date", notes)
                }
                progress("Pushing…")
                val results = git.push()
                    .setRemote("origin")
                    .setRefSpecs(RefSpec("refs/heads/$branch:refs/heads/$remoteBranch"))
                    .setCredentialsProvider(cp)
                    .call()
                val problems = mutableListOf<String>()
                for (pr in results) {
                    for (u in pr.remoteUpdates) {
                        when (u.status) {
                            RemoteRefUpdate.Status.OK, RemoteRefUpdate.Status.UP_TO_DATE -> {}
                            RemoteRefUpdate.Status.REJECTED_NONFASTFORWARD ->
                                problems += "Server has newer commits. Sync again."
                            else -> problems += "${u.status}${u.message?.let { ": $it" } ?: ""}"
                        }
                    }
                }
                if (problems.isNotEmpty()) {
                    return SyncOutcome(false, "Push was rejected", notes + problems)
                }
                notes += if (outgoing > 0) "Pushed $outgoing commit(s)" else "Pushed new branch '$remoteBranch'"
                return SyncOutcome(true, "Synced", notes)
            }
        } catch (e: Exception) {
            return SyncOutcome(false, "Sync failed", notes + friendly(e))
        }
    }

    // ---------- Clone ----------

    fun clone(
        url: String,
        destination: File,
        credentials: (host: String) -> HostCredential?,
        progress: (String) -> Unit = {},
    ): SyncOutcome {
        return try {
            if (isSsh(url)) return SyncOutcome(false, "SSH URLs aren't supported", listOf("Use the https:// link instead: ${sshToHttps(url)}"))
            if (destination.exists() && (destination.list()?.isNotEmpty() == true)) {
                return SyncOutcome(false, "Folder isn't empty", listOf(destination.path))
            }
            val cred = hostOf(url)?.let(credentials)
            progress("Cloning…")
            Git.cloneRepository()
                .setURI(url.trim())
                .setDirectory(destination)
                .setCredentialsProvider(cred?.let { UsernamePasswordCredentialsProvider(it.username, it.token) })
                .call()
                .close()
            SyncOutcome(true, "Cloned", listOf("Saved to ${destination.path}"))
        } catch (e: Exception) {
            SyncOutcome(false, "Clone failed", listOf(friendly(e)))
        }
    }

    /** Checks a token by asking the server for a repo's refs (like git ls-remote). */
    fun testConnection(url: String, cred: HostCredential?): SyncOutcome {
        return try {
            val refs = Git.lsRemoteRepository()
                .setRemote(url.trim())
                .setCredentialsProvider(cred?.let { UsernamePasswordCredentialsProvider(it.username, it.token) })
                .call()
            SyncOutcome(true, "Connection works", listOf("The server answered with ${refs.size} branch/tag ref(s)."))
        } catch (e: Exception) {
            SyncOutcome(false, "Connection failed", listOf(friendly(e)))
        }
    }

    // ---------- helpers ----------

    fun defaultCommitMessage(): String =
        "Sync from ${Wording.device} " + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())

    private fun identityFor(repo: Repository, fallback: Identity): Identity {
        val name = repo.config.getString("user", null, "name")
        val email = repo.config.getString("user", null, "email")
        return Identity(
            name?.takeIf { it.isNotBlank() } ?: fallback.name,
            email?.takeIf { it.isNotBlank() } ?: fallback.email,
        )
    }

    /**
     * Commits on the current branch that the server's copy (as of the last fetch) doesn't
     * have. Returns 0 when that can't be worked out, for example before the first push.
     */
    fun unpushedCount(git: Git): Int = try {
        val repo = git.repository
        val branch = repo.branch
        val remoteBranch = repo.config.getString("branch", branch, "merge")
            ?.let { Repository.shortenRefName(it) } ?: branch
        val head = repo.resolve(Constants.HEAD)
        val tracking = repo.resolve("refs/remotes/origin/$remoteBranch")
        if (head == null || tracking == null) 0 else countCommits(git, tracking, head)
    } catch (e: Exception) {
        0
    }

    /** Commits reachable from [to] but not from [from]. */
    private fun countCommits(git: Git, from: org.eclipse.jgit.lib.ObjectId, to: org.eclipse.jgit.lib.ObjectId): Int =
        if (from == to) 0 else git.log().addRange(from, to).call().count()

    /**
     * Settles one conflicting file in the middle of a rebase. During a rebase, stage 2
     * ("ours") is the server's side and stage 3 ("theirs") is the commit from this device.
     */
    private fun settleConflict(git: Git, path: String, choice: ConflictChoice, backupDir: File?) {
        val repo = git.repository
        val dc = repo.readDirCache()
        var server: DirCacheEntry? = null
        var mine: DirCacheEntry? = null
        var i = dc.findEntry(path)
        while (i in 0 until dc.entryCount && dc.getEntry(i).pathString == path) {
            val e = dc.getEntry(i)
            when (e.stage) {
                DirCacheEntry.STAGE_2 -> server = e
                DirCacheEntry.STAGE_3 -> mine = e
            }
            i++
        }
        if (choice == ConflictChoice.USE_SERVER && mine != null && backupDir != null) {
            val copy = File(backupDir, path)
            copy.parentFile?.mkdirs()
            FileOutputStream(copy).use { repo.open(mine.objectId).copyTo(it) }
        }
        val keep = if (choice == ConflictChoice.USE_SERVER) server else mine
        val target = File(repo.workTree, path)
        if (keep == null) {
            // That side deleted the file.
            target.delete()
            git.rm().addFilepattern(path).call()
        } else {
            target.parentFile?.mkdirs()
            FileOutputStream(target).use { repo.open(keep.objectId).copyTo(it) }
            git.add().addFilepattern(path).call()
        }
    }

    /**
     * A new folder for this device's copies of files the server's copy replaced. It sits
     * next to the repo ("NestSync backups/<repo>/<time>") so it never gets synced or
     * picked up by a build. If that can't be written, it goes inside .git instead.
     */
    private fun backupFolder(repoFolder: File): File {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH-mm-ss", Locale.US).format(Date())
        val parent = repoFolder.absoluteFile.parentFile
        val beside = parent?.let { File(File(File(it, "NestSync backups"), repoFolder.name), stamp) }
        if (beside != null && (beside.mkdirs() || beside.isDirectory)) return beside
        val inside = File(File(File(repoFolder, ".git"), "nestsync-backups"), stamp)
        inside.mkdirs()
        return inside
    }

    private fun abortRebaseQuietly(git: Git) {
        try {
            git.rebase().setOperation(RebaseCommand.Operation.ABORT).call()
        } catch (_: Exception) {
        }
    }

    /** Turns JGit exceptions into something a person can act on. */
    fun friendly(e: Throwable): String {
        val all = generateSequence(e) { it.cause }.mapNotNull { it.message }.joinToString(" | ")
        val lower = all.lowercase(Locale.ROOT)
        return when {
            "not authorized" in lower || "authentication is required" in lower || "401" in lower ->
                "The server refused the login. Check the username and token for this host in Settings → Hosts."
            "receive-pack not permitted" in lower || "not permitted" in lower ->
                "The server let NestSync download but not upload. The token for this host needs WRITE access " +
                    "to this repo. On GitHub: fine-grained token → Repository access must include this repo, " +
                    "and Repository permissions → Contents must be \"Read and write\". " +
                    "Also check a token is saved for this host in Settings."
            "403" in lower || "forbidden" in lower ->
                "The token works but isn't allowed to do this. Make sure it has read AND write access to repositories."
            "unknownhost" in lower || "unable to resolve host" in lower || "no address associated" in lower ->
                "Can't reach the server. Are you online?"
            "timed out" in lower || "timeout" in lower -> "The server took too long to answer. Try again."
            "not found" in lower && e is TransportException ->
                "The server says this repo doesn't exist (or the token can't see it). Check the remote URL."
            "permission denied" in lower || "eacces" in lower -> Wording.folderBlocked
            "gpg.format" in lower ->
                "This repo's own git settings ask for signed commits, which NestSync can't make. " +
                    "In ${Wording.terminal}, inside the repo, run: git config commit.gpgsign false " +
                    "and git config --unset gpg.format"
            else -> e.javaClass.simpleName + ": " + (e.message ?: "unknown error")
        }
    }
}
