package app.minplayer

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.*
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

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
        val vid = if (data != null) contentResolver.getType(data)?.startsWith("video") ?: (data.scheme?.startsWith("http") == true) else Queue.video
        if (vid) {
            if (sp(this).getBoolean("land", true)) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            WindowInsetsControllerCompat(window, window.decorView).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars()) }
        }
        val f = MediaController.Builder(this, SessionToken(this, ComponentName(this, PlaybackService::class.java))).buildAsync()
        future = f
        f.addListener({
            val c = f.get()
            c.setMediaItems(items, start, 0L); c.prepare(); c.play()
            ctrl.value = c
        }, ContextCompat.getMainExecutor(this))
        setContent { AppTheme { Box(Modifier.fillMaxSize().background(Color.Black)) { ctrl.value?.let { PlayerScreen(it) } } } }
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
    var hud by remember { mutableStateOf("") }
    LaunchedEffect(hud) { if (hud.isNotEmpty()) { delay(800); hud = "" } }
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
    val act = LocalContext.current as? Activity
    val land = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { c -> makePlayerView(c, p) { hud = it } },
            update = {
                it.controllerShowTimeoutMs = if (isVideo) 3000 else 0
                it.controllerHideOnTouch = isVideo
                if (!isVideo) it.showController()
            }, modifier = Modifier.fillMaxSize())
        if (!isVideo) Text(title, color = Color.White, style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.align(Alignment.Center).padding(32.dp))
        if (hud.isNotEmpty()) Surface(shape = RoundedCornerShape(16.dp), color = Color(0x99000000), modifier = Modifier.align(Alignment.Center)) {
            Text(hud, color = Color.White, style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
        }
        Row(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { act?.requestedOrientation = if (land) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE }) { Text("⟳ Rotate") }
            FilledTonalButton(onClick = { showTimer = true }) { Text(if (end > 0) "⏲ " + fmt(maxOf(0L, end - now)) else "⏲ Timer") }
        }
    }
    if (showTimer) TimerDialog(end > 0) { showTimer = false }
}

fun makePlayerView(c: Context, p: Player, onHud: (String) -> Unit): PlayerView {
    val act = c as? Activity
    val am = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    var vol = 0f   // 0..15
    var br = -1f   // 0..30
    val v = PlayerView(c)
    v.player = p; v.setShowNextButton(true); v.setShowPreviousButton(true)
    val gd = GestureDetector(c, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            vol = am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max * 15f; return true
        }
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (v.isControllerFullyVisible) { if (v.controllerHideOnTouch) v.hideController() } else v.showController()
            return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            val d = p.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
            val back = e.x < v.width / 2
            p.seekTo((p.currentPosition + if (back) -10_000L else 10_000L).coerceIn(0L, d))
            onHud(if (back) "⏪ 10 s" else "10 s ⏩")
            return true
        }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (e1 == null || abs(dy) < abs(dx)) return true
            val f = dy / v.height * 2f
            if (e1.x < v.width / 2) {
                val w = act?.window ?: return true
                if (br < 0) br = (w.attributes.screenBrightness.takeIf { it >= 0 }
                    ?: (Settings.System.getInt(c.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) / 255f)) * 30f
                br = (br + f * 30f).coerceIn(1f, 30f)
                w.attributes = w.attributes.apply { screenBrightness = br.roundToInt() / 30f }
                onHud("☀ Brightness " + br.roundToInt() + "/30")
            } else {
                vol = (vol + f * 15f).coerceIn(0f, 15f)
                am.setStreamVolume(AudioManager.STREAM_MUSIC, (vol.roundToInt() / 15f * max).roundToInt(), 0)
                onHud("🔊 Volume " + vol.roundToInt() + "/15")
            }
            return true
        }
    })
    v.setOnTouchListener { _, ev -> gd.onTouchEvent(ev) }
    return v
}

@Composable
fun TimerDialog(active: Boolean, onDismiss: () -> Unit) {
    var custom by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Sleep timer") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(10, 15, 30, 45, 60, 90).chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { m -> FilledTonalButton(onClick = { SleepTimer.start(m); onDismiss() }) { Text("$m min") } }
                    }
                }
                OutlinedTextField(value = custom, onValueChange = { custom = it.filter(Char::isDigit) },
                    label = { Text("Custom (minutes)") }, singleLine = true)
            }
        },
        confirmButton = { TextButton(onClick = {
            custom.toIntOrNull()?.takeIf { it > 0 }?.let { SleepTimer.start(it) }; onDismiss() }) { Text("Set") } },
        dismissButton = { if (active) TextButton(onClick = { SleepTimer.cancel(); onDismiss() }) { Text("Turn off") } })
}
