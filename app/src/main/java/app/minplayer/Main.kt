package app.minplayer

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

data class Media(val uri: Uri, val title: String, val dur: Long, val video: Boolean,
                 val size: Long, val added: Long, val dir: String) {
    val id: String get() = uri.lastPathSegment ?: ""
    fun item(): MediaItem = MediaItem.Builder().setUri(uri)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build()).build()
}
class Folder(val dir: String, val items: List<Media>) {
    val name get() = dir.substringAfterLast('/')
    val parent get() = dir.substringBeforeLast('/', "")
    val size get() = items.sumOf { it.size }
}
object Queue { var items: List<MediaItem> = emptyList(); var start = 0; var video = true }

object SleepTimer {
    val endAt = MutableStateFlow(0L)
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
        val p = ExoPlayer.Builder(this).setAudioAttributes(AudioAttributes.DEFAULT, true)
            .setHandleAudioBecomingNoisy(true).setWakeMode(C.WAKE_MODE_LOCAL).build()
        SleepTimer.attach(p)
        session = MediaSession.Builder(this, p).build()
    }
    override fun onGetSession(c: MediaSession.ControllerInfo) = session
    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }
    override fun onDestroy() {
        SleepTimer.cancel(); session?.run { player.release(); release() }; session = null; super.onDestroy()
    }
}

fun fmt(ms: Long): String { val s = ms / 1000; return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60) }
fun fmtSize(b: Long) = if (b >= (1L shl 30)) "%.1f GB".format(b / 1073741824.0) else "%.1f MB".format(b / 1048576.0)
fun sp(c: Context): SharedPreferences = c.getSharedPreferences("one", Context.MODE_PRIVATE)
fun played(c: Context): Set<String> = sp(c).getStringSet("played", emptySet()) ?: emptySet()
fun markPlayed(c: Context, id: String) { sp(c).edit().putStringSet("played", played(c) + id).apply() }

private val LightC = lightColorScheme(
    primary = Color(0xFF1E4F2F), onPrimary = Color(0xFFF7F4EC),
    primaryContainer = Color(0xFFDDE8D6), onPrimaryContainer = Color(0xFF12331E),
    secondary = Color(0xFF1E4F2F), onSecondary = Color(0xFFF7F4EC),
    secondaryContainer = Color(0xFFE9E4D5), onSecondaryContainer = Color(0xFF2B2A22),
    tertiary = Color(0xFFC9A24B), onTertiary = Color(0xFF2A2000),
    background = Color(0xFFF7F4EC), onBackground = Color(0xFF1B1C18),
    surface = Color(0xFFFFFDF8), onSurface = Color(0xFF1B1C18),
    surfaceVariant = Color(0xFFE6E2D3), onSurfaceVariant = Color(0xFF5A5C52))
private val DarkC = darkColorScheme(
    primary = Color(0xFF8FCB9F), onPrimary = Color(0xFF0E2A18),
    primaryContainer = Color(0xFF244231), onPrimaryContainer = Color(0xFFCFE8D6),
    secondary = Color(0xFF173323), onSecondary = Color(0xFFF1EEE4),
    secondaryContainer = Color(0xFF2A3A2F), onSecondaryContainer = Color(0xFFE6E2D3),
    tertiary = Color(0xFFD9B861), onTertiary = Color(0xFF2A2000),
    background = Color(0xFF0F1A13), onBackground = Color(0xFFE9E6DC),
    surface = Color(0xFF16251B), onSurface = Color(0xFFE9E6DC),
    surfaceVariant = Color(0xFF26372C), onSurfaceVariant = Color(0xFFB7BDB0))

@Composable
fun AppTheme(theme: Int = 0, content: @Composable () -> Unit) {
    val dark = when (theme) { 1 -> true; 2 -> isSystemInDarkTheme(); else -> false }
    MaterialTheme(colorScheme = if (dark) DarkC else LightC) {
        Surface(color = MaterialTheme.colorScheme.background, content = content)
    }
}

fun perms(): Array<String> = if (Build.VERSION.SDK_INT >= 33)
    arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
fun hasPerm(c: Context) = perms().filter { it != Manifest.permission.POST_NOTIFICATIONS }
    .all { ContextCompat.checkSelfPermission(c, it) == PackageManager.PERMISSION_GRANTED }

fun load(c: Context, video: Boolean): List<Media> {
    val coll = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    val dur = if (video) MediaStore.Video.Media.DURATION else MediaStore.Audio.Media.DURATION
    val out = ArrayList<Media>()
    c.contentResolver.query(coll, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, dur,
        MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.DATA),
        null, null, MediaStore.MediaColumns.DATE_ADDED + " DESC")?.use {
        while (it.moveToNext()) out += Media(ContentUris.withAppendedId(coll, it.getLong(0)), it.getString(1) ?: "",
            it.getLong(2), video, it.getLong(3), it.getLong(4), (it.getString(5) ?: "").substringBeforeLast('/'))
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
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        setContent { App(openFile = { picker.launch(arrayOf("*/*")) }, play = { l, i ->
            markPlayed(this, l[i].id)
            Queue.items = l.map { it.item() }; Queue.start = i; Queue.video = l[i].video
            startActivity(Intent(this, PlayerActivity::class.java)) }) }
    }
}

@Composable
fun App(openFile: () -> Unit, play: (List<Media>, Int) -> Unit) {
    val ctx = LocalContext.current
    var theme by remember { mutableIntStateOf(sp(ctx).getInt("theme", 0)) }
    AppTheme(theme) { Shell(theme, { theme = it; sp(ctx).edit().putInt("theme", it).apply() }, openFile, play) }
}

@Composable
fun Shell(theme: Int, setTheme: (Int) -> Unit, openFile: () -> Unit, play: (List<Media>, Int) -> Unit) {
    val ctx = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    var videos by remember { mutableStateOf(emptyList<Media>()) }
    var audios by remember { mutableStateOf(emptyList<Media>()) }
    var granted by remember { mutableStateOf(hasPerm(ctx)) }
    var seen by remember { mutableStateOf(played(ctx)) }
    var openV by remember { mutableStateOf<String?>(null) }
    var openA by remember { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted = hasPerm(ctx) }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(perms()) }
    LaunchedEffect(granted) {
        if (granted) {
            val v = withContext(Dispatchers.IO) { load(ctx, true) }
            val a = withContext(Dispatchers.IO) { load(ctx, false) }
            videos = v; audios = a
        }
    }
    val cs = MaterialTheme.colorScheme
    Scaffold(containerColor = cs.background, bottomBar = {
        NavigationBar(containerColor = cs.surface) {
            listOf("Video", "Audio", "Me").forEachIndexed { i, n ->
                NavigationBarItem(selected = tab == i, onClick = { tab = i }, label = { Text(n) },
                    colors = NavigationBarItemDefaults.colors(indicatorColor = cs.primaryContainer, selectedIconColor = cs.onPrimaryContainer,
                        selectedTextColor = cs.primary),
                    icon = { when (i) { 0 -> Icon(Icons.Default.PlayArrow, null); 1 -> Text("♪", fontSize = 22.sp); else -> Icon(Icons.Default.Person, null) } })
            }
        }
    }) { pad ->
        Box(Modifier.padding(bottom = pad.calculateBottomPadding())) {
            val p: (List<Media>, Int) -> Unit = { l, i -> play(l, i); seen = played(ctx) }
            when (tab) {
                0 -> Browse(true, videos, granted, seen, openV, { openV = it }, p)
                1 -> Browse(false, audios, granted, seen, openA, { openA = it }, p)
                else -> Me(theme, setTheme, openFile)
            }
        }
    }
}

enum class Sort(val label: String) { NAME("Name"), DATE("Date added"), SIZE("Size") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun barColors() = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.secondary,
    titleContentColor = MaterialTheme.colorScheme.onSecondary, navigationIconContentColor = MaterialTheme.colorScheme.onSecondary,
    actionIconContentColor = MaterialTheme.colorScheme.onSecondary)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Browse(video: Boolean, all: List<Media>, granted: Boolean, seen: Set<String>, open: String?,
           setOpen: (String?) -> Unit, play: (List<Media>, Int) -> Unit) {
    var q by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var sort by remember { mutableStateOf(Sort.DATE) }
    var rev by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    BackHandler(searching || open != null) { if (searching) { searching = false; q = "" } else setOpen(null) }
    val folders = remember(all) { all.groupBy { it.dir }.map { Folder(it.key, it.value) } }
    val cur = folders.find { it.dir == open }
    val now = System.currentTimeMillis() / 1000
    fun isNew(m: Media) = m.id !in seen && now - m.added < 7 * 86400
    val qq = q.trim().lowercase()
    val fl = folders.filter { qq.isEmpty() || it.name.lowercase().contains(qq) }.let { l ->
        val s = when (sort) { Sort.NAME -> l.sortedBy { it.name.lowercase() }
            Sort.DATE -> l.sortedByDescending { f -> f.items.maxOf { it.added } }; Sort.SIZE -> l.sortedByDescending { it.size } }
        if (rev) s.reversed() else s }
    val ml = (cur?.items ?: emptyList()).filter { qq.isEmpty() || it.title.lowercase().contains(qq) }.let { l ->
        val s = when (sort) { Sort.NAME -> l.sortedBy { it.title.lowercase() }
            Sort.DATE -> l.sortedByDescending { it.added }; Sort.SIZE -> l.sortedByDescending { it.size } }
        if (rev) s.reversed() else s }
    val bar = MaterialTheme.colorScheme.onSecondary
    Column(Modifier.fillMaxSize()) {
        TopAppBar(colors = barColors(),
            navigationIcon = { if (cur != null) IconButton(onClick = { setOpen(null) }) { Icon(Icons.Default.ArrowBack, "Back") } },
            title = {
                if (searching) TextField(value = q, onValueChange = { q = it }, singleLine = true,
                    placeholder = { Text("Search…", color = bar.copy(alpha = .6f)) },
                    textStyle = LocalTextStyle.current.copy(color = bar),
                    colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, cursorColor = bar))
                else Text(cur?.name ?: if (video) "Videos" else "Audio", fontWeight = FontWeight.Bold, maxLines = 1)
            },
            actions = {
                IconButton(onClick = { if (searching) { searching = false; q = "" } else searching = true }) {
                    Icon(if (searching) Icons.Default.Close else Icons.Default.Search, "Search") }
                Box {
                    IconButton(onClick = { menu = true }) { Text("⇅", fontSize = 22.sp) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        Sort.values().forEach { s -> DropdownMenuItem(text = { Text(s.label + if (sort == s) "  ✓" else "") },
                            onClick = { sort = s; menu = false }) }
                        DropdownMenuItem(text = { Text(if (rev) "Reversed  ✓" else "Reverse order") }, onClick = { rev = !rev; menu = false })
                    }
                }
            })
        when {
            !granted -> Msg("Allow media access to see your files")
            all.isEmpty() -> Msg(if (video) "No videos found" else "No audio found")
            cur == null -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(fl, key = { it.dir }) { f -> FolderCard(f, f.items.count { isNew(it) }, video) { setOpen(f.dir) } } }
            else -> LazyColumn { itemsIndexed(ml) { i, m -> MediaRow(m, isNew(m)) { play(ml, i) } } }
        }
    }
}

@Composable
fun Msg(t: String) = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Text(t, color = MaterialTheme.colorScheme.onSurfaceVariant) }

@Composable
fun FolderIcon(c: Color, m: Modifier) = Canvas(m) {
    val w = size.width; val h = size.height
    drawRoundRect(c, Offset(w * .08f, h * .16f), androidx.compose.ui.geometry.Size(w * .42f, h * .24f), CornerRadius(w * .07f))
    drawRoundRect(c, Offset(w * .08f, h * .3f), androidx.compose.ui.geometry.Size(w * .84f, h * .54f), CornerRadius(w * .09f))
}

@Composable
fun Chip(t: String) = Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
    Text(t, Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSecondaryContainer) }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderCard(f: Folder, fresh: Int, video: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val n = f.items.size
    Surface(onClick = onClick, shape = RoundedCornerShape(20.dp), color = cs.surface, shadowElevation = 2.dp) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(64.dp)) {
                Box(Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)).background(cs.primaryContainer), contentAlignment = Alignment.Center) {
                    FolderIcon(cs.primary, Modifier.size(36.dp)) }
                if (fresh > 0) Box(Modifier.align(Alignment.TopEnd).size(22.dp).background(cs.tertiary, CircleShape), contentAlignment = Alignment.Center) {
                    Text("$fresh", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = cs.onTertiary) }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(f.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(f.parent, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("$n " + (if (video) "Video" else "Track") + if (n != 1) "s" else ""); Chip(fmtSize(f.size)) }
            }
        }
    }
}

@Composable
fun MediaRow(m: Media, fresh: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box {
            Thumb(m, if (m.video) Modifier.size(128.dp, 72.dp) else Modifier.size(60.dp))
            if (m.video) Text(fmt(m.dur), color = Color.White, fontSize = 11.sp, modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)
                .background(Color(0xB3000000), RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (fresh) { Box(Modifier.size(8.dp).background(cs.tertiary, CircleShape)); Spacer(Modifier.width(6.dp)) }
                Text(m.title.substringBeforeLast('.'), fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text(fmtSize(m.size) + if (!m.video) "  •  " + fmt(m.dur) else "", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Me(theme: Int, setTheme: (Int) -> Unit, openFile: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var land by remember { mutableStateOf(sp(ctx).getBoolean("land", true)) }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(colors = barColors(), title = { Text("Me", fontWeight = FontWeight.Bold) })
        Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Settings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Surface(shape = RoundedCornerShape(20.dp), color = cs.surface, shadowElevation = 2.dp) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Appearance", fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Light", "Dark", "System").forEachIndexed { i, n ->
                            FilterChip(selected = theme == i, onClick = { setTheme(i) }, label = { Text(n) }) } }
                }
            }
            Surface(shape = RoundedCornerShape(20.dp), color = cs.surface, shadowElevation = 2.dp) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text("Play videos in landscape", fontWeight = FontWeight.SemiBold)
                        Text("You can still rotate from the player", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant) }
                    Switch(checked = land, onCheckedChange = { land = it; sp(ctx).edit().putBoolean("land", it).apply() })
                }
            }
            Surface(onClick = openFile, shape = RoundedCornerShape(20.dp), color = cs.surface, shadowElevation = 2.dp) {
                Text("Open a file…", Modifier.fillMaxWidth().padding(16.dp), fontWeight = FontWeight.SemiBold) }
            Surface(shape = RoundedCornerShape(20.dp), color = cs.surface, shadowElevation = 2.dp) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("One Player", fontWeight = FontWeight.SemiBold)
                    Text("Version 1.1  •  No ads  •  No tracking", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant) }
            }
        }
    }
}

object ThumbCache : LruCache<String, Bitmap>(120)

@Composable
fun Thumb(m: Media, modifier: Modifier) {
    val ctx = LocalContext.current
    val bmp by produceState<Bitmap?>(ThumbCache.get(m.uri.toString()), m.uri) {
        if (value == null && Build.VERSION.SDK_INT >= 29) {
            value = withContext(Dispatchers.IO) {
                runCatching { ctx.contentResolver.loadThumbnail(m.uri, Size(320, 320), null) }.getOrNull()
            }?.also { ThumbCache.put(m.uri.toString(), it) }
        }
    }
    Box(modifier.clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Text(if (m.video) "▶" else "♪", color = MaterialTheme.colorScheme.primary)
    }
}
