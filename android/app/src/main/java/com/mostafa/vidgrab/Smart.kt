package com.mostafa.vidgrab

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Per-result state: official-metadata candidates and library duplicates. */
class Smart {
    var matching by mutableStateOf(false)
    var matches by mutableStateOf<List<Match>>(emptyList())
    var chosen by mutableIntStateOf(-1)              // -1 = use YouTube info
    var dupes by mutableStateOf<List<Dupe>>(emptyList())
    var needPerm by mutableStateOf(false)
    var permTick by mutableIntStateOf(0)
    val match get() = matches.getOrNull(chosen)
}

fun hasAudioPerm(ctx: Context) = ctx.checkSelfPermission(Dupes.permission()) == PackageManager.PERMISSION_GRANTED

@Composable
fun rememberSmart(info: MediaInfo): Smart {
    val ctx = LocalContext.current
    val s = remember(info.url) { Smart() }
    LaunchedEffect(info.url) {
        if (!Settings(ctx).matchMeta) return@LaunchedEffect
        s.matching = true
        s.matches = withContext(Dispatchers.IO) { Matcher.find(info.artist, info.title, info.rawTitle, info.duration, info.known) }
        s.chosen = if ((s.matches.firstOrNull()?.score ?: 0.0) >= Matcher.CONFIDENT) 0 else -1
        s.matching = false
    }
    LaunchedEffect(info.url, s.chosen, s.permTick) {
        if (!Settings(ctx).dupCheck) return@LaunchedEffect
        if (!hasAudioPerm(ctx)) { s.needPerm = true; return@LaunchedEffect }
        s.needPerm = false
        val lib = withContext(Dispatchers.IO) { Dupes.library(ctx) }
        val names = listOfNotNull(info.title to info.artist, s.match?.let { it.title to it.artist })
        s.dupes = withContext(Dispatchers.Default) {
            names.flatMap { (t, a) -> Dupes.find(t, a, info.duration, lib) }.groupBy { it.song.id }.map { it.value.maxBy { d -> d.score } }
                .sortedByDescending { it.score }.take(3)
        }
    }
    return s
}

/** Album/cover line under the result card, with a picker for other matches or "Use YouTube info". */
@Composable
fun MatchPanel(s: Smart) {
    val ctx = LocalContext.current
    var pick by remember { mutableStateOf(false) }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { s.permTick++ }
    if (s.dupes.isNotEmpty()) AssistChip(onClick = {}, label = { Text("Already in library") }, leadingIcon = { Icon(Icons.Rounded.LibraryMusic, null, Modifier.size(18.dp)) },
        colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer))
    if (s.needPerm && Settings(ctx).dupCheck) OutlinedCard(shape = RoundedCornerShape(18.dp)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Allow access to your music so VidGrab can warn you before downloading a song you already have. Nothing leaves your phone.",
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton({ perm.launch(Dupes.permission()) }) { Text("Allow") }
        }
    }
    if (!Settings(ctx).matchMeta) return
    ElevatedCard(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().clickable(enabled = !s.matching) { pick = true }) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            val m = s.match
            if (s.matching) { CircularProgressIndicator(Modifier.size(48.dp).padding(10.dp)); Spacer(Modifier.width(12.dp)); Text("Finding official album & cover…") }
            else {
                if (m != null) AsyncImage(m.cover.replace("1000x1000bb", "200x200bb").replace("front-1200", "front-250"), null, contentScale = ContentScale.Crop,
                    modifier = Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)))
                else Icon(Icons.Rounded.SmartDisplay, null, Modifier.size(56.dp).padding(10.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(m?.album ?: "Using YouTube info", fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(m?.let { listOf(it.artist, it.year, it.genre ?: "", it.src).filter { x -> x.isNotBlank() }.joinToString(" · ") }
                        ?: if (s.matches.isEmpty()) "No official match found" else "${s.matches.size} matches available",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                if (s.matches.isNotEmpty()) Text("Change", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
    if (pick && s.matches.isNotEmpty()) AlertDialog(onDismissRequest = { pick = false }, confirmButton = { TextButton({ pick = false }) { Text("Close") } },
        title = { Text("Choose metadata") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                s.matches.forEachIndexed { i, m ->
                    ListItem(modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { s.chosen = i; pick = false },
                        leadingContent = { AsyncImage(m.cover.replace("1000x1000bb", "200x200bb").replace("front-1200", "front-250"), null,
                            contentScale = ContentScale.Crop, modifier = Modifier.size(44.dp).clip(RoundedCornerShape(8.dp))) },
                        headlineContent = { Text(m.album, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text("${m.artist} — ${m.title} · ${m.year}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        trailingContent = { if (s.chosen == i) Icon(Icons.Rounded.Check, null) })
                }
                ListItem(modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { s.chosen = -1; pick = false },
                    leadingContent = { Icon(Icons.Rounded.SmartDisplay, null, Modifier.size(44.dp)) },
                    headlineContent = { Text("Use YouTube info") }, trailingContent = { if (s.chosen == -1) Icon(Icons.Rounded.Check, null) })
            }
        })
}

/** "You may already have this" sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DupeSheet(s: Smart, onDismiss: () -> Unit, onDownload: () -> Unit) {
    val ctx = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("You may already have this", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            s.dupes.forEach { d ->
                ListItem(leadingContent = { AsyncImage(d.song.artUri, null, contentScale = ContentScale.Crop, modifier = Modifier.size(52.dp).clip(RoundedCornerShape(10.dp))) },
                    headlineContent = { Text(d.song.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(listOf(d.song.artist, d.song.album, dur(d.song.durationSec)).filter { it.isNotBlank() && it != "<unknown>" }.joinToString(" · "), maxLines = 2) },
                    trailingContent = { IconButton({ runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(d.song.uri, "audio/*")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)) } }) { Icon(Icons.Rounded.PlayCircle, "Play") } })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onDismiss, Modifier.weight(1f)) { Text("Cancel") }
                Button(onDownload, Modifier.weight(1f)) { Text("Download anyway") }
            }
        }
    }
}
