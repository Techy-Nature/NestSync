package io.github.techynature.nestsync

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.RebaseCommand
import org.eclipse.jgit.api.RebaseResult
import org.eclipse.jgit.api.errors.TransportException
import org.eclipse.jgit.dircache.DirCacheEntry
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.lib.RepositoryState
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

/**
 * Outcome of one sync, shown to the person as-is. When a sync stops on a clash,
 * [clashes] lists each file so the apps can offer a choice per file.
 */
data class SyncOutcome(
    val ok: Boolean,
    val title: String,
    val details: List<String>,
    val clashes: List<Clash> = emptyList(),
)

/** Who commits are made as, when the repo has no user.name / user.email of its own. */
data class Identity(val name: String, val email: String)

/**
 * What to do with a file that clashed (the server and this device both touched it).
 *  - STOP: undo the pull, change nothing, and list the files so the person can choose.
 *  - USE_SERVER: take the server's copy. This device's copy is saved to a backup folder first.
 *  - KEEP_MINE: keep this device's copy and upload it.
 * "Copy" includes "the file is gone": if the chosen side deleted the file, it gets deleted.
 */
enum class ConflictChoice { STOP, USE_SERVER, KEEP_MINE }

/** How a file clashed. "Mine" is this device, "server" is the branch on the server. */
enum class ClashKind {
    /** Both sides changed the file. */
    BOTH_CHANGED,

    /** This device deleted the file, the server changed it. */
    MINE_DELETED,

    /** The server deleted the file, this device changed it. */
    SERVER_DELETED,

    /** Both sides added a new file at the same path, with different contents. */
    BOTH_ADDED,
}

/** One file that clashed, with plain words the apps show next to it. */
data class Clash(val path: String, val kind: ClashKind) {
    /** What happened to the file, in a few words. */
    val description: String
        get() = when (kind) {
            ClashKind.BOTH_CHANGED -> "Changed on the server and on your ${Wording.device}."
            ClashKind.MINE_DELETED -> "Deleted on your ${Wording.device}, but changed on the server."
            ClashKind.SERVER_DELETED -> "Deleted on the server, but changed on your ${Wording.device}."
            ClashKind.BOTH_ADDED -> "Added on the server and on your ${Wording.device}, with different contents."
        }

    /** Label for the "Keep mine" button, saying what it does when that means deleting. */
    val keepMineLabel: String
        get() = when (kind) {
            ClashKind.MINE_DELETED -> "Keep mine (delete it)"
            ClashKind.SERVER_DELETED -> "Keep mine (keep the file)"
            else -> "Keep mine"
        }

    /** Label for the "Use server's" button, saying what it does when that means deleting. */
    val useServerLabel: String
        get() = when (kind) {
            ClashKind.SERVER_DELETED -> "Use server's (delete it)"
            ClashKind.MINE_DELETED -> "Use server's (keep the file)"
            else -> "Use server's"
        }
}

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

    /** Title of a sync that stopped on a clash. The apps show the per-file choices when they see it. */
    const val CONFLICT_TITLE = "Conflict, nothing was pulled or pushed"

    /** Which way round the index stages are while git is stopped on a clash. */
    private enum class Op {
        /** git pull --rebase: stage 2 is the server's side, stage 3 is this device's commit. */
        REBASE,

        /** git pull (merge): stage 2 is this device's side, stage 3 is the server's. */
        MERGE,
    }

    /** The person's choices for one sync: one per file, and a fallback for files not listed. */
    private class Plan(val fallback: ConflictChoice, val perFile: Map<String, ConflictChoice>) {
        fun choiceFor(path: String): ConflictChoice = perFile[path] ?: fallback
        fun decides(clashes: List<Clash>): Boolean = clashes.none { choiceFor(it.path) == ConflictChoice.STOP }
    }

    /** What this sync settled, for the summary, plus where this device's replaced copies were saved. */
    private class Settled(val repoFolder: File) {
        val usedServer = linkedSetOf<String>()
        val keptMine = linkedSetOf<String>()
        var backupDir: File? = null

        fun backupDir(): File = backupDir ?: backupFolder(repoFolder).also { backupDir = it }

        /** Forget what was settled, after an undo put everything back. */
        fun undo() {
            usedServer.clear()
            keptMine.clear()
            backupDir?.let { dir ->
                dir.deleteRecursively()
                // Remove the repo's backup folder too if this left it empty.
                dir.parentFile?.takeIf { it.list()?.isEmpty() == true }?.delete()
            }
            backupDir = null
        }

        fun notes(): List<String> {
            val out = mutableListOf<String>()
            if (usedServer.isNotEmpty()) {
                out += "Used the server's copy of:"
                out += usedServer.map { "• $it" }
            }
            if (keptMine.isNotEmpty()) {
                out += "Kept your ${Wording.device}'s copy of:"
                out += keptMine.map { "• $it" }
            }
            backupDir?.let { out += "Your ${Wording.device}'s replaced copies are saved in ${it.path}" }
            return out
        }
    }

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
        /** True when a pull was left half-done (for example by git pull --rebase in Termux). */
        val halfDone: Boolean = false,
        /** Files still waiting for a choice in that half-done pull. */
        val clashes: List<Clash> = emptyList(),
    )

    fun inspect(folder: File): RepoInfo {
        if (!folder.exists()) return RepoInfo(false, null, null, 0, "Folder not found")
        if (!File(folder, ".git").exists()) return RepoInfo(false, null, null, 0, "Not a git repo (no .git folder)")
        return try {
            Git.open(folder).use { git ->
                val repo = git.repository
                val op = openPull(repo)
                if (op != null) {
                    // Mid-pull, HEAD isn't on the branch; show the branch the pull belongs to.
                    return RepoInfo(
                        true, halfDoneBranch(repo) ?: repo.branch, remoteUrl(repo), 0,
                        halfDone = true, clashes = readClashes(repo, op),
                    )
                }
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
     *
     * When files clash, each one is settled by its entry in [choices], or by [onConflict]
     * when it has none. If any clash is left as STOP, the pull is undone so nothing is lost,
     * and the outcome lists every clashing file so the person can choose.
     *
     * A pull that git left half-done (a rebase or merge stopped on a clash, for example
     * from git pull --rebase in Termux) is finished first, with the same choices.
     */
    fun sync(
        folder: File,
        credentials: (host: String) -> HostCredential?,
        fallbackIdentity: Identity,
        commitMessage: String = defaultCommitMessage(),
        onConflict: ConflictChoice = ConflictChoice.STOP,
        choices: Map<String, ConflictChoice> = emptyMap(),
        progress: (String) -> Unit = {},
    ): SyncOutcome {
        val notes = mutableListOf<String>()
        val plan = Plan(onConflict, choices)
        val settled = Settled(folder)
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

                // 0. Finish a pull that git left half-done.
                when (openPull(repo)) {
                    Op.REBASE -> finishOpenRebase(git, plan, settled, notes, progress)?.let { return it }
                    Op.MERGE -> finishOpenMerge(git, plan, settled, fallbackIdentity, notes, progress)?.let { return it }
                    null -> if (repo.repositoryState != RepositoryState.SAFE) {
                        return SyncOutcome(
                            false, "Unfinished git step",
                            listOf(
                                "git is in the middle of something else: ${repo.repositoryState.description}.",
                                "Finish or cancel it in ${Wording.terminal} (git status shows how), then sync again.",
                            ),
                        )
                    }
                }

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
                    val ident = identFor(repo, fallbackIdentity)
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
                        val seen = linkedMapOf<String, Clash>()
                        val result = driveRebase(git, git.rebase().setUpstream(trackingRef).call(), plan, settled, seen)
                        when {
                            rebaseFinished(repo, result) -> notes += "Pulled $incoming commit(s)"
                            result.status == RebaseResult.Status.STOPPED && seen.isNotEmpty() -> {
                                abortRebaseQuietly(git)
                                settled.undo()
                                return clashOutcome(notes, seen.values.toList())
                            }
                            else -> {
                                abortRebaseQuietly(git)
                                settled.undo()
                                val failing = (result.failingPaths?.keys.orEmpty() + result.conflicts.orEmpty())
                                    .distinct().joinToString(", ")
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
                notes += settled.notes()

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

    /**
     * Moves a rebase forward until it finishes, or stops on a clash that has no choice yet.
     * Each local commit that clashes stops the rebase once; its files are settled by [plan]
     * and the rebase goes on. Every clash met is added to [seen].
     */
    private fun driveRebase(
        git: Git,
        start: RebaseResult,
        plan: Plan,
        settled: Settled,
        seen: MutableMap<String, Clash>,
    ): RebaseResult {
        val repo = git.repository
        var result = start
        var rounds = 0
        var continuedWithoutClash = false
        while (rounds++ < 500 && repo.repositoryState.isRebasing) {
            when (result.status) {
                // Taking one side can leave a local commit with nothing in it; drop that empty commit.
                RebaseResult.Status.NOTHING_TO_COMMIT ->
                    result = git.rebase().setOperation(RebaseCommand.Operation.SKIP).call()
                RebaseResult.Status.STOPPED -> {
                    val clashes = readClashes(repo, Op.REBASE)
                    clashes.forEach { seen[it.path] = it }
                    if (clashes.isEmpty()) {
                        // Stopped with nothing left to choose (for example the clash was already
                        // settled in Termux). Commit what is staged and go on, once.
                        if (continuedWithoutClash) return result
                        continuedWithoutClash = true
                    } else {
                        if (!plan.decides(clashes)) return result
                        clashes.forEach { settleClash(git, it, plan.choiceFor(it.path), Op.REBASE, settled) }
                        continuedWithoutClash = false
                    }
                    result = git.rebase().setOperation(RebaseCommand.Operation.CONTINUE).call()
                }
                else -> return result
            }
        }
        return result
    }

    private fun rebaseFinished(repo: Repository, result: RebaseResult): Boolean =
        !repo.repositoryState.isRebasing && result.status in setOf(
            RebaseResult.Status.OK,
            RebaseResult.Status.FAST_FORWARD,
            RebaseResult.Status.UP_TO_DATE,
            RebaseResult.Status.NOTHING_TO_COMMIT,
        )

    /**
     * A rebase was left stopped on a clash (usually git pull --rebase in Termux). If every
     * clashing file has a choice, settle them and finish it here. Returns an outcome to show
     * when the sync can't go on, or null to carry on with the normal sync.
     */
    private fun finishOpenRebase(
        git: Git,
        plan: Plan,
        settled: Settled,
        notes: MutableList<String>,
        progress: (String) -> Unit,
    ): SyncOutcome? {
        val repo = git.repository
        val clashes = readClashes(repo, Op.REBASE)
        if (!plan.decides(clashes)) {
            return clashOutcome(
                notes + "A pull was left half-done (for example by git pull --rebase in ${Wording.terminal}).",
                clashes,
            )
        }
        progress("Finishing the half-done pull…")
        val seen = linkedMapOf<String, Clash>()
        try {
            clashes.forEach {
                seen[it.path] = it
                settleClash(git, it, plan.choiceFor(it.path), Op.REBASE, settled)
            }
            val result = driveRebase(
                git, git.rebase().setOperation(RebaseCommand.Operation.CONTINUE).call(), plan, settled, seen,
            )
            if (rebaseFinished(repo, result)) {
                notes += "Finished a pull that was left half-done"
                return null
            }
            // A later commit clashed on a file with no choice yet: put everything back and ask.
            abortRebaseQuietly(git)
            settled.undo()
            if (result.status == RebaseResult.Status.STOPPED && seen.isNotEmpty()) {
                return clashOutcome(notes, seen.values.toList())
            }
            notes += "Restarted a pull that was left half-done"
            return null
        } catch (e: Exception) {
            // JGit couldn't carry on with git's own rebase. Undo it (your commits stay) and
            // let the normal sync redo the pull with the same choices.
            abortRebaseQuietly(git)
            settled.undo()
            if (repo.repositoryState.isRebasing) {
                return SyncOutcome(
                    false, "Couldn't finish the half-done pull",
                    notes + listOf(friendly(e), "In ${Wording.terminal}, run git rebase --abort, then sync again."),
                )
            }
            notes += "Restarted a pull that was left half-done"
            return null
        }
    }

    /**
     * A merge was left stopped on a clash (usually a plain git pull). If every clashing file
     * has a choice, settle them and make the merge commit. Returns an outcome to show when
     * the sync can't go on, or null to carry on with the normal sync.
     */
    private fun finishOpenMerge(
        git: Git,
        plan: Plan,
        settled: Settled,
        fallbackIdentity: Identity,
        notes: MutableList<String>,
        progress: (String) -> Unit,
    ): SyncOutcome? {
        val repo = git.repository
        val clashes = readClashes(repo, Op.MERGE)
        if (!plan.decides(clashes)) {
            return clashOutcome(notes + "A pull was left half-done (for example by git pull in ${Wording.terminal}).", clashes)
        }
        progress("Finishing the half-done pull…")
        clashes.forEach { settleClash(git, it, plan.choiceFor(it.path), Op.MERGE, settled) }
        val message = repo.readMergeCommitMsg()
            ?.lines()?.filterNot { it.startsWith("#") }?.joinToString("\n")?.trim()
            ?.takeIf { it.isNotEmpty() } ?: "Merge from server"
        val ident = identFor(repo, fallbackIdentity)
        git.commit().setMessage(message).setAuthor(ident).setCommitter(ident).call()
        notes += "Finished a pull that was left half-done"
        return null
    }

    private fun clashOutcome(notes: List<String>, clashes: List<Clash>): SyncOutcome =
        SyncOutcome(
            false, CONFLICT_TITLE,
            notes + listOf("These files clashed between the server and your ${Wording.device}:") +
                clashes.map { "• ${it.path}: ${it.description}" } + listOf(
                    "Your work is safe: nothing was overwritten or deleted.",
                    "${Wording.tap} the repo, choose Keep mine or Use server's for each file, then Finish sync.",
                ),
            clashes,
        )

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

    private fun identFor(repo: Repository, fallback: Identity): PersonIdent {
        val name = repo.config.getString("user", null, "name")
        val email = repo.config.getString("user", null, "email")
        return PersonIdent(
            name?.takeIf { it.isNotBlank() } ?: fallback.name,
            email?.takeIf { it.isNotBlank() } ?: fallback.email,
        )
    }

    /** Which kind of pull git left half-done in this repo, if any. */
    private fun openPull(repo: Repository): Op? = when {
        repo.repositoryState.isRebasing -> Op.REBASE
        repo.repositoryState == RepositoryState.MERGING ||
            repo.repositoryState == RepositoryState.MERGING_RESOLVED -> Op.MERGE
        else -> null
    }

    /** The branch a half-done rebase belongs to ("main"), read from git's own notes. */
    private fun halfDoneBranch(repo: Repository): String? {
        for (dir in listOf("rebase-merge", "rebase-apply")) {
            val f = File(repo.directory, "$dir/head-name")
            if (f.isFile) return Repository.shortenRefName(f.readText().trim()).takeIf { it.isNotEmpty() }
        }
        return null
    }

    /** Every file the index still has a clash for, and how it clashed. */
    private fun readClashes(repo: Repository, op: Op): List<Clash> {
        val dc = repo.readDirCache()
        val stages = linkedMapOf<String, MutableSet<Int>>()
        for (i in 0 until dc.entryCount) {
            val e = dc.getEntry(i)
            if (e.stage != DirCacheEntry.STAGE_0) stages.getOrPut(e.pathString) { mutableSetOf() }.add(e.stage)
        }
        val serverStage = if (op == Op.REBASE) DirCacheEntry.STAGE_2 else DirCacheEntry.STAGE_3
        val mineStage = if (op == Op.REBASE) DirCacheEntry.STAGE_3 else DirCacheEntry.STAGE_2
        return stages.map { (path, s) ->
            val base = DirCacheEntry.STAGE_1 in s
            val server = serverStage in s
            val mine = mineStage in s
            val kind = when {
                !base && server && mine -> ClashKind.BOTH_ADDED
                base && server && !mine -> ClashKind.MINE_DELETED
                base && !server && mine -> ClashKind.SERVER_DELETED
                else -> ClashKind.BOTH_CHANGED
            }
            Clash(path, kind)
        }
    }

    /**
     * Settles one clashing file: writes the chosen side's copy (or deletes the file when
     * the chosen side deleted it) and marks it settled. When the server's side wins, this
     * device's copy is saved to the backup folder first.
     */
    private fun settleClash(git: Git, clash: Clash, choice: ConflictChoice, op: Op, settled: Settled) {
        val repo = git.repository
        val path = clash.path
        val dc = repo.readDirCache()
        val serverStage = if (op == Op.REBASE) DirCacheEntry.STAGE_2 else DirCacheEntry.STAGE_3
        val mineStage = if (op == Op.REBASE) DirCacheEntry.STAGE_3 else DirCacheEntry.STAGE_2
        var server: DirCacheEntry? = null
        var mine: DirCacheEntry? = null
        var i = dc.findEntry(path)
        while (i in 0 until dc.entryCount && dc.getEntry(i).pathString == path) {
            val e = dc.getEntry(i)
            when (e.stage) {
                serverStage -> server = e
                mineStage -> mine = e
            }
            i++
        }
        if (choice == ConflictChoice.USE_SERVER && mine != null) {
            val copy = File(settled.backupDir(), path)
            copy.parentFile?.mkdirs()
            FileOutputStream(copy).use { repo.open(mine.objectId).copyTo(it) }
        }
        val keep = if (choice == ConflictChoice.USE_SERVER) server else mine
        val target = File(repo.workTree, path)
        if (keep == null) {
            // The chosen side deleted the file.
            target.delete()
            git.rm().addFilepattern(path).call()
        } else {
            target.parentFile?.mkdirs()
            FileOutputStream(target).use { repo.open(keep.objectId).copyTo(it) }
            git.add().addFilepattern(path).call()
        }
        if (choice == ConflictChoice.USE_SERVER) settled.usedServer += path else settled.keptMine += path
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
