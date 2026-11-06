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

/** Translucent activity: a compact sheet over YouTube with MP3 / Video, then finishes. */
class ShareActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val url = extractUrl(intent?.getStringExtra(Intent.EXTRA_TEXT) ?: intent?.dataString)
        if (url == null) { Toast.makeText(this, "No link found", Toast.LENGTH_SHORT).show(); finish(); return }
        setContent { VidGrabTheme {
            var info by remember { mutableStateOf<MediaInfo?>(null) }
            var err by remember { mutableStateOf<String?>(null) }
            var quality by remember { mutableStateOf(false) }
            LaunchedEffect(url) { try { info = fetchInfo(url) } catch (e: Exception) { err = e.message?.lines()?.lastOrNull { it.contains("ERROR") } ?: e.message } }
            val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ModalBottomSheet(onDismissRequest = { finish() }, sheetState = sheet) {
                Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    val i = info
                    when {
                        err != null -> Text("Couldn't load: $err")
                        i == null -> Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                        else -> {
                            val smart = rememberSmart(i)
                            var dupe by remember { mutableStateOf(false) }
                            fun mp3() { DownloadWorker.enqueue(this@ShareActivity, i, true, match = smart.match)
                                Toast.makeText(this@ShareActivity, "Downloading MP3 — check notifications", Toast.LENGTH_SHORT).show(); finish() }
                            ResultCard(i, compact = true)
                            MatchPanel(smart)
                            ActionButtons(i, onMp3 = { if (smart.dupes.isNotEmpty()) dupe = true else mp3() }, onVideo = { quality = true })
                            if (dupe) DupeSheet(smart, { dupe = false }) { dupe = false; mp3() }
                        }
                    }
                }
            }
            info?.let { i -> if (quality) QualitySheet(i, { quality = false }) { c -> DownloadWorker.enqueue(this, i, false, c.height)
                Toast.makeText(this, "Downloading ${c.label} video", Toast.LENGTH_SHORT).show(); finish() } }
        } }
    }
}
