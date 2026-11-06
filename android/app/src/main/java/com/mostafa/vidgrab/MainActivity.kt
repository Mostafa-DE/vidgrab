package com.mostafa.vidgrab

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import coil.compose.AsyncImage
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val incoming = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        History.load(this)
        incoming.value = extractUrl(intent?.getStringExtra(Intent.EXTRA_TEXT) ?: intent?.dataString)
        setContent { VidGrabTheme { AppScreen(incoming) } }
        if (System.currentTimeMillis() - Settings(this).lastUpdate > 12 * 3600_000L) lifecycleScope.launch { updateYtdl(this@MainActivity) }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); extractUrl(intent.getStringExtra(Intent.EXTRA_TEXT) ?: intent.dataString)?.let { incoming.value = it } }
}

suspend fun updateYtdl(ctx: android.content.Context): String = withContext(Dispatchers.IO) {
    try {
        val r = YoutubeDL.getInstance().updateYoutubeDL(ctx.applicationContext, YoutubeDL.UpdateChannel._STABLE)
        Settings(ctx).lastUpdate = System.currentTimeMillis()
        "yt-dlp ${YoutubeDL.getInstance().versionName(ctx.applicationContext) ?: ""} (${r?.name?.lowercase()?.replace('_', ' ') ?: "ok"})"
    } catch (e: Exception) { "Update failed: ${e.message}" }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScreen(incoming: MutableStateFlow<String?>) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val ctx = LocalContext.current
    val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 && ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            notif.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    val running by WorkManager.getInstance(ctx).getWorkInfosByTagFlow(DownloadWorker.TAG).collectAsStateWithLifecycle(emptyList())
    val active = running.count { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
    Scaffold(
        topBar = { CenterAlignedTopAppBar(title = { Text(listOf("VidGrab", "Downloads", "Settings")[tab], fontWeight = FontWeight.Bold) }) },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(tab == 0, { tab = 0 }, { Icon(Icons.Rounded.Home, null) }, label = { Text("Home") })
                NavigationBarItem(tab == 1, { tab = 1 }, { BadgedBox(badge = { if (active > 0) Badge { Text("$active") } }) { Icon(Icons.Rounded.Download, null) } }, label = { Text("Downloads") })
                NavigationBarItem(tab == 2, { tab = 2 }, { Icon(Icons.Rounded.Settings, null) }, label = { Text("Settings") })
            }
        }) { pad ->
        Box(Modifier.padding(pad).consumeWindowInsets(pad)) {
            when (tab) { 0 -> HomeScreen(incoming) { tab = 1 }; 1 -> DownloadsScreen(running); else -> SettingsScreen() }
        }
    }
}

@Composable
fun HomeScreen(incoming: MutableStateFlow<String?>, goDownloads: () -> Unit) {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    var url by rememberSaveable { mutableStateOf("") }
    var info by remember { mutableStateOf<MediaInfo?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var sheet by remember { mutableStateOf(false) }
    fun fetch(u: String) { url = u; info = null; error = null; loading = true
        scope.launch { try { info = fetchInfo(u.trim()) } catch (e: Exception) { error = e.message?.lines()?.lastOrNull { it.contains("ERROR") } ?: e.message } ; loading = false } }
    val inc by incoming.collectAsStateWithLifecycle()
    LaunchedEffect(inc) { inc?.let { fetch(it); incoming.value = null } }
    // Auto-paste a YouTube link from the clipboard when the window gains focus (Android only allows clipboard reads while focused).
    val focused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(focused) {
        if (focused && Settings(ctx).autoPaste && info == null && !loading) {
            val clip = ctx.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString()
            extractUrl(clip)?.takeIf { isYoutube(it) && it != url }?.let { fetch(it) }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        OutlinedTextField(value = url, onValueChange = { url = it }, singleLine = true, shape = RoundedCornerShape(20.dp),
            placeholder = { Text("Paste a YouTube link") }, leadingIcon = { Icon(Icons.Rounded.Link, null) },
            trailingIcon = {
                Row {
                    IconButton({ ctx.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(ctx)?.let { url = it.toString() } }) { Icon(Icons.Rounded.ContentPaste, "Paste") }
                    IconButton({ if (url.isNotBlank()) fetch(url) }) { Icon(Icons.Rounded.Search, "Fetch") }
                }
            },
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onGo = { if (url.isNotBlank()) fetch(url) }),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Go),
            modifier = Modifier.fillMaxWidth().height(64.dp))
        if (loading) Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        error?.let { Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Text("Couldn't load: $it\n\nTip: Settings → Update yt-dlp, then retry.", Modifier.padding(16.dp)) } }
        info?.let { i ->
            val smart = rememberSmart(i)
            var dupe by remember(i.url) { mutableStateOf(false) }
            fun mp3() { DownloadWorker.enqueue(ctx, i, true, match = smart.match); Toast.makeText(ctx, "Downloading MP3…", Toast.LENGTH_SHORT).show(); goDownloads() }
            ResultCard(i)
            MatchPanel(smart)
            ActionButtons(i, onMp3 = { if (smart.dupes.isNotEmpty()) dupe = true else mp3() }, onVideo = { sheet = true })
            if (dupe) DupeSheet(smart, { dupe = false }) { dupe = false; mp3() }
            if (sheet) QualitySheet(i, { sheet = false }) { c -> sheet = false; DownloadWorker.enqueue(ctx, i, false, c.height); goDownloads() }
        }
        if (info == null && !loading && error == null) Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Rounded.LibraryMusic, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(12.dp))
            Text("Copy a YouTube link or share it to VidGrab", style = MaterialTheme.typography.titleMedium)
            Text("MP3 320 kbps with cover art, artist & title", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

fun openUri(ctx: android.content.Context, uri: String, mime: String) = runCatching {
    ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri), mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
}.onFailure { Toast.makeText(ctx, "No app to open this file", Toast.LENGTH_SHORT).show() }

@Composable
fun DownloadsScreen(work: List<WorkInfo>) {
    val ctx = LocalContext.current
    val done by History.items.collectAsStateWithLifecycle()
    val live = work.filter { !it.state.isFinished }
    val failed = work.filter { it.state == WorkInfo.State.FAILED }.take(5)
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (live.isNotEmpty()) item { Text("In progress", style = MaterialTheme.typography.titleMedium) }
        items(live, key = { it.id }) { w ->
            val t = w.tags.firstOrNull { it.startsWith("t:") }?.removePrefix("t:") ?: "…"
            val th = w.tags.firstOrNull { it.startsWith("th:") }?.removePrefix("th:")
            val p = w.progress.getInt("p", 0); val stage = w.progress.getString("stage").orEmpty()
            Card(shape = RoundedCornerShape(20.dp)) { Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(th, null, contentScale = ContentScale.Crop, modifier = Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)))
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(t, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(6.dp))
                    if (w.state == WorkInfo.State.RUNNING && stage.isEmpty() && p > 0) LinearProgressIndicator({ p / 100f }, Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                    Spacer(Modifier.height(4.dp))
                    Text(when { w.state != WorkInfo.State.RUNNING -> "Waiting…"; stage.isNotEmpty() -> stage
                        else -> "$p% · ${w.progress.getString("speed").orEmpty()} · ETA ${dur(w.progress.getLong("eta", 0).toInt().coerceAtLeast(0))}" },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton({ WorkManager.getInstance(ctx).cancelWorkById(w.id) }) { Icon(Icons.Rounded.Close, "Cancel") }
            } }
        }
        items(failed, key = { "f" + it.id }) { w ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), shape = RoundedCornerShape(20.dp)) {
                Text("✗ ${w.tags.firstOrNull { it.startsWith("t:") }?.removePrefix("t:")}: ${w.outputData.getString("msg")}", Modifier.padding(14.dp), maxLines = 3) }
        }
        item { Text("Finished", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
        if (done.isEmpty()) item { Text("Nothing yet", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(done, key = { it.uri + it.time }) { d ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { openUri(ctx, d.uri, d.mime) }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(d.thumb, null, contentScale = ContentScale.Crop, modifier = Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)))
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(d.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                    Text((if (d.mime.startsWith("audio")) "Audio" else "Video") + if (d.artist.isNotBlank()) " · ${d.artist}" else "",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(if (d.mime.startsWith("audio")) Icons.Rounded.PlayCircle else Icons.Rounded.SmartDisplay, "Play", tint = MaterialTheme.colorScheme.primary)
                IconButton({ History.remove(ctx, d) }) { Icon(Icons.Rounded.DeleteOutline, "Remove from list") }
            }
        }
    }
}

@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current; val s = remember { Settings(ctx) }; val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(s.audioMode) }
    var orig by remember { mutableStateOf(s.originalLang) }
    var folder by remember { mutableStateOf(s.audioFolder) }
    var auto by remember { mutableStateOf(s.autoPaste) }
    var status by remember { mutableStateOf(YoutubeDL.getInstance().versionName(ctx.applicationContext)?.let { "yt-dlp $it" } ?: "") }
    var updating by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Audio quality", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        AudioMode.entries.forEach { m -> Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { mode = m; s.audioMode = m }, verticalAlignment = Alignment.CenterVertically) {
            RadioButton(mode == m, { mode = m; s.audioMode = m }); Text(m.label) } }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        ListItem(headlineContent = { Text("Original audio language") }, supportingContent = { Text("Avoid auto-dubbed audio tracks") },
            trailingContent = { Switch(orig, { orig = it; s.originalLang = it }) })
        ListItem(headlineContent = { Text("Auto-paste YouTube links") }, supportingContent = { Text("Fetch the link in your clipboard when VidGrab opens") },
            trailingContent = { Switch(auto, { auto = it; s.autoPaste = it }) })
        var mm by remember { mutableStateOf(s.matchMeta) }
        ListItem(headlineContent = { Text("Match official metadata (album, cover)") },
            supportingContent = { Text("Looks the song up on iTunes, then MusicBrainz, for the official artist, album, year, genre and a high-res cover") },
            trailingContent = { Switch(mm, { mm = it; s.matchMeta = it }) })
        var dc by remember { mutableStateOf(s.dupCheck) }
        ListItem(headlineContent = { Text("Warn about songs I already have") },
            supportingContent = { Text("Checks your music library on the phone before downloading an MP3") },
            trailingContent = { Switch(dc, { dc = it; s.dupCheck = it }) })
        Text("Audio folder", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Music", "Download").forEach { f -> FilterChip(folder == f, { folder = f; s.audioFolder = f }, { Text("$f/VidGrab") }) }
        }
        Text("Videos are saved to Movies/VidGrab.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        ListItem(headlineContent = { Text("Update yt-dlp") }, supportingContent = { Text(status) },
            trailingContent = { if (updating) CircularProgressIndicator(Modifier.size(24.dp)) else FilledTonalButton({ updating = true
                scope.launch { status = updateYtdl(ctx); updating = false } }) { Text("Update") } })
        Text("VidGrab ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 16.dp))
    }
}
