package com.vibeplayer.offline

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.database.Cursor
import android.media.audiofx.Equalizer
import android.net.Uri
import android.util.Size
import android.graphics.BitmapFactory
import android.media.ThumbnailUtils
import android.provider.MediaStore as VideoStoreAlias
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.Executors
import android.os.Handler
import android.os.Looper

data class LocalVideo(
    val id: Long, val title: String, val uri: Uri,
    val durationMs: Long, val sizeBytes: Long, val folder: String, val isPortrait: Boolean
)

class MainActivity : AppCompatActivity() {
    private var player: ExoPlayer? = null
    private var equalizer: Equalizer? = null
    private lateinit var playerView: PlayerView
    private lateinit var playerFrame: FrameLayout
    private lateinit var root: LinearLayout
    private lateinit var topHeader: LinearLayout
    private lateinit var bottomNav: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var nowPlayingText: TextView
    private lateinit var searchInput: EditText
    private var videos: List<LocalVideo> = emptyList()
    private var activeVideos: List<LocalVideo> = emptyList()
    private var currentTab = "Home"
    private var fullScreen = false
    private var shortsMode = false
    private var watchPageMode = false
    private var currentVideo: LocalVideo? = null
    private val playlists = linkedMapOf<String, MutableSet<Long>>()
    private val likedIds = linkedSetOf<Long>()
    private val watchLaterIds = linkedSetOf<Long>()
    private val resumePositions = mutableMapOf<Long, Long>()
    private var feedScroll: ScrollView? = null
    private val ioExecutor = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var previewUpdate: Runnable? = null
    private val thumbnailViews = linkedMapOf<Long, ImageView>()
    private val thumbnailLoading = linkedSetOf<Long>()
    private val prefs by lazy { getSharedPreferences("vibe_library", MODE_PRIVATE) }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted || hasVideoPermission()) loadVideos() else showPermissionMessage()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF09090F.toInt()
        window.navigationBarColor = 0xFF09090F.toInt()
        loadPlaylists()
        loadSavedCollections()
        buildScreen()
        ensurePermissionAndLoad()
    }

    private fun buildScreen() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF09090F.toInt())
            setPadding(dp(14), dp(8), dp(14), 0)
        }
        topHeader = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
        }
        val brand = label("vibe●player", 24, 0xFFF8FAFC.toInt(), true)
        topHeader.addView(brand, LinearLayout.LayoutParams(0, dp(48), 1f))
        val eqButton = actionButton("♫  EQ")
        eqButton.setOnClickListener { showEqualizer() }
        topHeader.addView(eqButton)
        root.addView(topHeader)

        playerView = PlayerView(this).apply {
            useController = true
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            setBackgroundColor(0xFF000000.toInt())
            controllerShowTimeoutMs = 3500
        }
        playerFrame = FrameLayout(this).apply {
            setBackgroundColor(0xFF15151F.toInt())
            addView(playerView, FrameLayout.LayoutParams(-1, -1))
        }
        val fullButton = TextView(this).apply {
            text = "⛶"
            textSize = 23f
            gravity = Gravity.CENTER
            setTextColor(-1)
            setBackgroundColor(0x99000000.toInt())
            setOnClickListener { toggleFullscreen() }
        }
        playerFrame.addView(fullButton, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.END or Gravity.TOP).apply {
            setMargins(0, dp(8), dp(8), 0)
        })
        root.addView(playerFrame, LinearLayout.LayoutParams(-1, dp(218)))

        nowPlayingText = label("Choose a video to start watching", 14, 0xFFF1F5F9.toInt(), true)
        nowPlayingText.setPadding(dp(2), dp(9), dp(2), dp(7))
        root.addView(nowPlayingText)

        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        bottomNav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(7), 0, dp(8))
            setBackgroundColor(0xFF101019.toInt())
        }
        root.addView(bottomNav, LinearLayout.LayoutParams(-1, dp(60)))
        setContentView(root)
        renderTab()
    }

    private fun renderTab() {
        content.removeAllViews()
        bottomNav.removeAllViews()
        listOf("Home", "Channels", "Playlists", "Library").forEach { tab ->
            val b = TextView(this).apply {
                text = when (tab) {
                    "Home" -> "⌂\nHome"
                    "Channels" -> "▦\nChannels"
                    "Playlists" -> "☷\nPlaylists"
                    else -> "▤\nLibrary"
                }
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(if (currentTab == tab) 0xFFB79CFF.toInt() else 0xFF9CA3AF.toInt())
                setOnClickListener { currentTab = tab; renderTab() }
            }
            bottomNav.addView(b, LinearLayout.LayoutParams(0, -1, 1f))
        }
        when (currentTab) {
            "Home" -> renderHome()
            "Channels" -> renderChannels()
            "Playlists" -> renderPlaylists()
            else -> renderLibrary()
        }
    }

    private fun renderHome() {
        playerFrame.visibility = View.GONE
        nowPlayingText.visibility = View.GONE
        val titleBar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        titleBar.addView(label("Home", 23, 0xFFF8FAFC.toInt(), true), LinearLayout.LayoutParams(0, dp(42), 1f))
        val search = actionButton("⌕ Search")
        search.setOnClickListener { showSearchDialog() }
        titleBar.addView(search)
        content.addView(titleBar)
        val scroll = ScrollView(this).apply { isFillViewport = true; overScrollMode = View.OVER_SCROLL_NEVER }
        feedScroll = scroll
        val feed = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(feed)
        content.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        thumbnailViews.clear()
        thumbnailLoading.clear()
        val longVideos = videos.filter { !it.isPortrait }
        val shorts = videos.filter { it.isPortrait }
        feed.addView(label("Videos", 20, 0xFFF8FAFC.toInt(), true).apply { setPadding(0, dp(12), 0, dp(8)) })
        if (longVideos.isEmpty()) feed.addView(message("No landscape videos found yet."))
        longVideos.take(40).forEach { feed.addView(feedVideoCard(it, false)) }
        feed.addView(label("Shorts", 22, 0xFFF8FAFC.toInt(), true).apply { setPadding(0, dp(18), 0, dp(10)) })
        if (shorts.isEmpty()) feed.addView(message("Portrait videos will appear here as Shorts."))
        shorts.take(40).chunked(2).forEach { pair ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP }
            pair.forEach { row.addView(feedVideoCard(it, true), LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(5) }) }
            if (pair.size == 1) row.addView(View(this), LinearLayout.LayoutParams(0, dp(10), 1f))
            feed.addView(row)
        }
        scroll.viewTreeObserver.addOnScrollChangedListener {
            previewUpdate?.let { mainHandler.removeCallbacks(it) }
            previewUpdate = Runnable { loadVisibleThumbnails() }
            mainHandler.postDelayed(previewUpdate!!, 450)
        }
        scroll.post { loadVisibleThumbnails() }
    }

    private fun showSearchDialog() {
        val input = EditText(this).apply { hint = "Search videos" }
        AlertDialog.Builder(this).setTitle("Search your videos").setView(input)
            .setNegativeButton("Close", null)
            .setPositiveButton("Search") { _, _ ->
                showCollection("Search results", videos.filter { it.title.contains(input.text.toString(), true) })
            }.show()
    }

    private fun feedVideoCard(video: LocalVideo, short: Boolean): View {
        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(10)) }
        val frame = FrameLayout(this).apply { setBackgroundColor(0xFF191923.toInt()) }
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0xFF252536.toInt())
            contentDescription = video.title
            tag = video.id
        }
        thumbnailViews[video.id] = image
        frame.addView(image, FrameLayout.LayoutParams(-1, -1))
        val ratio = if (short) 0.68f else 1.78f
        card.addView(frame, LinearLayout.LayoutParams(-1, if (short) dp(225) else (resources.displayMetrics.widthPixels / ratio).toInt()))
        frame.setOnClickListener { if (short) openShortsPlayer(video) else openFullPlayer(video) }
        val title = label(video.title, 14, 0xFFF8FAFC.toInt(), true).apply {
            maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(8), dp(7), dp(8), dp(2))
            setOnClickListener { if (short) openShortsPlayer(video) else openFullPlayer(video) }
        }
        card.addView(title)
        val actions = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(6), 0, dp(6), 0) }
        actions.addView(label("${formatDuration(video.durationMs)}  •  ${video.folder}", 10, 0xFF94A3B8.toInt()), LinearLayout.LayoutParams(0, dp(34), 1f))
        val like = actionButton(if (likedIds.contains(video.id)) "♥ Liked" else "♡ Like")
        like.setOnClickListener { if (!likedIds.add(video.id)) likedIds.remove(video.id); saveSavedCollections(); like.text = if (likedIds.contains(video.id)) "♥ Liked" else "♡ Like" }
        actions.addView(like)
        val later = actionButton(if (watchLaterIds.contains(video.id)) "✓ Later" else "＋ Later")
        later.setOnClickListener { if (!watchLaterIds.add(video.id)) watchLaterIds.remove(video.id); saveSavedCollections(); later.text = if (watchLaterIds.contains(video.id)) "✓ Later" else "＋ Later" }
        actions.addView(later)
        card.addView(actions)
        return card
    }

    // Feed stays decoder-free: only thumbnails for currently visible cards are loaded.
    private fun loadVisibleThumbnails() {
        if (currentTab != "Home" || isFinishing || isDestroyed) return
        val visibleRect = android.graphics.Rect()
        thumbnailViews.forEach { (id, image) ->
            val visible = image.isShown && image.getLocalVisibleRect(visibleRect)
            if (!visible) {
                image.setImageDrawable(null)
                return@forEach
            }
            if (image.drawable != null || !thumbnailLoading.add(id)) return@forEach
            val video = videos.firstOrNull { it.id == id } ?: run {
                thumbnailLoading.remove(id)
                return@forEach
            }
            ioExecutor.execute {
                var bitmap: android.graphics.Bitmap? = null
                try {
                    bitmap = if (Build.VERSION.SDK_INT >= 29) {
                        contentResolver.loadThumbnail(video.uri, Size(240, 135), null)
                    } else {
                        @Suppress("DEPRECATION")
                        ThumbnailUtils.createVideoThumbnail(video.uri.path ?: "", MediaStore.Video.Thumbnails.MINI_KIND)
                    }
                } catch (_: Exception) { }
                val result = bitmap
                mainHandler.post {
                    thumbnailLoading.remove(id)
                    if (result != null && !isFinishing && !isDestroyed &&
                        image.tag == id && image.isShown && image.getLocalVisibleRect(visibleRect)) {
                        image.setImageBitmap(result)
                    } else if (result != null && !result.isRecycled) {
                        result.recycle()
                    }
                }
            }
        }
    }

    private fun ScrollView.getLocationOnScreenY(): Int {
        val pos = IntArray(2); getLocationOnScreen(pos); return pos[1]
    }

    private fun detachPlayerView() {
        (playerView.parent as? android.view.ViewGroup)?.removeView(playerView)
    }

    private fun openFullPlayer(video: LocalVideo) {
        saveCurrentPosition()
        shortsMode = false
        watchPageMode = true
        fullScreen = false
        currentVideo = video
        ensurePlayer()
        player?.apply {
            volume = 1f
            repeatMode = Player.REPEAT_MODE_OFF
            setMediaItem(MediaItem.fromUri(video.uri))
            prepare()
            seekTo(resumePositions[video.id] ?: 0L)
            playWhenReady = true
        }
        playerView.useController = true
        playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        showWatchPage(video)
    }

    private fun showWatchPage(video: LocalVideo) {
        detachPlayerView()
        playerFrame.removeAllViews()
        playerFrame.addView(playerView, FrameLayout.LayoutParams(-1, -1))
        playerFrame.visibility = View.VISIBLE
        root.removeAllViews()
        root.setPadding(0, 0, 0, 0)
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFF09090F.toInt()) }
        val videoHeight = (resources.displayMetrics.widthPixels * 9f / 16f).toInt()
        page.addView(playerFrame, LinearLayout.LayoutParams(-1, videoHeight))
        val scroll = ScrollView(this)
        val details = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(18)) }
        details.addView(label(video.title, 19, 0xFFF8FAFC.toInt(), true))
        details.addView(label(formatDuration(video.durationMs) + "  •  " + video.folder + "  •  " + formatSize(video.sizeBytes), 12, 0xFF9CA3AF.toInt()).apply { setPadding(0, dp(7), 0, dp(12)) })
        val channelRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        channelRow.addView(label("●", 24, 0xFFB79CFF.toInt(), true))
        channelRow.addView(label("  Local videos  •  VibePlayer", 14, 0xFFE5E7EB.toInt(), true), LinearLayout.LayoutParams(0, dp(42), 1f))
        details.addView(channelRow)
        val actions = LinearLayout(this).apply { gravity = Gravity.CENTER; orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, dp(10)) }
        val like = actionButton(if (likedIds.contains(video.id)) "♥ Liked" else "♡ Like")
        like.setOnClickListener { if (!likedIds.add(video.id)) likedIds.remove(video.id); saveSavedCollections(); like.text = if (likedIds.contains(video.id)) "♥ Liked" else "♡ Like" }
        val later = actionButton(if (watchLaterIds.contains(video.id)) "✓ Saved" else "＋ Watch later")
        later.setOnClickListener { if (!watchLaterIds.add(video.id)) watchLaterIds.remove(video.id); saveSavedCollections(); later.text = if (watchLaterIds.contains(video.id)) "✓ Saved" else "＋ Watch later" }
        val playlist = actionButton("＋ Playlist")
        playlist.setOnClickListener { addToPlaylist(video) }
        actions.addView(like, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(5) })
        actions.addView(later, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(5) })
        actions.addView(playlist, LinearLayout.LayoutParams(0, dp(44), 1f))
        details.addView(actions)
        details.addView(label("Comments", 18, 0xFFF8FAFC.toInt(), true).apply { setPadding(0, dp(16), 0, dp(8)) })
        details.addView(label("Comments are for your local viewing notes. This video is stored on your device.", 13, 0xFF9CA3AF.toInt()).apply { setPadding(dp(12), dp(14), dp(12), dp(14)); setBackgroundColor(0xFF171722.toInt()) })
        details.addView(label("Up next", 19, 0xFFF8FAFC.toInt(), true).apply { setPadding(0, dp(18), 0, dp(8)) })
        videos.filter { it.id != video.id }.take(20).forEach { related ->
            details.addView(videoRow(related, onVideoClick = { openFullPlayer(related) }))
        }
        scroll.addView(details)
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val back = TextView(this).apply { text = "‹"; textSize = 30f; gravity = Gravity.CENTER; setTextColor(-1); setBackgroundColor(0x99000000.toInt()); setOnClickListener { closeWatchPage() } }
        playerFrame.addView(back, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.START or Gravity.TOP))
        root.addView(page, LinearLayout.LayoutParams(-1, -1))
    }

    private fun openShortsPlayer(video: LocalVideo) {
        saveCurrentPosition()
        shortsMode = true
        watchPageMode = false
        fullScreen = true
        currentVideo = video
        ensurePlayer()
        player?.apply { volume = 1f; repeatMode = Player.REPEAT_MODE_ONE; setMediaItem(MediaItem.fromUri(video.uri)); prepare(); seekTo(resumePositions[video.id] ?: 0L); playWhenReady = true }
        playerView.useController = true
        playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        detachPlayerView()
        playerFrame.removeAllViews()
        playerFrame.addView(playerView, FrameLayout.LayoutParams(-1, -1))
        root.removeAllViews()
        root.setPadding(0, 0, 0, 0)
        root.addView(playerFrame, LinearLayout.LayoutParams(-1, -1))
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN)
        val back = TextView(this).apply { text = "‹"; textSize = 30f; gravity = Gravity.CENTER; setTextColor(-1); setBackgroundColor(0x66000000); setOnClickListener { closeWatchPage() } }
        playerFrame.addView(back, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.START or Gravity.TOP))
    }

    private fun closeWatchPage() {
        saveCurrentPosition()
        player?.pause()
        shortsMode = false; watchPageMode = false; fullScreen = false
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        detachPlayerView()
        playerFrame.removeAllViews()
        playerFrame.addView(playerView, FrameLayout.LayoutParams(-1, -1))
        root.removeAllViews()
        root.setPadding(dp(14), dp(8), dp(14), 0)
        root.addView(topHeader)
        playerFrame.visibility = View.GONE
        root.addView(playerFrame, LinearLayout.LayoutParams(-1, dp(218)))
        nowPlayingText.visibility = View.GONE
        root.addView(nowPlayingText)
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(bottomNav, LinearLayout.LayoutParams(-1, dp(60)))
        currentTab = "Home"
        renderTab()
    }

    private fun loadSavedCollections() {
        likedIds.clear(); watchLaterIds.clear()
        try { JSONArray(prefs.getString("liked_ids", "[]")).let { a -> for (i in 0 until a.length()) likedIds.add(a.getLong(i)) } } catch (_: Exception) {}
        try { JSONArray(prefs.getString("later_ids", "[]")).let { a -> for (i in 0 until a.length()) watchLaterIds.add(a.getLong(i)) } } catch (_: Exception) {}
        try {
            val obj = JSONObject(prefs.getString("resume_positions", "{}"))
            obj.keys().forEach { key -> resumePositions[key.toLong()] = obj.getLong(key) }
        } catch (_: Exception) {}
    }

    private fun saveSavedCollections() {
        val liked = JSONArray(); likedIds.forEach { liked.put(it) }
        val later = JSONArray(); watchLaterIds.forEach { later.put(it) }
        val resume = JSONObject(); resumePositions.forEach { (id, pos) -> resume.put(id.toString(), pos) }
        prefs.edit().putString("liked_ids", liked.toString()).putString("later_ids", later.toString())
            .putString("resume_positions", resume.toString()).apply()
    }

    private fun saveCurrentPosition() {
        val video = currentVideo ?: return
        val p = player ?: return
        if (p.currentPosition > 0) resumePositions[video.id] = p.currentPosition
        saveSavedCollections()
    }

    private fun renderVideoRows(items: List<LocalVideo>) {
        val rows = content.findViewWithTag<LinearLayout>("video_rows") ?: return
        rows.removeAllViews()
        if (::statusText.isInitialized) statusText.text = "${items.size} videos"
        if (items.isEmpty()) {
            rows.addView(message(if (videos.isEmpty()) "No videos found. Add videos to your phone and refresh." else "No matching videos found."))
            return
        }
        items.forEach { video -> rows.addView(videoRow(video)) }
    }

    private fun renderChannels() {
        content.addView(label("Your channels", 21, 0xFFF8FAFC.toInt(), true))
        content.addView(label("Folders from your phone, organized like channels", 12, 0xFF94A3B8.toInt()))
        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)
        content.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val groups = videos.groupBy { it.folder }.toSortedMap()
        if (groups.isEmpty()) list.addView(message("Your folders will appear here when videos are available."))
        groups.forEach { (folder, items) ->
            val card = cardRow("▣", folder, "${items.size} videos  •  Open channel") {
                showCollection("Channel · $folder", items)
            }
            list.addView(card)
        }
    }

    private fun renderPlaylists() {
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(label("Your playlists", 21, 0xFFF8FAFC.toInt(), true), LinearLayout.LayoutParams(0, dp(48), 1f))
        val create = actionButton("+ New")
        create.setOnClickListener { createPlaylist() }
        header.addView(create)
        content.addView(header)
        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)
        content.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        if (playlists.isEmpty()) list.addView(message("Create playlists for workouts, movies, music videos or anything you like."))
        playlists.forEach { (name, ids) ->
            val items = videos.filter { ids.contains(it.id) }
            list.addView(cardRow("♫", name, "${items.size} saved videos") {
                showCollection("Playlist · $name", items)
            })
        }
    }

    private fun renderLibrary() {
        content.addView(label("Library", 21, 0xFFF8FAFC.toInt(), true))
        content.addView(label("All videos stored on this device", 12, 0xFF94A3B8.toInt()))
        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)
        content.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        videos.forEach { list.addView(videoRow(it)) }
        if (videos.isEmpty()) list.addView(message("No videos found. Allow video access to scan your library."))
    }

    private fun showCollection(title: String, items: List<LocalVideo>) {
        content.removeAllViews()
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val back = actionButton("‹ Back")
        back.setOnClickListener { renderTab() }
        header.addView(back)
        header.addView(label(title, 18, 0xFFF8FAFC.toInt(), true), LinearLayout.LayoutParams(0, dp(48), 1f))
        val playAll = actionButton("▶ Play all")
        playAll.setOnClickListener { playCollection(items) }
        header.addView(playAll)
        content.addView(header)
        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)
        content.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        items.forEach { list.addView(videoRow(it)) }
        if (items.isEmpty()) list.addView(message("This collection is empty. Add videos from the Library menu."))
    }

    private fun videoRow(video: LocalVideo, onVideoClick: (() -> Unit)? = null): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(9), dp(5), dp(9))
            setBackgroundColor(0xFF13131D.toInt())
            isClickable = true
            setOnClickListener { (onVideoClick ?: { openFullPlayer(video) }).invoke() }
        }
        val thumb = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0xFF28223B.toInt())
            contentDescription = "Thumbnail: ${video.title}"
            tag = video.id
        }
        row.addView(thumb, LinearLayout.LayoutParams(dp(112), dp(72)))
        loadRowThumbnail(thumb, video)
        val details = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, dp(4), 0) }
        details.addView(label(video.title, 14, 0xFFF1F5F9.toInt(), true).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
        details.addView(label("${video.folder}  •  ${formatDuration(video.durationMs)}  •  ${formatSize(video.sizeBytes)}", 10, 0xFF94A3B8.toInt()))
        row.addView(details, LinearLayout.LayoutParams(0, -2, 1f))
        val menu = TextView(this).apply {
            text = "⋮"
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(0xFFCBD5E1.toInt())
            setOnClickListener { showVideoMenu(this, video) }
        }
        row.addView(menu, LinearLayout.LayoutParams(dp(38), dp(72)))
        return row.apply {
            val lp = LinearLayout.LayoutParams(-1, -2)
            lp.bottomMargin = dp(6)
            layoutParams = lp
        }
    }

    private fun loadRowThumbnail(image: ImageView, video: LocalVideo) {
        ioExecutor.execute {
            try {
                val bitmap = if (Build.VERSION.SDK_INT >= 29) {
                    contentResolver.loadThumbnail(video.uri, Size(240, 135), null)
                } else {
                    @Suppress("DEPRECATION")
                    ThumbnailUtils.createVideoThumbnail(video.uri.path ?: "", MediaStore.Video.Thumbnails.MINI_KIND)
                }
                mainHandler.post {
                    if (!isFinishing && !isDestroyed && image.tag == video.id && bitmap != null) {
                        image.setImageBitmap(bitmap)
                    }
                }
            } catch (_: Exception) {
                mainHandler.post {
                    if (!isFinishing && !isDestroyed && image.tag == video.id) {
                        image.setImageResource(android.R.drawable.ic_media_play)
                        image.scaleType = ImageView.ScaleType.CENTER
                    }
                }
            }
        }
    }

    private fun showVideoMenu(anchor: View, video: LocalVideo) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add("Play")
        popup.menu.add(if (likedIds.contains(video.id)) "Unlike" else "Like")
        popup.menu.add(if (watchLaterIds.contains(video.id)) "Remove from Watch Later" else "Watch later")
        popup.menu.add("Add to playlist")
        popup.menu.add("Play next")
        popup.setOnMenuItemClickListener {
            when (it.title.toString()) {
                "Play" -> playVideo(video)
                "Like", "Unlike" -> { if (!likedIds.add(video.id)) likedIds.remove(video.id); saveSavedCollections() }
                "Watch later", "Remove from Watch Later" -> { if (!watchLaterIds.add(video.id)) watchLaterIds.remove(video.id); saveSavedCollections() }
                "Watch later" -> { if (!watchLaterIds.add(video.id)) watchLaterIds.remove(video.id); saveSavedCollections() }
                "Add to playlist" -> addToPlaylist(video)
                "Play next" -> {
                    ensurePlayer()
                    player?.addMediaItem(0, MediaItem.fromUri(video.uri))
                }
            }
            true
        }
        popup.show()
    }

    private fun createPlaylist() {
        val input = EditText(this).apply { hint = "e.g. Favourites, Movies, Workout" }
        AlertDialog.Builder(this).setTitle("Create playlist").setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty() && !playlists.containsKey(name)) {
                    playlists[name] = linkedSetOf()
                    savePlaylists()
                    currentVideo?.let { playlists[name]?.add(it.id); savePlaylists() }
                    renderTab()
                }
            }.show()
    }

    private fun addToPlaylist(video: LocalVideo) {
        if (playlists.isEmpty()) {
            AlertDialog.Builder(this).setMessage("Create your first playlist?")
                .setNegativeButton("Cancel", null).setPositiveButton("Create") { _, _ ->
                    currentVideo = video
                    createPlaylist()
                }.show()
            return
        }
        val names = playlists.keys.toTypedArray()
        AlertDialog.Builder(this).setTitle("Add to playlist").setItems(names) { _, which ->
            playlists[names[which]]?.add(video.id)
            savePlaylists()
            Toast.makeText(this, "Added to ${names[which]}", Toast.LENGTH_SHORT).show()
        }.setPositiveButton("New playlist") { _, _ -> currentVideo = video; createPlaylist() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun loadPlaylists() {
        try {
            val json = JSONArray(prefs.getString("playlists", "[]"))
            for (i in 0 until json.length()) {
                val item = json.getJSONObject(i)
                val ids = item.getJSONArray("ids")
                val set = linkedSetOf<Long>()
                for (j in 0 until ids.length()) set.add(ids.getLong(j))
                playlists[item.getString("name")] = set
            }
        } catch (_: Exception) { }
    }

    private fun savePlaylists() {
        val json = JSONArray()
        playlists.forEach { (name, ids) ->
            val item = JSONObject().put("name", name)
            val arr = JSONArray()
            ids.forEach { arr.put(it) }
            item.put("ids", arr)
            json.put(item)
        }
        prefs.edit().putString("playlists", json.toString()).apply()
    }

    private fun ensurePlayer() {
        if (player != null) return
        val renderersFactory = DefaultRenderersFactory(this).setEnableDecoderFallback(true)
        player = ExoPlayer.Builder(this, renderersFactory).build().also { exo ->
            playerView.player = exo
            exo.addListener(object : Player.Listener {
                override fun onAudioSessionIdChanged(audioSessionId: Int) { attachEqualizer(audioSessionId) }
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    mainHandler.post {
                        player?.playWhenReady = false
                        saveCurrentPosition()
                        Toast.makeText(this@MainActivity, "Playback stopped: ${error.errorCodeName}. Try another video or format.", Toast.LENGTH_LONG).show()
                    }
                }
            })
        }
    }

    private fun playVideo(video: LocalVideo) {
        if (watchPageMode || shortsMode) {
            if (video.isPortrait) openShortsPlayer(video) else openFullPlayer(video)
            return
        }
        openFullPlayer(video)
    }

    private fun playCollection(items: List<LocalVideo>) {
        if (items.isEmpty()) return
        ensurePlayer()
        currentVideo = items.first()
        player?.apply {
            setMediaItems(items.map { MediaItem.fromUri(it.uri) })
            prepare()
            playWhenReady = true
        }
        nowPlayingText.text = "Playing collection  •  ${items.first().title}"
    }

    private fun toggleFullscreen() {
        if (shortsMode) return
        fullScreen = !fullScreen
        if (fullScreen) {
            topHeader.visibility = View.GONE
            nowPlayingText.visibility = View.GONE
            content.visibility = View.GONE
            bottomNav.visibility = View.GONE
            playerFrame.layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
            window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN)
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            topHeader.visibility = View.VISIBLE
            nowPlayingText.visibility = View.VISIBLE
            content.visibility = View.VISIBLE
            bottomNav.visibility = View.VISIBLE
            playerFrame.layoutParams = LinearLayout.LayoutParams(-1, dp(218))
            window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    private fun showEqualizer() {
        ensurePlayer()
        val session = player?.audioSessionId ?: 0
        if (session != 0) attachEqualizer(session)
        val eq = equalizer
        if (eq == null) {
            Toast.makeText(this, "Equalizer is not supported by this device", Toast.LENGTH_LONG).show()
            return
        }
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(10), dp(18), dp(4)) }
        val presets = (0 until eq.numberOfPresets).map { eq.getPresetName(it.toShort()) }
        val spinner = Spinner(this)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("Custom") + presets)
        panel.addView(label("Sound preset", 13, 0xFFE2E8F0.toInt(), true))
        panel.addView(spinner)
        val bandLabels = mutableListOf<TextView>()
        for (band in 0 until eq.numberOfBands) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val range = eq.bandLevelRange
            val label = label("${eq.getCenterFreq(band.toShort()) / 1000} kHz", 12, 0xFFE2E8F0.toInt())
            bandLabels.add(label)
            row.addView(label)
            val seek = SeekBar(this).apply {
                max = (range[1] - range[0]).toInt()
                progress = (eq.getBandLevel(band.toShort()) - range[0]).toInt()
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                        if (fromUser) try { eq.setBandLevel(band.toShort(), (p + range[0]).toShort()); spinner.setSelection(0) } catch (_: Exception) { }
                    }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
            }
            row.addView(seek)
            panel.addView(row)
        }
        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position > 0) try { eq.usePreset((position - 1).toShort()) } catch (_: Exception) { }
            }
        }
        AlertDialog.Builder(this).setTitle("Vibe Equalizer").setView(panel)
            .setPositiveButton("Done", null).setNeutralButton("Off") { _, _ -> try { eq.enabled = false } catch (_: Exception) { } }.show()
    }

    private fun attachEqualizer(sessionId: Int) {
        try {
            equalizer?.release()
            equalizer = Equalizer(0, sessionId).apply { enabled = true }
        } catch (_: Exception) { equalizer = null }
    }

    private fun ensurePermissionAndLoad() {
        if (hasVideoPermission()) loadVideos() else {
            val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE
            permissionLauncher.launch(permission)
        }
    }

    private fun hasVideoPermission(): Boolean = if (Build.VERSION.SDK_INT >= 33) {
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED ||
            (Build.VERSION.SDK_INT >= 34 && ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED)
    } else ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun showPermissionMessage() {
        content.removeAllViews()
        content.addView(message("Allow video access to show videos stored on your phone."))
    }

    private fun loadVideos() {
        content.removeAllViews()
        content.addView(message("Scanning your video library…"))
        ioExecutor.execute {
            val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.RELATIVE_PATH,
                MediaStore.Video.Media.WIDTH,
                MediaStore.Video.Media.HEIGHT
            )
            val found = mutableListOf<LocalVideo>()
            try {
                contentResolver.query(collection, projection, null, null,
                    "${MediaStore.Video.Media.DATE_ADDED} DESC")?.use { c ->
                    val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                    val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                    val durationCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                    val sizeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                    val pathCol = c.getColumnIndex(MediaStore.Video.Media.RELATIVE_PATH)
                    val widthCol = c.getColumnIndex(MediaStore.Video.Media.WIDTH)
                    val heightCol = c.getColumnIndex(MediaStore.Video.Media.HEIGHT)
                    while (c.moveToNext()) {
                        val id = c.getLong(idCol)
                        val path = if (pathCol >= 0) c.getString(pathCol).orEmpty().trimEnd('/') else "Videos"
                        val folder = path.substringAfterLast('/').ifBlank { "Videos" }
                        val width = if (widthCol >= 0) c.getInt(widthCol) else 0
                        val height = if (heightCol >= 0) c.getInt(heightCol) else 0
                        found.add(LocalVideo(
                            id, c.getString(nameCol) ?: "Untitled video",
                            ContentUris.withAppendedId(collection, id),
                            c.getLong(durationCol), c.getLong(sizeCol), folder,
                            height > width && width > 0
                        ))
                    }
                }
            } catch (_: Exception) { }
            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                videos = found
                renderTab()
                if (found.isEmpty()) Toast.makeText(this, "No videos found. Check video permission and device storage.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun cardRow(icon: String, title: String, subtitle: String, click: () -> Unit): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setBackgroundColor(0xFF171722.toInt())
            val symbol = label(icon, 23, 0xFFB79CFF.toInt(), true)
            addView(symbol, LinearLayout.LayoutParams(dp(48), dp(48)))
            val textBox = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            textBox.addView(label(title, 15, 0xFFF8FAFC.toInt(), true))
            textBox.addView(label(subtitle, 11, 0xFF94A3B8.toInt()))
            addView(textBox, LinearLayout.LayoutParams(0, -2, 1f))
            setOnClickListener { click() }
        }.apply { layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = dp(8) } }
    }

    private fun actionButton(text: String) = TextView(this).apply {
        this.text = text
        textSize = 12f
        gravity = Gravity.CENTER
        setTextColor(0xFFEDE9FE.toInt())
        setPadding(dp(12), dp(8), dp(12), dp(8))
        setBackgroundColor(0xFF29213D.toInt())
    }

    private fun label(text: String, size: Int, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = size.toFloat()
        setTextColor(color)
        if (bold) typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    private fun message(text: String) = TextView(this).apply {
        this.text = text
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

    @Deprecated("Deprecated in Android API")
    override fun onBackPressed() {
        if (watchPageMode || shortsMode) { closeWatchPage(); return }
        super.onBackPressed()
    }

    override fun onPause() {
        saveCurrentPosition()
        super.onPause()
        if (!fullScreen) player?.pause()
    }

    override fun onStop() {
        saveCurrentPosition()
        super.onStop()
        if (!fullScreen) player?.pause()
    }

    override fun onDestroy() {
        playerView.player = null
        player?.release()
        player = null
        equalizer?.release()
        equalizer = null
        ioExecutor.shutdownNow()
        previewUpdate?.let { mainHandler.removeCallbacks(it) }
        super.onDestroy()
    }
}
