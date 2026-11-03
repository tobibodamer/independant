package app.pendant

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.pendant.recording.RecordingController
import app.pendant.ui.HomeScreen
import app.pendant.ui.PendantWordmark
import app.pendant.ui.PendantTheme
import app.pendant.ui.RecordingsScreen
import app.pendant.ui.SettingsScreen

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as PendantApp
        setContent {
            PendantTheme {
                AppRoot(app)
            }
        }
    }
}

@Composable
fun AppRoot(app: PendantApp) {
    val context = LocalContext.current
    val state by RecordingController.state.collectAsState()
    val error by RecordingController.error.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    var hasMic by remember {
        mutableStateOf(
            androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED,
        )
    }
    val notifNeeded = android.os.Build.VERSION.SDK_INT >= 33
    var hasNotif by remember {
        mutableStateOf(
            !notifNeeded || androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED,
        )
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        hasMic = result[Manifest.permission.RECORD_AUDIO] == true
        hasNotif = result[Manifest.permission.POST_NOTIFICATIONS] == true || !notifNeeded
        if (hasMic) RecordingController.start(context)
    }

    LaunchedEffect(error) {
        error?.let { snackbar.showSnackbar(it) ; RecordingController.postError(null) }
    }

    val tabs = listOf("Listen", "Recordings", "Settings")
    var tab by remember { mutableStateOf(0) }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            PendantWordmark(Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
            if (!hasMic || !hasNotif) {
                Column(Modifier.fillMaxWidth().padding(20.dp), Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Pendant records your own speech in the background, all day. " +
                            "Everything stays on this phone. Grant the permissions to begin.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = {
                        val wanted = buildList {
                            add(Manifest.permission.RECORD_AUDIO)
                            if (notifNeeded) add(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        permLauncher.launch(wanted.toTypedArray())
                    }) { Text("Grant permissions") }
                }
            } else {
                // weight() keeps the tab row on screen even when the active tab's
                // content wants more height than is available.
                Column(Modifier.fillMaxWidth().weight(1f)) {
                    when (tab) {
                        0 -> HomeScreen(app, state)
                        1 -> RecordingsScreen(app)
                        2 -> SettingsScreen(app)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            TabRow(selectedTabIndex = tab, modifier = Modifier.fillMaxWidth()) {
                tabs.forEachIndexed { i, title ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(title) })
                }
            }
        }
    }

    // Battery optimization hint handled inside HomeScreen.
}
