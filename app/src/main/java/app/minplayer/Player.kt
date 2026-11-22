package app.minplayer

import android.content.ComponentName
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.media3.common.*
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.delay

class PlayerActivity : ComponentActivity() {
    private var future: ListenableFuture<MediaController>? = null
    private val ctrl = mutableStateOf<MediaController?>(null)

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val data = intent.data
        val items = if (data != null) listOf(MediaItem.Builder().setUri(data)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(data.lastPathSegment).build()).build()) else Queue.items
        val start = if (data != null) 0 else Queue.start
        val f = MediaController.Builder(this, SessionToken(this, ComponentName(this, PlaybackService::class.java))).buildAsync()
        future = f
        f.addListener({
            val c = f.get()
            c.setMediaItems(items, start, 0L); c.prepare(); c.play()
            ctrl.value = c
        }, ContextCompat.getMainExecutor(this))
        setContent { AppTheme { Box(Modifier.fillMaxSize().background(Color.Black)) {
            ctrl.value?.let { PlayerScreen(it) } } } }
    }

    private fun isVideo() = ctrl.value?.currentTracks?.isTypeSelected(C.TRACK_TYPE_VIDEO) == true

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations && isVideo()) ctrl.value?.pause()
    }

    override fun onDestroy() {
        if (isFinishing && isVideo()) ctrl.value?.run { stop(); clearMediaItems() }
        future?.let { MediaController.releaseFuture(it) }
        super.onDestroy()
    }
}

@Composable
fun PlayerScreen(p: Player) {
    var showTimer by remember { mutableStateOf(false) }
    val end by SleepTimer.endAt.collectAsState()
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(end) { while (end > 0) { now = SystemClock.elapsedRealtime(); delay(1000) } }
    var isVideo by remember { mutableStateOf(p.currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO)) }
    var title by remember { mutableStateOf(p.mediaMetadata.title?.toString() ?: "") }
    DisposableEffect(p) {
        val l = object : Player.Listener {
            override fun onTracksChanged(t: Tracks) { isVideo = t.isTypeSelected(C.TRACK_TYPE_VIDEO) }
            override fun onMediaMetadataChanged(m: MediaMetadata) { title = m.title?.toString() ?: "" }
        }
        p.addListener(l); onDispose { p.removeListener(l) }
    }
    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { PlayerView(it).apply { player = p; setShowNextButton(true); setShowPreviousButton(true) } },
            update = {
                it.controllerShowTimeoutMs = if (isVideo) 3000 else 0
                it.controllerHideOnTouch = isVideo
                if (!isVideo) it.showController()
            }, modifier = Modifier.fillMaxSize())
        if (!isVideo) Text(title, color = Color.White, style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.align(Alignment.Center).padding(32.dp))
        FilledTonalButton(onClick = { showTimer = true },
            modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp)) {
            Text(if (end > 0) "⏲ " + fmt(maxOf(0L, end - now)) else "⏲ টাইমার")
        }
    }
    if (showTimer) TimerDialog(end > 0) { showTimer = false }
}

@Composable
fun TimerDialog(active: Boolean, onDismiss: () -> Unit) {
    var custom by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("স্লিপ টাইমার") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(10, 15, 30, 45, 60, 90).chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { m -> FilledTonalButton(onClick = { SleepTimer.start(m); onDismiss() }) { Text("$m মিনিট") } }
                    }
                }
                OutlinedTextField(value = custom, onValueChange = { custom = it.filter(Char::isDigit) },
                    label = { Text("কাস্টম (মিনিট)") }, singleLine = true)
            }
        },
        confirmButton = { TextButton(onClick = {
            custom.toIntOrNull()?.takeIf { it > 0 }?.let { SleepTimer.start(it) }; onDismiss() }) { Text("সেট") } },
        dismissButton = { if (active) TextButton(onClick = { SleepTimer.cancel(); onDismiss() }) { Text("বন্ধ করুন") } })
}
