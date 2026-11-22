package app.minplayer

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

// --- ডাটা ক্লাস ---
data class MediaFile(
    val uri: Uri,
    val title: String,
    val duration: Long,
    val size: Long,
    val path: String,
    val folderName: String,
    val isVideo: Boolean
) {
    fun toMediaItem(): MediaItem = MediaItem.Builder()
        .setUri(uri)
        .setMediaMetadata(androidx.media3.common.MediaMetadata.Builder().setTitle(title).build())
        .build()
}

data class FolderItem(
    val folderName: String,
    val path: String,
    val count: Int,
    val totalSize: Long,
    val isVideo: Boolean
)

// --- কুইউ এবং টাইমার ---
object Queue {
    var items: List<MediaItem> = emptyList()
    var startIndex: Int = 0
}

object SleepTimer {
    val endAt = MutableStateFlow(0L)
    private val handler = Handler(Looper.getMainLooper())
    private var player: Player? = null
    
    private val fireRunnable = Runnable { 
        player?.pause()
        endAt.value = 0L 
    }
    
    fun attach(p: Player) { player = p }
    
    fun start(minutes: Int) {
        handler.removeCallbacks(fireRunnable)
        endAt.value = SystemClock.elapsedRealtime() + minutes * 60_000L
        handler.postDelayed(fireRunnable, minutes * 60_000L)
    }
    
    fun cancel() { 
        handler.removeCallbacks(fireRunnable)
        endAt.value = 0L 
    }
}

// --- ব্যাকগ্রাউন্ড সার্ভিস ---
class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
            
        SleepTimer.attach(player)
        mediaSession = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        SleepTimer.cancel()
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }
}

// --- হেল্পার ফাংশন ---
fun formatDuration(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
    return String.format("%.1f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}

fun getRequiredPermissions(): Array<String> {
    return if (Build.VERSION.SDK_INT >= 33) {
        arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
}

fun hasPermissions(context: Context): Boolean {
    return getRequiredPermissions().filter { it != Manifest.permission.POST_NOTIFICATIONS }
        .all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
}

fun loadMedia(context: Context, isVideo: Boolean): List<MediaFile> {
    val collection = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    val projection = arrayOf(
        MediaStore.MediaColumns._ID,
        MediaStore.MediaColumns.DISPLAY_NAME,
        MediaStore.MediaColumns.DURATION,
        MediaStore.MediaColumns.SIZE,
        MediaStore.MediaColumns.DATA
    )
    
    val list = ArrayList<MediaFile>()
    context.contentResolver.query(collection, projection, null, null, MediaStore.MediaColumns.DATE_ADDED + " DESC")?.use { cursor ->
        val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
        val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
        val durCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DURATION)
        val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
        val dataCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
        
        while (cursor.moveToNext()) {
            val id = cursor.getLong(idCol)
            val name = cursor.getString(nameCol) ?: "Unknown"
            val duration = cursor.getLong(durCol)
            val size = cursor.getLong(sizeCol)
            val path = cursor.getString(dataCol) ?: ""
            val folderName = path.substringBeforeLast("/").substringAfterLast("/")
            
            list.add(MediaFile(
                uri = ContentUris.withAppendedId(collection, id),
                title = name,
                duration = duration,
                size = size,
                path = path,
                folderName = if (folderName.isNotEmpty()) folderName else "Unknown",
                isVideo = isVideo
            ))
        }
    }
    return list
}

// --- মেইন অ্যাক্টিভিটি (আপাতত টেস্ট) ---
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { 
            AppTheme { 
                Surface(color = MaterialTheme.colorScheme.background) {
                    Text("One Player Setup Complete!", modifier = Modifier.padding(16.dp))
                }
            } 
        }
    }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val darkTheme = isSystemInDarkTheme()
    val colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme()
    MaterialTheme(colorScheme = colorScheme, content = content)
}
