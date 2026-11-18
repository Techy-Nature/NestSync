package io.github.techynature.nestsync

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.awt.FileDialog
import java.awt.Frame
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.URIish
import java.io.File
import java.nio.file.Files
import javax.swing.JFileChooser
import kotlin.system.exitProcess

// ---------- Colours (always dark, same as the Android app) ----------

private val Bg = Color(0xFF12101A)
private val Surface = Color(0xFF1D1A28)
private val SurfaceHigh = Color(0xFF282338)
private val Accent = Color(0xFF9D7CFF)
private val OnAccent = Color(0xFF14102A)
private val TextMain = Color(0xFFECE8F6)
private val TextDim = Color(0xFFA59FB8)
private val Good = Color(0xFF6FD6A0)
private val Bad = Color(0xFFFF7B8A)
private val Warn = Color(0xFFF2C46D)

private val NestColors = darkColorScheme(
    primary = Accent,
    onPrimary = OnAccent,
    secondary = Accent,
    background = Bg,
    onBackground = TextMain,
    surface = Surface,
    onSurface = TextMain,
    surfaceVariant = SurfaceHigh,
    onSurfaceVariant = TextDim,
    surfaceContainerHigh = SurfaceHigh,
    error = Bad,
)

/** Set by the build (see desktop/build.gradle.kts). */
private val appVersion: String = System.getProperty("nestsync.version").orEmpty()

fun main(args: Array<String>) {
    // The sync engine's messages talk about a computer here, not a phone.
    Wording.device = "computer"
    Wording.terminal = "a terminal"
    Wording.tap = "Click"
    Wording.folderBlocked =
        "Your computer blocked access to the folder. Check that your account can read and write it" +
        (if (AppDirs.isMac) ", and on a Mac that NestSync is allowed under System Settings → Privacy & Security → Files and Folders." else ".")

    // Read ~/.gitconfig as usual, but never try to sign commits (JGit can't sign with SSH keys).
    CommitSigning.install()

    if ("--self-test" in args) exitProcess(selfTest())

    if (AppDirs.isMac) {
        // Lets the Mac's own folder picker choose folders instead of files.
        System.setProperty("apple.awt.fileDialogForDirectories", "true")
    }

    val state = AppState()
    application {
        val icon = remember {
            Thread.currentThread().contextClassLoader.getResourceAsStream("nestsync-icon.png")
                ?.use { BitmapPainter(loadImageBitmap(it)) }
        }
        Window(
            onCloseRequest = ::exitApplication,
            title = "NestSync",
            icon = icon,
            state = rememberWindowState(size = DpSize(560.dp, 800.dp)),
        ) {
            MaterialTheme(colorScheme = NestColors) {
                App(state, pickFolder = { start -> pickFolder(window, start) })
            }
        }
    }
}

/**
 * Run by the build workflow on the finished AppImage / Mac app, without opening a window:
 * a real sync between two local copies, then an HTTPS check against GitHub. If the bundled
 * Java is missing a piece (for example HTTPS support), this fails the build instead of
 * failing on someone's computer.
 */
private fun selfTest(): Int {
    println("NestSync $appVersion self-test")
    val tmp = Files.createTempDirectory("nestsync-selftest").toFile()
    try {
        val server = File(tmp, "server.git")
        Git.init().setBare(true).setDirectory(server).setInitialBranch("main").call().close()
        val copy = File(tmp, "copy")
        Git.init().setDirectory(copy).setInitialBranch("main").call().use { g ->
            g.remoteAdd().setName("origin").setUri(URIish(server.toURI().toString())).call()
        }
        File(copy, "story.tw").writeText("hello\n")
        val sync = GitEngine.sync(copy, { null }, Identity("NestSync self-test", "selftest@localhost"))
        println("  local sync: ${sync.title} ${sync.details}")

        val https = GitEngine.testConnection("https://github.com/Techy-Nature/NestSync.git", null)
        println("  https: ${https.title} ${https.details}")

        return if (sync.ok && https.ok) 0 else 1
    } finally {
        tmp.deleteRecursively()
    }
}

/** Opens the system folder picker. Returns the chosen path, or null if cancelled. */
private fun pickFolder(owner: Frame, start: String): String? {
    val startDir = File(AppState.expandHome(start)).let { if (it.isDirectory) it else AppDirs.home }
    return if (AppDirs.isMac) {
        val dialog = FileDialog(owner, "Choose a repo folder", FileDialog.LOAD)
        dialog.directory = startDir.path
        dialog.isVisible = true
        val dir = dialog.directory
        val name = dialog.file
        if (dir == null || name == null) null else File(dir, name).path
    } else {
        val chooser = JFileChooser(startDir).apply {
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            dialogTitle = "Choose a repo folder"
            isAcceptAllFileFilterUsed = false
        }
        if (chooser.showOpenDialog(owner) == JFileChooser.APPROVE_OPTION) chooser.selectedFile.path else null
    }
}

/** "just now", "5 minutes ago", "yesterday"… */
private fun ago(time: Long): String {
    val mins = (System.currentTimeMillis() - time) / 60_000
    return when {
        mins < 1 -> "just now"
        mins < 60 -> "$mins min ago"
        mins < 60 * 24 -> "${mins / 60} h ago"
        mins < 60 * 48 -> "yesterday"
        else -> "${mins / (60 * 24)} days ago"
    }
}

private enum class Screen { Repos, Settings }

@Composable
private fun App(state: AppState, pickFolder: (String) -> String?) {
    var screen by remember { mutableStateOf(Screen.Repos) }

    Box(Modifier.fillMaxSize()) {
        when (screen) {
            Screen.Repos -> ReposScreen(state, pickFolder, openSettings = { screen = Screen.Settings })
            Screen.Settings -> SettingsScreen(state, back = { screen = Screen.Repos })
        }
    }

    state.banner?.let { outcome ->
        ResultDialog(outcome, onDismiss = { state.banner = null })
    }
}

/** Keeps cards a comfortable width when the window is wide. */
private val ContentWidth = Modifier.widthIn(max = 720.dp)

// =====================================================================
// Repo list
// =====================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReposScreen(state: AppState, pickFolder: (String) -> String?, openSettings: () -> Unit) {
    var showAdd by remember { mutableStateOf(false) }
    var showClone by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<RepoEntry?>(null) }

    Scaffold(
        containerColor = Bg,
        topBar = {
            TopAppBar(
                title = { Text("NestSync", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg, titleContentColor = TextMain),
                actions = {
                    IconButton(onClick = { state.refreshAll() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh", tint = TextDim)
                    }
                    IconButton(onClick = openSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = TextDim)
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAdd = true },
                containerColor = Accent,
                contentColor = OnAccent,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Add repo") },
            )
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = ContentWidth.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.hosts.isEmpty()) {
                    item {
                        NoticeCard(
                            title = "No login saved yet",
                            body = "Add a token for Git.gay or GitHub in Settings so NestSync can push.",
                            button = "Open Settings",
                            onClick = openSettings,
                        )
                    }
                }
                state.busy["clone"]?.let { msg ->
                    item { NoticeCard(title = msg, body = "This can take a minute for big repos.", busy = true) }
                }
                if (state.repos.isEmpty()) {
                    item {
                        Text(
                            "No repos yet. Click \"Add repo\" to pick a story folder or clone one.",
                            color = TextDim,
                            modifier = Modifier.padding(vertical = 24.dp),
                        )
                    }
                } else {
                    if (state.repos.size > 1) {
                        item {
                            OutlinedButton(onClick = { state.syncAll() }, modifier = Modifier.fillMaxWidth()) {
                                Text("Sync all ${state.repos.size} repos")
                            }
                        }
                    }
                    items(state.repos, key = { it.id }) { repo ->
                        RepoCard(
                            repo = repo,
                            info = state.info[repo.id],
                            busyText = state.busy[repo.id],
                            onSync = { state.sync(repo) },
                            onOpen = { selected = repo },
                        )
                    }
                }
            }
        }
    }

    if (showAdd) {
        AddRepoDialog(
            pickFolder = pickFolder,
            onDismiss = { showAdd = false },
            onAdd = { path -> state.addRepo(path) },
            onCloneInstead = { showAdd = false; showClone = true },
        )
    }
    if (showClone) {
        CloneDialog(state, pickFolder, onDismiss = { showClone = false })
    }
    selected?.let { repo ->
        RepoDetailDialog(state, repo, onDismiss = { selected = null })
    }
}

@Composable
private fun NoticeCard(
    title: String,
    body: String,
    button: String? = null,
    onClick: () -> Unit = {},
    color: Color = Accent,
    busy: Boolean = false,
) {
    Card(colors = CardDefaults.cardColors(containerColor = SurfaceHigh)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = color)
                    Spacer(Modifier.width(10.dp))
                }
                Text(title, color = color, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(4.dp))
            Text(body, color = TextDim, fontSize = 14.sp)
            if (button != null) {
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = onClick,
                    colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = OnAccent),
                ) { Text(button) }
            }
        }
    }
}

@Composable
private fun RepoCard(
    repo: RepoEntry,
    info: GitEngine.RepoInfo?,
    busyText: String?,
    onSync: () -> Unit,
    onOpen: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Surface),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(repo.name, color = TextMain, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(repo.path, color = TextDim, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(6.dp))

            val summary = when {
                info == null -> "Checking…"
                !info.isRepo -> info.error ?: "Not a git repo"
                else -> buildString {
                    append(info.branch ?: "no branch")
                    append(" · ")
                    append(if (info.changedFiles == 0) "no local changes" else "${info.changedFiles} changed file(s)")
                    GitEngine.hostOf(info.remoteUrl)?.let { append(" · ").append(it) }
                }
            }
            Text(summary, color = if (info?.isRepo == false) Bad else TextDim, fontSize = 14.sp)
            if (info != null && info.unpushed > 0) {
                Text(
                    "${info.unpushed} commit(s) waiting to upload",
                    color = Warn, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                )
            }

            if (repo.lastTitle != null && busyText == null) {
                Spacer(Modifier.height(6.dp))
                val when_ = if (repo.lastTime > 0) " · " + ago(repo.lastTime) else ""
                Text(
                    (if (repo.lastOk == true) "✓ " else "✗ ") + repo.lastTitle + when_,
                    color = if (repo.lastOk == true) Good else Bad,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
                if (repo.lastOk != true) {
                    repo.lastDetails.lastOrNull()?.let { Text(it, color = TextDim, fontSize = 13.sp) }
                    Text("Click the card for full details.", color = TextDim, fontSize = 12.sp)
                }
            }

            Spacer(Modifier.height(12.dp))
            if (busyText != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Accent)
                    Spacer(Modifier.width(12.dp))
                    Text(busyText, color = TextMain)
                }
            } else {
                Button(
                    onClick = onSync,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = OnAccent),
                ) { Text("Sync", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

// =====================================================================
// Dialogs
// =====================================================================

@Composable
private fun AddRepoDialog(
    pickFolder: (String) -> String?,
    onDismiss: () -> Unit,
    onAdd: (String) -> String?,
    onCloneInstead: () -> Unit,
) {
    var path by remember { mutableStateOf("~/Stories/") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface,
        title = { Text("Add a repo folder") },
        text = {
            Column {
                Text(
                    "Pick the folder that has your story's .git inside, or type its path.",
                    color = TextDim, fontSize = 14.sp,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { pickFolder(path)?.let { path = it; error = null } },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Browse for folder…") }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = path,
                    onValueChange = { path = it; error = null },
                    label = { Text("Folder path") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, color = Bad, fontSize = 13.sp)
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onCloneInstead) { Text("Repo isn't on this computer yet? Clone it instead") }
            }
        },
        confirmButton = {
            Button(onClick = {
                val err = onAdd(path)
                if (err == null) onDismiss() else error = err
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun CloneDialog(state: AppState, pickFolder: (String) -> String?, onDismiss: () -> Unit) {
    var url by remember { mutableStateOf("https://") }
    var name by remember { mutableStateOf("") }
    var parent by remember { mutableStateOf(state.settings.cloneFolder) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface,
        title = { Text("Clone a repo") },
        text = {
            Column {
                OutlinedTextField(
                    value = url, onValueChange = { url = it },
                    label = { Text("HTTPS link") },
                    placeholder = { Text("https://git.gay/you/story.git") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = parent, onValueChange = { parent = it },
                    label = { Text("Save inside this folder") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = { pickFolder(parent)?.let { parent = it } }) { Text("Browse…") }
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("New folder name (optional)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Private repos need a token for that host first (Settings).",
                    color = TextDim, fontSize = 13.sp,
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                state.settings.cloneFolder = parent.trim().trimEnd('/')
                state.clone(url, name)
                onDismiss()
            }) { Text("Clone") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun RepoDetailDialog(state: AppState, repo: RepoEntry, onDismiss: () -> Unit) {
    val current = state.repos.firstOrNull { it.id == repo.id } ?: repo
    val info = state.info[repo.id]
    var editingRemote by remember { mutableStateOf(false) }
    var remote by remember { mutableStateOf(info?.remoteUrl ?: "") }
    var confirmRemove by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface,
        title = { Text(current.name) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Label("Folder")
                Mono(current.path)
                Label("Remote")
                Mono(info?.remoteUrl ?: "(none)")
                if (GitEngine.isSsh(info?.remoteUrl)) {
                    Text(
                        "This remote uses SSH. NestSync uses HTTPS + token instead.",
                        color = Warn, fontSize = 13.sp,
                    )
                    TextButton(onClick = {
                        state.setRemote(current, GitEngine.sshToHttps(info?.remoteUrl ?: ""))
                    }) { Text("Switch remote to HTTPS") }
                }

                if (current.lastTitle != null) {
                    Label("Last sync")
                    Text(
                        current.lastTitle,
                        color = if (current.lastOk == true) Good else Bad,
                        fontWeight = FontWeight.SemiBold,
                    )
                    current.lastDetails.forEach { Text(it, color = TextMain, fontSize = 14.sp) }
                }

                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = SurfaceHigh)
                Spacer(Modifier.height(4.dp))

                TextButton(onClick = { state.testConnection(current); onDismiss() }) { Text("Test connection") }
                TextButton(onClick = { editingRemote = !editingRemote }) { Text("Change remote URL") }
                if (editingRemote) {
                    OutlinedTextField(
                        value = remote, onValueChange = { remote = it },
                        label = { Text("HTTPS remote URL") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(onClick = { state.setRemote(current, remote); editingRemote = false }) { Text("Save remote") }
                }
                if (!confirmRemove) {
                    TextButton(onClick = { confirmRemove = true }) { Text("Remove from list", color = Bad) }
                } else {
                    Text("Only removes it from NestSync. Your files stay where they are.", color = TextDim, fontSize = 13.sp)
                    TextButton(onClick = { state.removeRepo(current); onDismiss() }) { Text("Yes, remove", color = Bad) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun ResultDialog(outcome: SyncOutcome, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface,
        title = { Text(outcome.title, color = if (outcome.ok) Good else Bad) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                outcome.details.forEach { Text(it, color = TextMain, fontSize = 14.sp) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}

@Composable
private fun Label(text: String) {
    Spacer(Modifier.height(10.dp))
    Text(text.uppercase(), color = TextDim, fontSize = 11.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun Mono(text: String) {
    Text(text, color = TextMain, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
}

// =====================================================================
// Settings
// =====================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(state: AppState, back: () -> Unit) {
    var editing by remember { mutableStateOf<HostCredential?>(null) }
    var adding by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf(state.settings.identity.name) }
    var email by remember { mutableStateOf(state.settings.identity.email) }
    var savedIdentity by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = Bg,
        modifier = Modifier.onEscape(back),
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = back) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextMain)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg, titleContentColor = TextMain),
            )
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.TopCenter) {
            Column(
                ContentWidth.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            ) {
                Text("Hosts and tokens", color = TextMain, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text(
                    "One login per server. Every repo on that server uses it.",
                    color = TextDim, fontSize = 14.sp,
                )
                Spacer(Modifier.height(10.dp))
                state.hosts.forEach { h ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Surface),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).clickable { editing = h },
                    ) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(h.host, color = TextMain, fontWeight = FontWeight.SemiBold)
                                Text("${h.username} · token ••••${h.token.takeLast(4)}", color = TextDim, fontSize = 13.sp)
                            }
                            IconButton(onClick = { state.removeHost(h.host) }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = Bad)
                            }
                        }
                    }
                }
                OutlinedButton(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) { Text("Add a host") }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Tokens are saved in ${AppDirs.config.path}, readable only by your account.",
                    color = TextDim, fontSize = 12.sp,
                )

                Spacer(Modifier.height(28.dp))
                Text("Commit name", color = TextMain, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text(
                    "Used only when a repo doesn't already have user.name / user.email set.",
                    color = TextDim, fontSize = 14.sp,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = name, onValueChange = { name = it; savedIdentity = false },
                    label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = email, onValueChange = { email = it; savedIdentity = false },
                    label = { Text("Email") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = {
                    state.settings.identity = Identity(name.trim(), email.trim())
                    savedIdentity = true
                }) { Text(if (savedIdentity) "Saved ✓" else "Save") }

                Spacer(Modifier.height(28.dp))
                Text("NestSync $appVersion", color = TextDim, fontSize = 12.sp)
            }
        }
    }

    if (adding || editing != null) {
        HostDialog(
            existing = editing,
            onDismiss = { adding = false; editing = null },
            onSave = { state.saveHost(it); adding = false; editing = null },
        )
    }
}

/** Escape goes back, like the Back button on Android. */
private fun Modifier.onEscape(action: () -> Unit): Modifier =
    onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyUp && event.key == Key.Escape) {
            action(); true
        } else {
            false
        }
    }

@Composable
private fun HostDialog(existing: HostCredential?, onDismiss: () -> Unit, onSave: (HostCredential) -> Unit) {
    var host by remember { mutableStateOf(existing?.host ?: "") }
    var user by remember { mutableStateOf(existing?.username ?: "") }
    var token by remember { mutableStateOf(existing?.token ?: "") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface,
        title = { Text(if (existing == null) "Add a host" else "Edit ${existing.host}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { host = "git.gay" }) { Text("git.gay") }
                    OutlinedButton(onClick = { host = "github.com" }) { Text("github.com") }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = host, onValueChange = { host = it; error = null },
                    label = { Text("Host (no https://)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = user, onValueChange = { user = it; error = null },
                    label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = token, onValueChange = { token = it; error = null },
                    label = { Text("Access token") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    when {
                        host.contains("github") ->
                            "GitHub: Settings → Developer settings → Personal access tokens → Fine-grained. " +
                                "Give it your repos with \"Contents: Read and write\"."
                        host.isNotBlank() ->
                            "Forgejo / Gitea (like git.gay): Settings → Applications → Generate new token. " +
                                "Set \"repository\" to Read and write."
                        else -> "Click a quick-fill button or type your server's name."
                    },
                    color = TextDim, fontSize = 13.sp,
                )
                error?.let { Text(it, color = Bad, fontSize = 13.sp) }
            }
        },
        confirmButton = {
            Button(onClick = {
                val h = host.trim().removePrefix("https://").removePrefix("http://").trimEnd('/').lowercase()
                when {
                    h.isEmpty() || h.contains('/') -> error = "Host should look like git.gay or github.com"
                    user.isBlank() -> error = "Enter your username"
                    token.isBlank() -> error = "Paste your token"
                    else -> onSave(HostCredential(h, user.trim(), token.trim()))
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
