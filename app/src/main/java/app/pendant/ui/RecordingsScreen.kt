package app.pendant.ui

import android.content.Intent
import android.media.MediaPlayer
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import app.pendant.PendantApp
import app.pendant.storage.SegmentInfo
import app.pendant.storage.SegmentStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun RecordingsScreen(app: PendantApp) {
    val context = LocalContext.current
    val store = app.store
    val scope = rememberCoroutineScope()
    var recordings by remember { mutableStateOf<List<SegmentInfo>>(emptyList()) }
    var playingKey by remember { mutableStateOf<String?>(null) }
    val player = remember { MediaPlayer() }

    suspend fun reload() {
        // Fast backends first so the list paints immediately; the custom tree's
        // SAF clips append asynchronously when the background scan delivers a
        // merged snapshot on the main thread.
        recordings = withContext(Dispatchers.IO) {
            store.list(onSafClips = { recordings = it })
        }
    }

    LaunchedEffect(Unit) {
        reload()
    }

    fun stopPlayback() {
        if (playingKey != null) {
            playingKey = null
            try {
                player.stop()
            } catch (_: Exception) {
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            try {
                player.release()
            } catch (_: Exception) {
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Text(
            "${recordings.size} clips · ${formatBytes(recordings.sumOf { it.sizeBytes })}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 10.dp),
        )
        if (recordings.isEmpty()) {
            Text(
                "Nothing here yet. Clips appear as you speak.",
                style = MaterialTheme.typography.bodyMedium,
            )
            return@Column
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(recordings, key = { it.key }) { seg ->
                Card(colors = CardDefaults.cardColors()) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = {
                            if (playingKey == seg.key) {
                                stopPlayback()
                            } else {
                                try {
                                    player.reset()
                                    player.setDataSource(context, seg.playbackUri())
                                    player.prepare()
                                    player.start()
                                    playingKey = seg.key
                                    player.setOnCompletionListener {
                                        playingKey = null
                                    }
                                } catch (_: Exception) {
                                    playingKey = null
                                }
                            }
                        }) {
                            if (playingKey == seg.key) PauseBars() else {
                                Icon(Icons.Filled.PlayArrow, contentDescription = "Play")
                            }
                        }
                        Column(Modifier.weight(1f)) {
                            Text(seg.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                            Text(
                                formatDuration(seg.durationMs) + " · " + formatBytes(seg.sizeBytes),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = {
                            if (playingKey == seg.key) stopPlayback()
                            share(context, seg)
                        }) {
                            Icon(Icons.Filled.Share, contentDescription = "Share")
                        }
                        IconButton(onClick = {
                            stopPlayback()
                            store.delete(seg)
                            scope.launch { reload() }
                        }) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = "Delete",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }
    }
}

private val nameFmt = SimpleDateFormat("EEE d MMM, HH:mm:ss", Locale.getDefault())

/** Two-bar pause glyph (material-icons-core has no Pause icon). */
@Composable
private fun PauseBars() {
    val tint = androidx.compose.material3.LocalContentColor.current
    Row(
        Modifier.height(20.dp).padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(6.dp).height(20.dp).background(tint))
        Box(Modifier.width(6.dp).height(20.dp).background(tint))
    }
}

private fun formatTime(ms: Long): String = nameFmt.format(Date(ms))

private fun share(context: android.content.Context, seg: SegmentInfo) {
    try {
        // FileProvider cannot share remote content URIs, so MediaStore/SAF clips are
        // shared via their own content URI; plain files via FileProvider.
        val uri = seg.shareContentUri() ?: FileProvider.getUriForFile(
            context,
            "app.pendant.fileprovider",
            seg.backingFile ?: return,
        )
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "audio/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Share recording"))
    } catch (_: Exception) {
    }
}
