package io.github.techynature.nestsync

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Holds the app's state and runs all git work off the UI thread. Same jobs as the Android MainViewModel. */
class AppState {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val repoStore = RepoStore()
    val credentialStore = CredentialStore()
    val settings = AppSettings()

    var repos by mutableStateOf(repoStore.load())
        private set
    var hosts by mutableStateOf(credentialStore.all())
        private set

    /** repo id -> progress text while that repo is syncing. */
    val busy = mutableStateMapOf<String, String>()

    /** repo id -> quick offline info (branch, changed files). */
    val info = mutableStateMapOf<String, GitEngine.RepoInfo>()

    /** A message shown in a dialog (clone results etc). */
    var banner by mutableStateOf<SyncOutcome?>(null)

    init {
        refreshAll()
    }

    // ---------- repos ----------

    fun refreshAll() {
        repos.forEach { refresh(it) }
    }

    fun refresh(repo: RepoEntry) {
        scope.launch {
            val i = withContext(Dispatchers.IO) { GitEngine.inspect(File(repo.path)) }
            info[repo.id] = i
        }
    }

    /** Adds an existing folder. Returns an error message, or null when it worked. */
    fun addRepo(path: String): String? {
        val clean = expandHome(path.trim()).trimEnd('/')
        if (clean.isEmpty()) return "Enter a folder path."
        if (repos.any { it.path == clean }) return "That folder is already in the list."
        val folder = File(clean)
        if (!folder.isDirectory) return "Can't find that folder."
        if (!File(folder, ".git").exists()) return "That folder isn't a git repo (no .git inside)."
        val entry = RepoEntry(name = folder.name, path = clean)
        updateRepos(repos + entry)
        refresh(entry)
        return null
    }

    fun removeRepo(repo: RepoEntry) {
        updateRepos(repos.filterNot { it.id == repo.id })
        info.remove(repo.id)
    }

    fun setRemote(repo: RepoEntry, url: String) {
        scope.launch {
            val err = withContext(Dispatchers.IO) {
                try {
                    GitEngine.setRemoteUrl(File(repo.path), url); null
                } catch (e: Exception) {
                    GitEngine.friendly(e)
                }
            }
            banner = if (err == null) SyncOutcome(true, "Remote updated", listOf(url)) else SyncOutcome(false, "Couldn't change remote", listOf(err))
            refresh(repo)
        }
    }

    fun testConnection(repo: RepoEntry) {
        val url = info[repo.id]?.remoteUrl ?: run {
            banner = SyncOutcome(false, "No remote", listOf("This repo has no 'origin' remote."))
            return
        }
        busy[repo.id] = "Testing connection…"
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                GitEngine.testConnection(url, GitEngine.hostOf(url)?.let { credentialStore.forHost(it) })
            }
            busy.remove(repo.id)
            banner = result
        }
    }

    /**
     * Syncs one repo. [onConflict] settles every clashing file one way; [choices] settles
     * listed files one by one (it wins for the files it names).
     */
    fun sync(
        repo: RepoEntry,
        onConflict: ConflictChoice = ConflictChoice.STOP,
        choices: Map<String, ConflictChoice> = emptyMap(),
    ) {
        if (busy.containsKey(repo.id)) return
        busy[repo.id] = "Starting…"
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                GitEngine.sync(
                    folder = File(repo.path),
                    credentials = { host -> credentialStore.forHost(host) },
                    fallbackIdentity = settings.identity,
                    onConflict = onConflict,
                    choices = choices,
                    progress = { msg -> scope.launch { if (busy.containsKey(repo.id)) busy[repo.id] = msg } },
                )
            }
            busy.remove(repo.id)
            val updated = repo.copy(
                lastOk = result.ok,
                lastTitle = result.title,
                lastDetails = result.details,
                lastTime = System.currentTimeMillis(),
                lastClashes = result.clashes,
            )
            updateRepos(repos.map { if (it.id == repo.id) updated else it })
            refresh(updated)
        }
    }

    fun syncAll() = repos.forEach { sync(it) }

    fun clone(url: String, folderName: String) {
        val parent = File(expandHome(settings.cloneFolder))
        val name = folderName.trim().ifEmpty { guessName(url) }
        val dest = File(parent, name)
        val key = "clone"
        busy[key] = "Cloning $name…"
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                parent.mkdirs()
                GitEngine.clone(url, dest, { host -> credentialStore.forHost(host) })
            }
            busy.remove(key)
            banner = result
            if (result.ok) {
                val entry = RepoEntry(name = dest.name, path = dest.path)
                updateRepos(repos + entry)
                refresh(entry)
            }
        }
    }

    private fun guessName(url: String): String =
        url.trim().trimEnd('/').substringAfterLast('/').removeSuffix(".git").ifEmpty { "repo" }

    private fun updateRepos(list: List<RepoEntry>) {
        repos = list
        repoStore.save(list)
    }

    // ---------- hosts ----------

    fun saveHost(cred: HostCredential) {
        credentialStore.put(cred)
        hosts = credentialStore.all()
    }

    fun removeHost(host: String) {
        credentialStore.remove(host)
        hosts = credentialStore.all()
    }

    companion object {
        /** Lets people type ~/Stories like they would in a terminal. */
        fun expandHome(path: String): String = when {
            path == "~" -> AppDirs.home.path
            path.startsWith("~/") -> File(AppDirs.home, path.removePrefix("~/")).path
            else -> path
        }
    }
}
