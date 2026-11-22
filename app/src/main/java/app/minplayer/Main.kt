package app.minplayer

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

data class Media(val uri: Uri, val title: String, val dur: Long, val video: Boolean) {
    fun item(): MediaItem = MediaItem.Builder().setUri(uri)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build()).build()
}
object Queue { var items: List<MediaItem> = emptyList(); var start = 0 }

object SleepTimer {
    val endAt = MutableStateFlow(0L) // SystemClock.elapsedRealtime() based
    private val h = Handler(Looper.getMainLooper())
    private var player: Player? = null
    private val fire = Runnable { player?.pause(); endAt.value = 0L }
    fun attach(p: Player) { player = p }
    fun start(min: Int) {
        h.removeCallbacks(fire)
        endAt.value = SystemClock.elapsedRealtime() + min * 60_000L
        h.postDelayed(fire, min * 60_000L)
    }
    fun cancel() { h.removeCallbacks(fire); endAt.value = 0L }
}

class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    override fun onCreate() {
        super.onCreate()
        val p = ExoPlayer.Builder(this)
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL).build()
        SleepTimer.attach(p)
        session = MediaSession.Builder(this, p).build()
    }
    override fun onGetSession(c: MediaSession.ControllerInfo) = session
    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }
    override fun onDestroy() {
        SleepTimer.cancel()
        session?.run { player.release(); release() }
        session = null
        super.onDestroy()
    }
}

fun fmt(ms: Long): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme(); val ctx = LocalContext.current
    val cs = if (Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    } else if (dark) darkColorScheme() else lightColorScheme()
    MaterialTheme(colorScheme = cs) { Surface(color = MaterialTheme.colorScheme.background, content = content) }
}

fun perms(): Array<String> = if (Build.VERSION.SDK_INT >= 33)
    arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

fun hasPerm(c: Context): Boolean = perms().filter { it != Manifest.permission.POST_NOTIFICATIONS }
    .all { ContextCompat.checkSelfPermission(c, it) == PackageManager.PERMISSION_GRANTED }

fun load(c: Context, video: Boolean): List<Media> {
    val coll = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    val dur = if (video) MediaStore.Video.Media.DURATION else MediaStore.Audio.Media.DURATION
    val out = ArrayList<Media>()
    c.contentResolver.query(coll, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, dur),
        null, null, MediaStore.MediaColumns.DATE_ADDED + " DESC")?.use {
        while (it.moveToNext())
            out += Media(ContentUris.withAppendedId(coll, it.getLong(0)), it.getString(1) ?: "", it.getLong(2), video)
    }
    return out
}

class MainActivity : ComponentActivity() {
    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) startActivity(Intent(this, PlayerActivity::class.java)
            .setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        setContent { AppTheme { Library(
            open = { list, i ->
                Queue.items = list.map { it.item() }; Queue.start = i
                startActivity(Intent(this, PlayerActivity::class.java))
            },
            pick = { picker.launch(arrayOf("*/*")) }) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Library(open: (List<Media>, Int) -> Unit, pick: () -> Unit) {
    val ctx = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    var videos by remember { mutableStateOf(emptyList<Media>()) }
    var audios by remember { mutableStateOf(emptyList<Media>()) }
    var granted by remember { mutableStateOf(hasPerm(ctx)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted = hasPerm(ctx) }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(perms()) }
    LaunchedEffect(granted) {
        if (granted) {
            val v = withContext(Dispatchers.IO) { load(ctx, true) }
            val a = withContext(Dispatchers.IO) { load(ctx, false) }
            videos = v; audios = a
        }
    }
    Scaffold(topBar = { TopAppBar(title = { Text("One Player") },
        actions = { TextButton(onClick = pick) { Text("ফাইল খুলুন") } }) }) { pad ->
        Column(Modifier.padding(pad)) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("ভিডিও") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("অডিও") })
            }
            val list = if (tab == 0) videos else audios
            if (!granted) Text("মিডিয়ার অনুমতি দিন অথবা ‘ফাইল খুলুন’ চাপুন", Modifier.padding(16.dp))
            LazyColumn {
                itemsIndexed(list) { i, m ->
                    ListItem(headlineContent = { Text(m.title, maxLines = 1) },
                        supportingContent = { Text(fmt(m.dur)) },
                        modifier = Modifier.clickable { open(list, i) })
                }
            }
        }
    }
}
