package io.github.techynature.nestsync

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import android.text.format.DateUtils
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel

// ---------- Colours (always dark) ----------

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

/** Version text shown in Settings, filled in when the app starts. */
object BuildInfo {
    var version: String = ""
}

class MainActivity : ComponentActivity() {
    private val storageOk = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        BuildInfo.version = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (e: Exception) {
            ""
        }
        setContent {
            MaterialTheme(colorScheme = NestColors) {
                App(storageOk = storageOk.value, askForStorage = { askForStorage() })
            }
        }
    }

    override fun onResume() {
        super.onResume()
        storageOk.value = hasStorageAccess(this)
    }

    private fun askForStorage() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")),
                )
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
        }
    }

    // No permission-result callback needed: the permission prompt pauses the
    // activity, and onResume() re-checks access when it closes.
}

fun hasStorageAccess(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
    }

/** Turns a folder picked in Android's folder picker into a normal file path. */
fun treeUriToPath(uri: Uri): String? {
    val docId = try {
        DocumentsContract.getTreeDocumentId(uri)
    } catch (e: Exception) {
        return null
    }
    val parts = docId.split(":", limit = 2)
    val volume = parts[0]
    val rel = parts.getOrElse(1) { "" }
    val base = if (volume.equals("primary", ignoreCase = true)) {
        Environment.getExternalStorageDirectory().path
    } else {
        "/storage/$volume"
    }
    return if (rel.isEmpty()) base else "$base/$rel"
}

private enum class Screen { Repos, Settings }

@Composable
private fun App(storageOk: Boolean, askForStorage: () -> Unit) {
    val vm: MainViewModel = viewModel()
    var screen by remember { mutableStateOf(Screen.Repos) }

    when (screen) {
        Screen.Repos -> ReposScreen(vm, storageOk, askForStorage, openSettings = { screen = Screen.Settings })
        Screen.Settings -> {
            BackHandler { screen = Screen.Repos }
            SettingsScreen(vm, back = { screen = Screen.Repos })
        }
    }

    vm.banner?.let { outcome ->
        ResultDialog(outcome, onDismiss = { vm.banner = null })
    }
}

// =====================================================================
// Repo list
// =====================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReposScreen(vm: MainViewModel, storageOk: Boolean, askForStorage: () -> Unit, openSettings: () -> Unit) {
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
                    IconButton(onClick = { vm.refreshAll() }) {
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
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!storageOk) {
                item { StorageBanner(askForStorage) }
            }
            if (vm.hosts.isEmpty()) {
                item {
                    NoticeCard(
                        title = "No login saved yet",
                        body = "Add a token for Git.gay or GitHub in Settings so NestSync can push.",
                        button = "Open Settings",
                        onClick = openSettings,
                    )
                }
            }
            vm.busy["clone"]?.let { msg ->
                item { NoticeCard(title = msg, body = "This can take a minute for big repos.", busy = true) }
            }
            if (vm.repos.isEmpty()) {
                item {
                    Text(
                        "No repos yet. Tap \"Add repo\" to pick a story folder or clone one.",
                        color = TextDim,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
            } else {
                if (vm.repos.size > 1) {
                    item {
                        OutlinedButton(onClick = { vm.syncAll() }, modifier = Modifier.fillMaxWidth()) {
                            Text("Sync all ${vm.repos.size} repos")
                        }
                    }
                }
                items(vm.repos, key = { it.id }) { repo ->
                    RepoCard(
                        repo = repo,
                        info = vm.info[repo.id],
                        busyText = vm.busy[repo.id],
                        onSync = { vm.sync(repo) },
                        onOpen = { selected = repo },
                    )
                }
            }
        }
    }

    if (showAdd) {
        AddRepoDialog(
            onDismiss = { showAdd = false },
            onAdd = { path -> vm.addRepo(path) },
            onCloneInstead = { showAdd = false; showClone = true },
        )
    }
    if (showClone) {
        CloneDialog(vm, onDismiss = { showClone = false })
    }
    selected?.let { repo ->
        RepoDetailDialog(vm, repo, onDismiss = { selected = null })
    }
}

@Composable
private fun StorageBanner(askForStorage: () -> Unit) {
    NoticeCard(
        title = "Storage access needed",
        body = "NestSync needs \"All files access\" to read and write your story folders. " +
            "Tap below, then switch NestSync on and come back.",
        button = "Allow access",
        onClick = askForStorage,
        color = Warn,
    )
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
                val ago = if (repo.lastTime > 0) " · " + DateUtils.getRelativeTimeSpanString(repo.lastTime) else ""
                Text(
                    (if (repo.lastOk == true) "✓ " else "✗ ") + repo.lastTitle + ago,
                    color = if (repo.lastOk == true) Good else Bad,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
                if (repo.lastOk != true) {
                    repo.lastDetails.lastOrNull()?.let { Text(it, color = TextDim, fontSize = 13.sp) }
                    Text("Tap the card for full details.", color = TextDim, fontSize = 12.sp)
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
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = OnAccent),
                ) { Text("Sync", fontSize = 17.sp, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

// =====================================================================
// Dialogs
// =====================================================================

@Composable
private fun AddRepoDialog(onDismiss: () -> Unit, onAdd: (String) -> String?, onCloneInstead: () -> Unit) {
    var path by remember { mutableStateOf("/storage/emulated/0/Stories/") }
    var error by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val p = treeUriToPath(uri)
            if (p != null) path = p else error = "Couldn't read that folder's path. Type it instead."
        }
    }

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
                OutlinedButton(onClick = { picker.launch(null) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Browse for folder…")
                }
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
                TextButton(onClick = onCloneInstead) { Text("Repo isn't on this phone yet? Clone it instead") }
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
private fun CloneDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    var url by remember { mutableStateOf("https://") }
    var name by remember { mutableStateOf("") }
    var parent by remember { mutableStateOf(vm.settings.cloneFolder) }

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
                Spacer(Modifier.height(8.dp))
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
                vm.settings.cloneFolder = parent.trim().trimEnd('/')
                vm.clone(url, name)
                onDismiss()
            }) { Text("Clone") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun RepoDetailDialog(vm: MainViewModel, repo: RepoEntry, onDismiss: () -> Unit) {
    val current = vm.repos.firstOrNull { it.id == repo.id } ?: repo
    val info = vm.info[repo.id]
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
                        vm.setRemote(current, GitEngine.sshToHttps(info?.remoteUrl ?: ""))
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
                    if (current.lastOk == false && current.lastTitle == GitEngine.CONFLICT_TITLE) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Use server's copy: the server's version of those files comes down, your copy is " +
                                "saved in a 'NestSync backups' folder next to the repo, and the rest syncs both ways.",
                            color = TextDim, fontSize = 13.sp,
                        )
                        Button(onClick = { vm.sync(current, ConflictChoice.USE_SERVER); onDismiss() }) {
                            Text("Use server's copy")
                        }
                        Text(
                            "Keep mine: your version of those files goes up and replaces the server's.",
                            color = TextDim, fontSize = 13.sp,
                        )
                        TextButton(onClick = { vm.sync(current, ConflictChoice.KEEP_MINE); onDismiss() }) {
                            Text("Keep mine")
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = SurfaceHigh)
                Spacer(Modifier.height(4.dp))

                TextButton(onClick = { vm.testConnection(current); onDismiss() }) { Text("Test connection") }
                TextButton(onClick = { editingRemote = !editingRemote }) { Text("Change remote URL") }
                if (editingRemote) {
                    OutlinedTextField(
                        value = remote, onValueChange = { remote = it },
                        label = { Text("HTTPS remote URL") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(onClick = { vm.setRemote(current, remote); editingRemote = false }) { Text("Save remote") }
                }
                if (!confirmRemove) {
                    TextButton(onClick = { confirmRemove = true }) { Text("Remove from list", color = Bad) }
                } else {
                    Text("Only removes it from NestSync. Your files stay where they are.", color = TextDim, fontSize = 13.sp)
                    TextButton(onClick = { vm.removeRepo(current); onDismiss() }) { Text("Yes, remove", color = Bad) }
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
private fun SettingsScreen(vm: MainViewModel, back: () -> Unit) {
    var editing by remember { mutableStateOf<HostCredential?>(null) }
    var adding by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf(vm.settings.identity.name) }
    var email by remember { mutableStateOf(vm.settings.identity.email) }
    var savedIdentity by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = Bg,
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
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
        ) {
            Text("Hosts and tokens", color = TextMain, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(
                "One login per server. Every repo on that server uses it.",
                color = TextDim, fontSize = 14.sp,
            )
            Spacer(Modifier.height(10.dp))
            vm.hosts.forEach { h ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = Surface),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).clickable { editing = h },
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(h.host, color = TextMain, fontWeight = FontWeight.SemiBold)
                            Text("${h.username} · token ••••${h.token.takeLast(4)}", color = TextDim, fontSize = 13.sp)
                        }
                        IconButton(onClick = { vm.removeHost(h.host) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = Bad)
                        }
                    }
                }
            }
            OutlinedButton(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) { Text("Add a host") }

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
                vm.settings.identity = Identity(name.trim(), email.trim())
                savedIdentity = true
            }) { Text(if (savedIdentity) "Saved ✓" else "Save") }

            Spacer(Modifier.height(28.dp))
            Text(
                "NestSync ${BuildInfo.version}",
                color = TextDim, fontSize = 12.sp,
            )
        }
    }

    if (adding || editing != null) {
        HostDialog(
            existing = editing,
            onDismiss = { adding = false; editing = null },
            onSave = { vm.saveHost(it); adding = false; editing = null },
        )
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
                        else -> "Tap a quick-fill button or type your server's name."
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
