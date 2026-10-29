package app.pendant.ui

import android.content.Intent
import android.media.MediaPlayer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import app.pendant.PendantApp
import app.pendant.storage.SegmentStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun RecordingsScreen(app: PendantApp) {
    val context = LocalContext.current
    val store = app.store
    var recordings by remember { mutableStateOf(store.list()) }
    var playingPath by remember { mutableStateOf<String?>(null) }
    val player = remember { MediaPlayer() }

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
            items(recordings, key = { it.file.absolutePath }) { seg ->
                Card(colors = CardDefaults.cardColors()) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = {
                            if (playingPath == seg.file.absolutePath) {
                                playingPath = null
                                try {
                                    player.stop()
                                } catch (_: Exception) {
                                }
                            } else {
                                try {
                                    player.reset()
                                    player.setDataSource(seg.file.absolutePath)
                                    player.prepare()
                                    player.start()
                                    playingPath = seg.file.absolutePath
                                    player.setOnCompletionListener {
                                        playingPath = null
                                    }
                                } catch (_: Exception) {
                                    playingPath = null
                                }
                            }
                        }) {
                            Icon(
                                imageVector = if (playingPath == seg.file.absolutePath) Icons.Filled.Delete else Icons.Filled.PlayArrow,
                                contentDescription = "Play",
                            )
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
                            share(context, seg)
                        }) {
                            Icon(Icons.Filled.Share, contentDescription = "Share")
                        }
                        IconButton(onClick = {
                            if (playingPath == seg.file.absolutePath) {
                                playingPath = null
                                try {
                                    player.stop()
                                } catch (_: Exception) {
                                }
                            }
                            store.delete(seg)
                            recordings = store.list()
                        }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}

private val nameFmt = SimpleDateFormat("EEE d MMM, HH:mm:ss", Locale.getDefault())

private fun share(context: android.content.Context, seg: SegmentStore.SegmentInfo) {
    try {
        val uri = FileProvider.getUriForFile(context, "app.pendant.fileprovider", seg.file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "audio/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Share recording"))
    } catch (_: Exception) {
    }
}

private fun formatTime(ms: Long): String = nameFmt.format(Date(ms))
