package com.mostafa.vidgrab

import android.content.Context
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

private val Violet = Color(0xFF6A1BD8); private val Magenta = Color(0xFFE0247C)

@Composable
fun VidGrabTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme(); val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme(primary = Color(0xFFCFBCFF), secondary = Color(0xFFFFB0C8))
        else -> lightColorScheme(primary = Violet, secondary = Magenta)
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

suspend fun fetchInfo(url: String): MediaInfo = withContext(Dispatchers.IO) {
    val req = YoutubeDLRequest(url).apply { addOption("-J"); addOption("--no-playlist") }
    MediaInfo.parse(url, JSONObject(YoutubeDL.getInstance().execute(req).out))
}

fun extractUrl(text: String?): String? = text?.let { Regex("""https?://\S+""").find(it)?.value }
fun isYoutube(u: String) = Regex("""(?i)(youtube\.com|youtu\.be|music\.youtube)""").containsMatchIn(u)

@Composable
fun ResultCard(info: MediaInfo, compact: Boolean = false) {
    ElevatedCard(shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
        Box {
            AsyncImage(model = info.thumb, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(if (compact) 2.2f else 16f / 9f))
            Surface(color = Color.Black.copy(alpha = .7f), shape = RoundedCornerShape(8.dp),
                modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp)) {
                Text(dur(info.duration), color = Color.White, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
            }
        }
        Column(Modifier.padding(16.dp)) {
            Text(info.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(info.artist, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
fun ActionButtons(info: MediaInfo, onMp3: () -> Unit, onVideo: () -> Unit) {
    val mode = remember { Settings(MyApp.ctx).audioMode }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        Button(onClick = onMp3, shape = RoundedCornerShape(20.dp), modifier = Modifier.weight(1.4f).height(72.dp),
            contentPadding = PaddingValues(0.dp)) {
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Violet, Magenta))), contentAlignment = Alignment.Center) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.MusicNote, null, tint = Color.White, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(if (mode == AudioMode.ORIGINAL) "AUDIO" else "MP3", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Text(when (mode) { AudioMode.MP3_320 -> "320 kbps"; AudioMode.MP3_V0 -> "V0"; AudioMode.MP3_192 -> "192 kbps"; AudioMode.ORIGINAL -> "original" },
                            color = Color.White.copy(alpha = .85f), fontSize = 12.sp)
                    }
                }
            }
        }
        FilledTonalButton(onClick = onVideo, shape = RoundedCornerShape(20.dp), modifier = Modifier.weight(1f).height(72.dp)) {
            Icon(Icons.Rounded.Movie, null, modifier = Modifier.size(26.dp)); Spacer(Modifier.width(8.dp))
            Column { Text("Video", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(info.choices.firstOrNull()?.detail?.substringBefore(" ·") ?: "", fontSize = 12.sp) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun QualitySheet(info: MediaInfo, onDismiss: () -> Unit, onPick: (VideoChoice) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
            Text("Video quality", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(16.dp))
            if (info.choices.isEmpty()) Text("No video formats found. Try Settings → Update yt-dlp.")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                info.choices.forEach { c ->
                    val best = c.height == null
                    OutlinedCard(onClick = { onPick(c) }, shape = RoundedCornerShape(18.dp),
                        colors = if (best) CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else CardDefaults.outlinedCardColors(),
                        modifier = Modifier.widthIn(min = 100.dp)) {
                        Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
                            Text(c.label, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Text(c.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}


object MyApp { lateinit var ctx: Context }
