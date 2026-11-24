package io.github.techynature.nestsync

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Holds the app's state and runs all git work off the main thread. */
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val repoStore = RepoStore(app)
    val credentialStore = CredentialStore(app)
    val settings = AppSettings(app)

    var repos by mutableStateOf(repoStore.load())
        private set
    var hosts by mutableStateOf(credentialStore.all())
        private set

    /** repo id -> progress text while that repo is syncing. */
    val busy = mutableStateMapOf<String, String>()

    /** repo id -> quick offline info (branch, changed files). */
    val info = mutableStateMapOf<String, GitEngine.RepoInfo>()

    /** A message shown at the bottom of the screen (clone results etc). */
    var banner by mutableStateOf<SyncOutcome?>(null)

    init {
        refreshAll()
    }

    // ---------- repos ----------

    fun refreshAll() {
        repos.forEach { refresh(it) }
    }

    fun refresh(repo: RepoEntry) {
        viewModelScope.launch {
            val i = withContext(Dispatchers.IO) { GitEngine.inspect(File(repo.path)) }
            info[repo.id] = i
        }
    }

    /** Adds an existing folder. Returns an error message, or null when it worked. */
    fun addRepo(path: String): String? {
        val clean = path.trim().trimEnd('/')
        if (clean.isEmpty()) return "Enter a folder path."
        if (repos.any { it.path == clean }) return "That folder is already in the list."
        val folder = File(clean)
        if (!folder.isDirectory) return "Can't find that folder. If it exists, give NestSync 'All files access'."
        if (!File(folder, ".git").exists()) return "That folder isn't a git repo (no .git inside)."
        GitEngine.relaxFileModes(folder)
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
        viewModelScope.launch {
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
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                GitEngine.testConnection(url, GitEngine.hostOf(url)?.let { credentialStore.forHost(it) })
            }
            busy.remove(repo.id)
            banner = result
        }
    }

    fun sync(repo: RepoEntry, onConflict: ConflictChoice = ConflictChoice.STOP) {
        if (busy.containsKey(repo.id)) return
        busy[repo.id] = "Starting…"
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                GitEngine.sync(
                    folder = File(repo.path),
                    credentials = { host -> credentialStore.forHost(host) },
                    fallbackIdentity = settings.identity,
                    onConflict = onConflict,
                    progress = { msg -> viewModelScope.launch(Dispatchers.Main) { if (busy.containsKey(repo.id)) busy[repo.id] = msg } },
                )
            }
            busy.remove(repo.id)
            val updated = repo.copy(
                lastOk = result.ok,
                lastTitle = result.title,
                lastDetails = result.details,
                lastTime = System.currentTimeMillis(),
            )
            updateRepos(repos.map { if (it.id == repo.id) updated else it })
            refresh(updated)
        }
    }

    fun syncAll() = repos.forEach { sync(it) }

    fun clone(url: String, folderName: String) {
        val parent = File(settings.cloneFolder)
        val name = folderName.trim().ifEmpty { guessName(url) }
        val dest = File(parent, name)
        val key = "clone"
        busy[key] = "Cloning $name…"
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                parent.mkdirs()
                GitEngine.clone(url, dest, { host -> credentialStore.forHost(host) })
            }
            busy.remove(key)
            banner = result
            if (result.ok) {
                GitEngine.relaxFileModes(dest)
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
}
