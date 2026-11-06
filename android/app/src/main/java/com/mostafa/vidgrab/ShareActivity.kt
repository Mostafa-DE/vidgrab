package com.mostafa.vidgrab

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Translucent activity: a compact sheet over YouTube with MP3 / Video, then finishes.
 *  YouTube links show quick oEmbed info first (buttons work at once); yt-dlp's full info replaces it when it arrives. */
class ShareActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        if (BuildConfig.DEBUG) Timing.shareCreated()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val url = extractUrl(intent?.getStringExtra(Intent.EXTRA_TEXT) ?: intent?.dataString)
        if (url == null) { Toast.makeText(this, "No link found", Toast.LENGTH_SHORT).show(); finish(); return }
        setContent { VidGrabTheme {
            var info by remember { mutableStateOf<MediaInfo?>(null) }
            var err by remember { mutableStateOf<String?>(null) }
            var quality by remember { mutableStateOf(false) }
            LaunchedEffect(url) {
                val quick = launch { quickInfo(url)?.let { if (info == null) info = it } }
                try { info = fetchInfo(url) } catch (e: Exception) {  // not when the sheet is gone (its lookup was killed)
                    if (isActive) err = e.message?.lines()?.lastOrNull { it.contains("ERROR") } ?: e.message }
                quick.cancel()  // full info (or its error) came first
            }
            if (BuildConfig.DEBUG) LaunchedEffect(info?.partial) { info?.let { Timing.since(if (it.partial) "quick info visible" else "full info") } }
            val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ModalBottomSheet(onDismissRequest = { finish() }, sheetState = sheet) {
                Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    val i = info
                    when {
                        err != null && i == null -> Text("Couldn't load: $err")
                        i == null -> Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                        else -> {
                            val smart = rememberSmart(i)
                            var dupe by remember { mutableStateOf(false) }
                            fun mp3() { DownloadWorker.enqueue(this@ShareActivity, i, true, match = smart.match, matchPending = smart.matching)
                                if (BuildConfig.DEBUG && i.partial) Timing.since("MP3 enqueued before full info (match pending ${smart.matching})")
                                Toast.makeText(this@ShareActivity, "Downloading MP3 — check notifications", Toast.LENGTH_SHORT).show(); finish() }
                            ResultCard(i, compact = true)
                            if (err == null) MatchPanel(smart)
                            ActionButtons(i, onMp3 = { if (smart.dupes.isNotEmpty()) dupe = true else mp3() }, onVideo = { quality = true })
                            if (err != null) Text("Couldn't load the full details. The download looks the video up again.",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (dupe) DupeSheet(smart, { dupe = false }) { dupe = false; mp3() }
                        }
                    }
                }
            }
            info?.let { i -> if (quality) QualitySheet(i, { quality = false }) { c -> DownloadWorker.enqueue(this, i, false, c.height)
                if (BuildConfig.DEBUG && i.partial) Timing.since("Video ${c.label} enqueued before full info")
                Toast.makeText(this, "Downloading ${c.label} video", Toast.LENGTH_SHORT).show(); finish() } }
        } }
    }
}
