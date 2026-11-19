package com.vibeplayer.offline

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import java.util.Locale

data class LocalVideo(
    val id: Long,
    val title: String,
    val uri: Uri,
    val durationMs: Long,
    val sizeBytes: Long
)

class MainActivity : AppCompatActivity() {
    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var listContainer: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var nowPlayingText: TextView
    private lateinit var searchInput: android.widget.EditText
    private var videos: List<LocalVideo> = emptyList()
    private var filteredVideos: List<LocalVideo> = emptyList()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted || hasVideoPermission()) loadVideos()
            else showPermissionMessage()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(9, 9, 15)
        window.navigationBarColor = android.graphics.Color.rgb(9, 9, 15)
        buildScreen()
        ensurePermissionAndLoad()
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF09090F.toInt())
            setPadding(dp(16), dp(12), dp(16), 0)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val brand = TextView(this).apply {
            text = "vibe\u25cfplayer"
            textSize = 25f
            setTextColor(0xFFF8FAFC.toInt())
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
        }
        val offline = TextView(this).apply {
            text = "  OFFLINE"
            textSize = 10f
            letterSpacing = 0.12f
            setTextColor(0xFF22D3EE.toInt())
            setPadding(dp(8), dp(5), dp(8), dp(5))
            setBackgroundColor(0xFF14252D.toInt())
        }
        header.addView(brand)
        header.addView(offline)
        root.addView(header, LinearLayout.LayoutParams(-1, dp(48)))

        playerView = PlayerView(this).apply {
            useController = true
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            setBackgroundColor(0xFF000000.toInt())
        }
        val playerFrame = FrameLayout(this).apply {
            setBackgroundColor(0xFF15151F.toInt())
            addView(playerView, FrameLayout.LayoutParams(-1, -1))
        }
        root.addView(playerFrame, LinearLayout.LayoutParams(-1, dp(218)))

        nowPlayingText = TextView(this).apply {
            text = "Choose a video to start watching"
            textSize = 15f
            setTextColor(0xFFF1F5F9.toInt())
            setPadding(0, dp(12), 0, dp(4))
            maxLines = 2
        }
        root.addView(nowPlayingText)

        val section = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val heading = TextView(this).apply {
            text = "Your library"
            textSize = 20f
            setTextColor(0xFFF8FAFC.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        statusText = TextView(this).apply {
            text = "Scanning videos…"
            textSize = 12f
            setTextColor(0xFF94A3B8.toInt())
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        section.addView(heading, LinearLayout.LayoutParams(0, dp(42), 1f))
        section.addView(statusText, LinearLayout.LayoutParams(-2, dp(42)))
        root.addView(section)

        searchInput = android.widget.EditText(this).apply {
            hint = "Search your videos"
            setHintTextColor(0xFF64748B.toInt())
            setTextColor(0xFFF8FAFC.toInt())
            textSize = 14f
            singleLine = true
            setPadding(dp(14), 0, dp(14), 0)
            setBackgroundColor(0xFF171722.toInt())
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    filterVideos(s?.toString().orEmpty())
                }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
        }
        root.addView(searchInput, LinearLayout.LayoutParams(-1, dp(46)).apply {
            topMargin = dp(8)
            bottomMargin = dp(10)
        })

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(listContainer)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun ensurePermissionAndLoad() {
        if (hasVideoPermission()) {
            loadVideos()
        } else {
            val permission = if (Build.VERSION.SDK_INT >= 33) {
                Manifest.permission.READ_MEDIA_VIDEO
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }
            permissionLauncher.launch(permission)
        }
    }

    private fun hasVideoPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED ||
                (Build.VERSION.SDK_INT >= 34 &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED)
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun showPermissionMessage() {
        statusText.text = "Permission needed"
        listContainer.removeAllViews()
        listContainer.addView(makeMessage("Allow video access to show videos stored on your phone."))
    }

    private fun loadVideos() {
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE
        )
        val found = mutableListOf<LocalVideo>()
        var cursor: Cursor? = null
        try {
            cursor = contentResolver.query(
                collection,
                projection,
                null,
                null,
                "${MediaStore.Video.Media.DATE_ADDED} DESC"
            )
            cursor?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val durationCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    found.add(
                        LocalVideo(
                            id = id,
                            title = c.getString(nameCol) ?: "Untitled video",
                            uri = ContentUris.withAppendedId(collection, id),
                            durationMs = c.getLong(durationCol),
                            sizeBytes = c.getLong(sizeCol)
                        )
                    )
                }
            }
        } catch (_: Exception) {
            statusText.text = "Could not scan videos"
        }
        videos = found
        filteredVideos = found
        renderVideos()
    }

    private fun filterVideos(query: String) {
        filteredVideos = if (query.isBlank()) videos else videos.filter {
            it.title.contains(query.trim(), ignoreCase = true)
        }
        renderVideos()
    }

    private fun renderVideos() {
        listContainer.removeAllViews()
        statusText.text = "${filteredVideos.size} videos"
        if (filteredVideos.isEmpty()) {
            val message = if (videos.isEmpty()) {
                "No videos found yet. Save a video to your phone and refresh the library."
            } else {
                "No videos match your search."
            }
            listContainer.addView(makeMessage(message))
            return
        }
        filteredVideos.forEachIndexed { index, video ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setBackgroundColor(if (index % 2 == 0) 0xFF12121B.toInt() else 0xFF0E0E16.toInt())
                isClickable = true
                isFocusable = true
                setOnClickListener { playVideo(video) }
            }
            val playIcon = TextView(this).apply {
                text = "▶"
                textSize = 17f
                gravity = Gravity.CENTER
                setTextColor(0xFF22D3EE.toInt())
                setBackgroundColor(0xFF1A2931.toInt())
            }
            row.addView(playIcon, LinearLayout.LayoutParams(dp(44), dp(44)))
            val details = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, 0, 0)
            }
            val title = TextView(this).apply {
                text = video.title
                textSize = 14f
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(0xFFF1F5F9.toInt())
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            val meta = TextView(this).apply {
                text = "${formatDuration(video.durationMs)}  •  ${formatSize(video.sizeBytes)}"
                textSize = 11f
                setTextColor(0xFF94A3B8.toInt())
                setPadding(0, dp(4), 0, 0)
            }
            details.addView(title)
            details.addView(meta)
            row.addView(details, LinearLayout.LayoutParams(0, -2, 1f))
            listContainer.addView(row, LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(6)
            })
        }
    }

    private fun playVideo(video: LocalVideo) {
        if (player == null) {
            player = ExoPlayer.Builder(this).build().also { exo ->
                playerView.player = exo
                exo.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        // Playback state is managed by Media3's built-in controller.
                    }
                })
            }
        }
        player?.apply {
            setMediaItem(MediaItem.fromUri(video.uri))
            prepare()
            playWhenReady = true
        }
        nowPlayingText.text = video.title
    }

    private fun makeMessage(message: String): TextView = TextView(this).apply {
        text = message
        textSize = 13f
        setTextColor(0xFF94A3B8.toInt())
        gravity = Gravity.CENTER
        setPadding(dp(20), dp(28), dp(20), dp(28))
    }

    private fun formatDuration(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val seconds = total % 60
        return if (hours > 0) String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
        else String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
    }

    private fun formatSize(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024) String.format(Locale.getDefault(), "%.1f GB", mb / 1024)
        else String.format(Locale.getDefault(), "%.0f MB", mb)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        playerView.player = null
        player?.release()
        player = null
        super.onDestroy()
    }
}
