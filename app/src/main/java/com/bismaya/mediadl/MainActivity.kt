package com.bismaya.mediadl

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.HapticFeedbackConstants
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.bismaya.mediadl.ui.theme.*
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// --- Constants ------------------------------------------------------------------
private const val CHANNEL_ID = "mediadl_downloads"
private const val NOTIF_ID_PROGRESS = 1001
private const val NOTIF_ID_COMPLETE = 1002
internal const val PREFS_NAME = "mediadl_prefs"

// --- Filter Enums ---------------------------------------------------------------
enum class MediaFilter(val label: String) { ALL("All"), VIDEO("Video"), AUDIO("Audio") }
enum class DateFilter(val label: String) { ALL("All Time"), TODAY("Today"), WEEK("This Week"), MONTH("This Month") }

// --- Navigation Tab Enum --------------------------------------------------------
enum class NavTab(val label: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    HOME("Home", Icons.Outlined.Home, Icons.Rounded.Home),
    DOWNLOADS("Downloads", Icons.Outlined.Download, Icons.Rounded.Download),
    TORRENT("Torrent", Icons.Outlined.CloudDownload, Icons.Rounded.CloudDownload),
    HISTORY("History", Icons.Outlined.History, Icons.Rounded.History),
    ABOUT("About", Icons.Outlined.Info, Icons.Rounded.Info)
}

// --- Input Type Detection (SmartBar) -------------------------------------------
enum class InputType { EMPTY, URL, MAGNET, TORRENT_URL, SEARCH }

fun detectInputType(input: String): InputType {
    val t = input.trim()
    return when {
        t.isBlank() -> InputType.EMPTY
        t.startsWith("magnet:?", ignoreCase = true) || t.startsWith("magnet:", ignoreCase = true) -> InputType.MAGNET
        t.matches(Regex("https?://.*\\.torrent(\\?.*)?$", RegexOption.IGNORE_CASE)) -> InputType.TORRENT_URL
        t.startsWith("http://") || t.startsWith("https://") -> InputType.URL
        else -> InputType.SEARCH
    }
}

// --- Data Classes ---------------------------------------------------------------
data class VideoInfo(
    val title: String,
    val uploader: String,
    val duration: String,
    val thumbnail: String,
    val viewCount: String,
    val formats: List<FormatOption>,
    val url: String
)

data class FormatOption(
    val formatId: String,
    val label: String,
    val type: String,
    val qualityTag: String,
    val height: Int = 0
)

// --- Platform Detection ---------------------------------------------------------
fun detectPlatform(url: String): String? {
    val patterns = mapOf(
        "YouTube Music" to Regex("music\\.youtube\\.com", RegexOption.IGNORE_CASE),
        "YouTube" to Regex("youtube\\.com|youtu\\.be", RegexOption.IGNORE_CASE),
        "Instagram" to Regex("instagram\\.com", RegexOption.IGNORE_CASE),
        "Facebook" to Regex("facebook\\.com|fb\\.watch", RegexOption.IGNORE_CASE),
        "TikTok" to Regex("tiktok\\.com", RegexOption.IGNORE_CASE),
        "Twitter/X" to Regex("twitter\\.com|x\\.com", RegexOption.IGNORE_CASE),
        "Vimeo" to Regex("vimeo\\.com", RegexOption.IGNORE_CASE),
        "Reddit" to Regex("reddit\\.com", RegexOption.IGNORE_CASE),
        "Twitch" to Regex("twitch\\.tv", RegexOption.IGNORE_CASE),
        "SoundCloud" to Regex("soundcloud\\.com", RegexOption.IGNORE_CASE),
        "Dailymotion" to Regex("dailymotion\\.com", RegexOption.IGNORE_CASE)
    )
    for ((name, regex) in patterns) {
        if (regex.containsMatchIn(url)) return name
    }
    return if (url.startsWith("http")) "Web" else null
}

fun getQualityTag(height: Int): String = when {
    height >= 4320 -> "8K"
    height >= 2160 -> "4K"
    height >= 1440 -> "2K"
    height >= 1080 -> "FHD"
    height >= 720 -> "HD"
    height >= 480 -> "SD"
    height >= 360 -> "360p"
    else -> "Low"
}

fun formatNumber(n: Long): String = when {
    n >= 1_000_000 -> "${"%.1f".format(n / 1_000_000.0)}M"
    n >= 1_000 -> "${"%.1f".format(n / 1_000.0)}K"
    else -> n.toString()
}

// --- Notification Helpers -------------------------------------------------------
fun createNotificationChannel(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val channel = NotificationChannel(
            CHANNEL_ID, "Downloads",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Download progress & completion" }
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.createNotificationChannel(channel)
    }
}

fun showProgressNotification(context: Context, title: String, progress: Int) {
    if (Build.VERSION.SDK_INT >= 33 &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
        != PackageManager.PERMISSION_GRANTED
    ) return
    val builder = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setContentTitle("Downloading")
        .setContentText(title)
        .setProgress(100, progress, false)
        .setOngoing(true)
        .setSilent(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
    @Suppress("MissingPermission")
    NotificationManagerCompat.from(context).notify(NOTIF_ID_PROGRESS, builder.build())
}

fun showCompleteNotification(
    context: Context,
    title: String,
    contentUri: Uri? = null,
    mimeType: String? = null
) {
    NotificationManagerCompat.from(context).cancel(NOTIF_ID_PROGRESS)
    if (Build.VERSION.SDK_INT >= 33 &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
        != PackageManager.PERMISSION_GRANTED
    ) return
    val builder = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_download_done)
        .setContentTitle("Download Complete")
        .setContentText(title)
        .setAutoCancel(true)
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)

    // Tap notification → play the downloaded media
    if (contentUri != null) {
        val playIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, mimeType ?: "video/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, playIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        builder.setContentIntent(pendingIntent)
    }

    @Suppress("MissingPermission")
    NotificationManagerCompat.from(context).notify(NOTIF_ID_COMPLETE, builder.build())
}

fun cancelProgressNotification(context: Context) {
    NotificationManagerCompat.from(context).cancel(NOTIF_ID_PROGRESS)
}

// --- Save to MediaStore (Movies / Music system folders) -------------------------
fun saveToMediaStore(
    context: Context,
    tempFile: File,
    isAudio: Boolean
): Uri? {
    val resolver = context.contentResolver

    val mimeType = if (isAudio) {
        when (tempFile.extension.lowercase()) {
            "mp3" -> "audio/mpeg"
            "m4a", "aac" -> "audio/mp4"
            "ogg", "opus" -> "audio/ogg"
            "flac" -> "audio/flac"
            "wav" -> "audio/wav"
            "webm" -> "audio/webm"
            else -> "audio/*"
        }
    } else {
        when (tempFile.extension.lowercase()) {
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            "3gp" -> "video/3gpp"
            else -> "video/*"
        }
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        // API 29+: Use MediaStore with RELATIVE_PATH
        val collection = if (isAudio) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }

        val relativePath = if (isAudio) {
            "${Environment.DIRECTORY_MUSIC}/MediaDL"
        } else {
            "${Environment.DIRECTORY_MOVIES}/MediaDL"
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, tempFile.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val uri = resolver.insert(collection, values) ?: return null

        try {
            resolver.openOutputStream(uri)?.use { out ->
                tempFile.inputStream().use { inp -> inp.copyTo(out) }
            }
            // Mark as complete
            val updateValues = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            resolver.update(uri, updateValues, null, null)
            return uri
        } catch (e: Exception) {
            // Clean up on failure
            resolver.delete(uri, null, null)
            return null
        }
    } else {
        // API < 29: Save to public directory + scan with MediaScanner
        @Suppress("DEPRECATION")
        val baseDir = if (isAudio) {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        } else {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        }
        val dir = File(baseDir, "MediaDL")
        if (!dir.exists()) dir.mkdirs()

        val dest = File(dir, tempFile.name)
        tempFile.inputStream().use { inp ->
            dest.outputStream().use { out -> inp.copyTo(out) }
        }

        // Scan to register in MediaStore and get content URI
        var resultUri: Uri? = null
        val latch = CountDownLatch(1)
        MediaScannerConnection.scanFile(
            context,
            arrayOf(dest.absolutePath),
            arrayOf(mimeType)
        ) { _, uri ->
            resultUri = uri
            latch.countDown()
        }
        latch.await(5, TimeUnit.SECONDS)
        return resultUri
    }
}

// Delete a download from MediaStore (removes file from the system)
fun deleteFromMediaStore(context: Context, contentUriString: String): Boolean {
    if (contentUriString.isBlank()) return false
    return try {
        val uri = Uri.parse(contentUriString)
        context.contentResolver.delete(uri, null, null) > 0
    } catch (_: Exception) {
        false
    }
}

// Play media file using system player
fun playMedia(context: Context, record: DownloadRecord) {
    if (record.contentUri.isBlank()) return
    val uri = Uri.parse(record.contentUri)
    val mimeType = if (record.type == "audio") "audio/*" else "video/*"
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mimeType)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(intent)
    } catch (_: Exception) {
        // No app available to play this media
    }
}

// --- Activity -------------------------------------------------------------------
class MainActivity : ComponentActivity() {

    // Holds a URL shared into the app from the system share sheet.
    private var sharedUrl by mutableStateOf<String?>(null)
    // Holds a magnet URI received via intent (deep link or share)
    private var sharedMagnet by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        processIncomingIntent(intent)
        createNotificationChannel(this)
        enableEdgeToEdge()
        setContent {
            MediaDLTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Ink) {
                    MediaDLApp(
                        sharedUrl = sharedUrl,
                        onSharedUrlConsumed = { sharedUrl = null },
                        sharedMagnet = sharedMagnet,
                        onSharedMagnetConsumed = { sharedMagnet = null }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        processIncomingIntent(intent)
    }

    private fun processIncomingIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            Intent.ACTION_SEND -> {
                if (intent.type == "text/plain") {
                    val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
                    val trimmed = text.trim()
                    if (trimmed.startsWith("magnet:", ignoreCase = true)) {
                        sharedMagnet = trimmed
                    } else {
                        Regex("""https?://\S+""").find(trimmed)?.value?.let { sharedUrl = it }
                    }
                }
            }
            Intent.ACTION_VIEW -> {
                val uri = intent.data ?: return
                when {
                    uri.scheme.equals("magnet", ignoreCase = true) -> {
                        sharedMagnet = uri.toString()
                    }
                    intent.type == "application/x-bittorrent" ||
                    uri.toString().endsWith(".torrent", ignoreCase = true) -> {
                        sharedMagnet = uri.toString()
                    }
                    else -> {
                        sharedUrl = uri.toString()
                    }
                }
            }
        }
    }
}

// --- Main App Composable --------------------------------------------------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaDLApp(
    vm: MainViewModel = viewModel(),
    sharedUrl: String? = null,
    onSharedUrlConsumed: () -> Unit = {},
    sharedMagnet: String? = null,
    onSharedMagnetConsumed: () -> Unit = {}
) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()

    // Notification permission (Android 13+)
    val notifPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted or not - we handle gracefully */ }

    // Storage permission for API < 29
    val storagePermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted or not */ }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (Build.VERSION.SDK_INT < 29) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                storagePermLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
    }

    // ── Haptic feedback observer ──
    val haptic = vm.hapticEvent
    LaunchedEffect(haptic) {
        if (haptic != null) {
            val feedbackConstant = when (haptic) {
                MainViewModel.HapticType.LIGHT -> HapticFeedbackConstants.CLOCK_TICK
                MainViewModel.HapticType.SUCCESS -> HapticFeedbackConstants.CONFIRM
                MainViewModel.HapticType.ERROR -> HapticFeedbackConstants.REJECT
            }
            @Suppress("NewApi")
            try { view.performHapticFeedback(feedbackConstant) } catch (_: Exception) {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }
            vm.consumeHaptic()
        }
    }

    // ── Handle incoming shared URLs (from Android share sheet) ──
    LaunchedEffect(sharedUrl) {
        val url = sharedUrl ?: return@LaunchedEffect
        // Navigate to HOME tab
        vm.currentNavTab = NavTab.HOME
        // Populate the URL field and auto-fetch
        vm.onUrlChange(url)
        vm.fetch()
        onSharedUrlConsumed()
    }

    // ── Pager State synced with NavTab ──
    val pagerState = rememberPagerState(
        initialPage = vm.currentNavTab.ordinal,
        pageCount = { NavTab.entries.size }
    )

    // Sync pager → NavTab (only when page fully settles, avoids intermediate page conflicts)
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            val tab = NavTab.entries.getOrNull(page) ?: NavTab.HOME
            if (vm.currentNavTab != tab) vm.currentNavTab = tab
        }
    }

    // Sync NavTab → pager (when tapped from bottom bar)
    LaunchedEffect(vm.currentNavTab) {
        val target = vm.currentNavTab.ordinal
        if (pagerState.currentPage != target) {
            pagerState.animateScrollToPage(target)
        }
    }

    val torrentVm: TorrentViewModel = viewModel()

    // Handle shared magnet link (from deep link or share)
    LaunchedEffect(sharedMagnet) {
        val magnet = sharedMagnet ?: return@LaunchedEffect
        vm.currentNavTab = NavTab.TORRENT
        if (magnet.startsWith("magnet:", ignoreCase = true)) {
            torrentVm.onMagnetInputChange(magnet)
            torrentVm.addMagnet()
        } else {
            torrentVm.addMagnetDirect(magnet)
        }
        onSharedMagnetConsumed()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AuroraBackground()

        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = {
                BottomNavBar(
                    currentTab = vm.currentNavTab,
                    onTabSelect = { vm.currentNavTab = it },
                    downloadCount = vm.visibleDownloadsCount()
                )
            }
        ) { paddingValues ->
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                beyondViewportPageCount = 0
            ) { page ->
                when (NavTab.entries[page]) {
                    NavTab.HOME -> HomeScreen(
                        vm = vm,
                        torrentVm = torrentVm,
                        onNavigateToTorrent = { vm.currentNavTab = NavTab.TORRENT },
                        modifier = Modifier.padding(paddingValues)
                    )
                    NavTab.DOWNLOADS -> DownloadsScreen(vm = vm, modifier = Modifier.padding(paddingValues))
                    NavTab.TORRENT -> TorrentScreen(vm = torrentVm, modifier = Modifier.padding(paddingValues))
                    NavTab.HISTORY -> HistoryScreen(vm = vm, modifier = Modifier.padding(paddingValues))
                    NavTab.ABOUT -> AboutScreen(vm = vm, modifier = Modifier.padding(paddingValues))
                }
            }
        }
    }
}

// --- Bottom Navigation Bar (custom aurora-themed) --------------------------------
@Composable
fun BottomNavBar(
    currentTab: NavTab,
    onTabSelect: (NavTab) -> Unit,
    downloadCount: Int
) {
    Column {
        HorizontalDivider(color = SurfaceBorder.copy(alpha = 0.15f), thickness = 0.5.dp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Ink2.copy(alpha = 0.95f))
                .navigationBarsPadding()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            NavTab.entries.forEach { tab ->
                val isSelected = tab == currentTab
                val bgAlpha by animateFloatAsState(
                    if (isSelected) 1f else 0f, tween(250), label = "navBg"
                )
                val iconColor by animateColorAsState(
                    if (isSelected) VioletLight else TextTertiary, tween(250), label = "navIcon"
                )
                val textColor by animateColorAsState(
                    if (isSelected) VioletLight else TextTertiary, tween(250), label = "navText"
                )

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { onTabSelect(tab) }
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(contentAlignment = Alignment.TopEnd) {
                        Icon(
                            imageVector = if (isSelected) tab.selectedIcon else tab.icon,
                            contentDescription = tab.label,
                            tint = iconColor,
                            modifier = Modifier.size(22.dp)
                        )
                        // Badge for downloads count
                        if (tab == NavTab.DOWNLOADS && downloadCount > 0) {
                            val badgeText = if (downloadCount > 99) "99+" else downloadCount.toString()
                            Box(
                                modifier = Modifier
                                    .offset(x = 10.dp, y = (-6).dp)
                                    .defaultMinSize(minWidth = 18.dp, minHeight = 18.dp)
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(Violet)
                                    .padding(horizontal = 4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    badgeText,
                                    color = Color.White, fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    lineHeight = 10.sp
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        tab.label, color = textColor,
                        fontSize = 11.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                    )
                    // Active indicator line
                    Box(
                        modifier = Modifier
                            .padding(top = 3.dp)
                            .width(24.dp)
                            .height(2.dp)
                            .clip(RoundedCornerShape(1.dp))
                            .background(VioletLight.copy(alpha = bgAlpha))
                    )
                }
            }
        }
    }
}

// --- Home Screen (main download page) -------------------------------------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    vm: MainViewModel,
    torrentVm: TorrentViewModel? = null,
    onNavigateToTorrent: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    val keyboardController = LocalSoftwareKeyboardController.current
    val screenState = vm.screenState

    PullToRefreshBox(
        isRefreshing = vm.isRefreshing,
        onRefresh = { vm.refresh() },
        modifier = modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
        ) {
            // Header
            HeaderBar(
                ytdlpVersion = vm.ytdlpVersion,
                isUpdating = vm.isUpdating,
                onUpdate = { vm.updateYtDlp() }
            )

            // Hero — visible when NOT in Success state
            AnimatedVisibility(
                visible = screenState !is ScreenState.Success,
                enter = fadeIn(tween(350)) + expandVertically(tween(350)),
                exit = fadeOut(tween(250)) + shrinkVertically(tween(250))
            ) {
                HeroSection()
            }

            // Smart Bar
            SmartBar(
                url = vm.url,
                onUrlChange = { vm.onUrlChange(it) },
                screenState = screenState,
                isUrlValid = vm.isUrlValid(),
                onFetch = {
                    keyboardController?.hide()
                    vm.fetch()
                },
                onCancel = {
                    vm.cancelFetch()
                },
                onClear = {
                    vm.clearResult()
                },
                onAddTorrent = {
                    torrentVm?.let { tvm ->
                        tvm.onMagnetInputChange(vm.url.trim())
                        tvm.addMagnet()
                    }
                    onNavigateToTorrent()
                },
                onSearchTorrents = {
                    torrentVm?.let { tvm ->
                        tvm.activeTab = TorrentTab.SEARCH
                        tvm.onSearchQueryChange(vm.url.trim())
                        tvm.searchTorrents()
                    }
                    onNavigateToTorrent()
                }
            )

            // Quick-action chips
            SmartBarChips(
                onSearchTorrents = {
                    torrentVm?.activeTab = TorrentTab.SEARCH
                    onNavigateToTorrent()
                },
                onBrowseFiles = {
                    torrentVm?.activeTab = TorrentTab.FILES
                    onNavigateToTorrent()
                }
            )

            // Save location info
            SaveLocationInfo()

            // Error banner — shown in Error state, stays editable
            AnimatedVisibility(
                visible = screenState is ScreenState.Error,
                enter = fadeIn(tween(300)) + slideInVertically(tween(300)) { it / 3 },
                exit = fadeOut(tween(200)) + slideOutVertically(tween(200)) { -it / 4 }
            ) {
                val errorMsg = (screenState as? ScreenState.Error)?.message ?: ""
                ErrorBar(message = errorMsg)
            }

            // Status Message
            AnimatedVisibility(
                visible = vm.statusMessage != null,
                enter = fadeIn(tween(250)) + expandVertically(tween(250)),
                exit = fadeOut(tween(200)) + shrinkVertically(tween(200))
            ) {
                StatusBar(message = vm.statusMessage ?: "")
            }

            // ── Fetching → Skeleton shimmer ──
            AnimatedVisibility(
                visible = screenState is ScreenState.Fetching,
                enter = fadeIn(tween(400)) + slideInVertically(tween(400)) { it / 4 },
                exit = fadeOut(tween(250))
            ) {
                SkeletonResultCard()
            }

            // ── Success → Result Card ──
            AnimatedVisibility(
                visible = screenState is ScreenState.Success,
                enter = fadeIn(tween(400)) + slideInVertically(tween(400)) { it / 4 },
                exit = fadeOut(tween(250))
            ) {
                val info = (screenState as? ScreenState.Success)?.info
                if (info != null) {
                    ResultCard(
                        info = info,
                        selectedTab = vm.selectedTab,
                        onTabChange = { vm.selectedTab = it },
                        selectedFormatId = vm.selectedFormatId,
                        onFormatSelect = { vm.selectedFormatId = it },
                        isDownloading = vm.isDownloading,
                        downloadProgress = vm.downloadProgress,
                        onDownload = { vm.download() }
                    )
                }
            }

            // Supported Platforms
            PlatformChips()

            // Footer
            FooterSection()
        }
    }
}

// --- Save Location Info (replaces DownloadFolderRow) ----------------------------
@Composable
fun SaveLocationInfo() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(EmeraldDim)
            .border(1.dp, Emerald.copy(alpha = 0.12f), RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Outlined.Folder,
            contentDescription = "Save location",
            tint = Emerald,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "Save Location",
                color = TextSecondary, fontSize = 11.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp
            )
            Text(
                "Videos → Movies/MediaDL · Audio → Music/MediaDL",
                color = Emerald,
                fontSize = 12.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(EmeraldDim)
                .padding(horizontal = 10.dp, vertical = 5.dp)
        ) {
            Text("Auto", color = Emerald, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

// --- Search Bar -------------------------------------------------------------------
@Composable
fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String = "Search…",
    modifier: Modifier = Modifier
) {
    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        textStyle = LocalTextStyle.current.copy(color = TextPrimary, fontSize = 14.sp),
        cursorBrush = SolidColor(Cyan),
        decorationBox = { inner ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Ink2)
                    .border(1.dp, SurfaceBorder, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = null,
                    tint = TextTertiary,
                    modifier = Modifier.size(18.dp)
                )
                Box(modifier = Modifier.weight(1f)) {
                    if (query.isEmpty()) {
                        Text(placeholder, color = TextTertiary, fontSize = 14.sp)
                    }
                    inner()
                }
                if (query.isNotEmpty()) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Clear",
                        tint = TextTertiary,
                        modifier = Modifier
                            .size(16.dp)
                            .clickable { onQueryChange("") }
                    )
                }
            }
        },
        modifier = modifier.fillMaxWidth()
    )
}

// --- Active Download Progress Card -----------------------------------------------
@Composable
fun ActiveDownloadCard(
    title: String,
    thumbnailUrl: String,
    progress: Float,
    statusMessage: String?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val infiniteTransition = rememberInfiniteTransition(label = "progress_anim")

    // Shimmer sweep for the bar
    val shimmerOffset by infiniteTransition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer"
    )

    // Smooth animated fill
    val animatedProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(400, easing = FastOutSlowInEasing),
        label = "bar_fill"
    )

    // Pulsing border glow
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Ink2)
            .border(1.dp, Cyan.copy(alpha = glowAlpha), RoundedCornerShape(16.dp))
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {

            // ── Top row: thumbnail + text info ──
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Clean thumbnail, no overlay
                if (thumbnailUrl.isNotBlank()) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(thumbnailUrl)
                            .crossfade(true)
                            .build(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(10.dp))
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Ink3),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Download,
                            contentDescription = null,
                            tint = TextTertiary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    // Badge + percentage on same row
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(Cyan.copy(alpha = 0.15f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                "DOWNLOADING",
                                color = Cyan,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.8.sp
                            )
                        }
                        if (progress > 0f) {
                            Text(
                                "${(progress * 100).toInt()}%",
                                color = Emerald,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    // Title
                    Text(
                        title.ifBlank { "Preparing download…" },
                        color = TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // ── Colourful horizontal progress bar ──
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(99.dp))
                    .background(Ink3)
            ) {
                if (progress > 0f) {
                    // Filled portion: Cyan → Emerald gradient + shimmer sweep
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(animatedProgress)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(99.dp))
                            .background(
                                Brush.horizontalGradient(listOf(Cyan, Emerald))
                            )
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.horizontalGradient(
                                        colors = listOf(
                                            Color.Transparent,
                                            Color.White.copy(alpha = 0.4f),
                                            Color.Transparent
                                        ),
                                        startX = shimmerOffset * 400f,
                                        endX = (shimmerOffset + 0.4f) * 400f
                                    )
                                )
                        )
                    }
                } else {
                    // Indeterminate: animated Cyan → VioletLight sweep
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.horizontalGradient(
                                    colors = listOf(
                                        Color.Transparent,
                                        Cyan.copy(alpha = 0.7f),
                                        Emerald.copy(alpha = 0.7f),
                                        Color.Transparent
                                    ),
                                    startX = shimmerOffset * 800f,
                                    endX = (shimmerOffset + 0.45f) * 800f
                                )
                            )
                    )
                }
            }

            // Status message
            if (!statusMessage.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(5.dp))
                Text(
                    statusMessage,
                    color = TextTertiary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

// --- Filter Chips Row (reusable for Downloads & History) -------------------------
@Composable
fun FilterChipsRow(
    mediaFilter: MediaFilter,
    dateFilter: DateFilter,
    onMediaFilterChange: (MediaFilter) -> Unit,
    onDateFilterChange: (DateFilter) -> Unit
) {
    var showFilterPanel by remember { mutableStateOf(false) }
    val hasActiveFilter = mediaFilter != MediaFilter.ALL || dateFilter != DateFilter.ALL

    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
        // Filter toggle button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Active filter summary chips
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                if (hasActiveFilter) {
                    if (mediaFilter != MediaFilter.ALL) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(VioletLight.copy(alpha = 0.12f))
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(mediaFilter.label, color = VioletLight, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    if (dateFilter != DateFilter.ALL) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(Cyan.copy(alpha = 0.10f))
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(dateFilter.label, color = Cyan, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                } else {
                    Text("All downloads", color = TextTertiary, fontSize = 12.sp)
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Funnel filter button
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (showFilterPanel) VioletLight.copy(alpha = 0.15f)
                        else if (hasActiveFilter) Violet.copy(alpha = 0.10f)
                        else SurfaceCard.copy(alpha = 0.3f)
                    )
                    .border(
                        1.dp,
                        if (showFilterPanel || hasActiveFilter) VioletLight.copy(alpha = 0.25f)
                        else SurfaceBorder.copy(alpha = 0.12f),
                        RoundedCornerShape(10.dp)
                    )
                    .clickable { showFilterPanel = !showFilterPanel }
                    .padding(horizontal = 12.dp, vertical = 7.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.FilterList,
                        contentDescription = "Filter",
                        tint = if (showFilterPanel || hasActiveFilter) VioletLight else TextTertiary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        "Filter",
                        color = if (showFilterPanel || hasActiveFilter) VioletLight else TextTertiary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (hasActiveFilter) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(Emerald)
                        )
                    }
                }
            }
        }

        // Expandable filter panel
        AnimatedVisibility(
            visible = showFilterPanel,
            enter = expandVertically(tween(250)) + fadeIn(tween(200)),
            exit = shrinkVertically(tween(250)) + fadeOut(tween(150))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Ink2.copy(alpha = 0.9f))
                    .border(1.dp, SurfaceBorder.copy(alpha = 0.12f), RoundedCornerShape(14.dp))
                    .padding(14.dp)
            ) {
                // Media type section
                Text(
                    "MEDIA TYPE",
                    color = TextTertiary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MediaFilter.entries.forEach { f ->
                        val selected = f == mediaFilter
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(if (selected) VioletLight.copy(alpha = 0.15f) else SurfaceCard.copy(alpha = 0.25f))
                                .border(
                                    1.dp,
                                    if (selected) VioletLight.copy(alpha = 0.4f) else SurfaceBorder.copy(alpha = 0.15f),
                                    RoundedCornerShape(999.dp)
                                )
                                .clickable { onMediaFilterChange(f) }
                                .padding(horizontal = 14.dp, vertical = 7.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (selected) {
                                    Icon(
                                        imageVector = Icons.Rounded.Check,
                                        contentDescription = null,
                                        tint = VioletLight,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                }
                                Text(
                                    f.label,
                                    color = if (selected) VioletLight else TextTertiary,
                                    fontSize = 12.sp,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Date section
                Text(
                    "TIME PERIOD",
                    color = TextTertiary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DateFilter.entries.forEach { d ->
                        val selected = d == dateFilter
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(if (selected) Cyan.copy(alpha = 0.12f) else SurfaceCard.copy(alpha = 0.25f))
                                .border(
                                    1.dp,
                                    if (selected) Cyan.copy(alpha = 0.35f) else SurfaceBorder.copy(alpha = 0.15f),
                                    RoundedCornerShape(999.dp)
                                )
                                .clickable { onDateFilterChange(d) }
                                .padding(horizontal = 14.dp, vertical = 7.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (selected) {
                                    Icon(
                                        imageVector = Icons.Rounded.Check,
                                        contentDescription = null,
                                        tint = Cyan,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                }
                                Text(
                                    d.label,
                                    color = if (selected) Cyan else TextTertiary,
                                    fontSize = 12.sp,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }
                }

                // Clear all button (only when filters are active)
                if (hasActiveFilter) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Rose.copy(alpha = 0.06f))
                            .border(1.dp, Rose.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                            .clickable {
                                onMediaFilterChange(MediaFilter.ALL)
                                onDateFilterChange(DateFilter.ALL)
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = null,
                                tint = Rose.copy(alpha = 0.6f),
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Clear filters", color = Rose.copy(alpha = 0.6f), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

// --- Downloads Screen -----------------------------------------------------------
@Composable
fun DownloadsScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // visibleDownloads excludes items the user deleted (hiddenDownloadIds)
    val visibleDownloads = vm.downloadHistory.filter { it.id !in vm.hiddenDownloadIds }
    val videoCount = visibleDownloads.count { it.type == "video" }
    val audioCount = visibleDownloads.count { it.type == "audio" }
    val totalSize = visibleDownloads.sumOf { it.fileSize }

    var searchQuery by remember { mutableStateOf("") }

    val filtered = vm.filteredDownloads().let { list ->
        if (searchQuery.isBlank()) list
        else list.filter {
            it.title.contains(searchQuery, ignoreCase = true) ||
            it.platform.contains(searchQuery, ignoreCase = true) ||
            it.format.contains(searchQuery, ignoreCase = true)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        // Header
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            Text(
                "Downloads",
                color = TextPrimary,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-1).sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Tap to play · Swipe to manage",
                color = TextTertiary,
                fontSize = 13.sp
            )
        }

        // ── Active download progress card ──
        AnimatedVisibility(
            visible = vm.isDownloading || vm.activeDownloadTitle.isNotBlank(),
            enter = expandVertically(tween(300)) + fadeIn(tween(250)),
            exit = shrinkVertically(tween(300)) + fadeOut(tween(200))
        ) {
            ActiveDownloadCard(
                title = vm.activeDownloadTitle,
                thumbnailUrl = vm.activeDownloadThumbnail,
                progress = vm.downloadProgress,
                statusMessage = vm.statusMessage,
                modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 12.dp)
            )
        }

        if (vm.visibleDownloadsCount() == 0 && !vm.isDownloading) {
            // Empty state
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 80.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Outlined.CloudDownload,
                    contentDescription = null,
                    tint = TextTertiary,
                    modifier = Modifier.size(48.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "No Downloads Yet",
                    color = TextSecondary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Downloaded media will appear here.\nFiles are saved to Movies & Music folders.",
                    color = TextTertiary,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 20.sp
                )
            }
        } else if (vm.visibleDownloadsCount() > 0) {
            // Stats pills row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                HistoryStatPill(
                    icon = Icons.Outlined.Videocam,
                    label = "$videoCount videos",
                    color = Cyan,
                    modifier = Modifier.weight(1f)
                )
                HistoryStatPill(
                    icon = Icons.Outlined.MusicNote,
                    label = "$audioCount audio",
                    color = VioletLight,
                    modifier = Modifier.weight(1f)
                )
                HistoryStatPill(
                    icon = Icons.Outlined.Storage,
                    label = formatBytes(totalSize),
                    color = Emerald,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Search bar
            SearchBar(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                placeholder = "Search downloads…",
                modifier = Modifier.padding(horizontal = 20.dp)
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Filter chips
            FilterChipsRow(
                mediaFilter = vm.downloadsMediaFilter,
                dateFilter = vm.downloadsDateFilter,
                onMediaFilterChange = { vm.downloadsMediaFilter = it },
                onDateFilterChange = { vm.downloadsDateFilter = it }
            )

            Spacer(modifier = Modifier.height(12.dp))

            if (filtered.isEmpty()) {
                // No results after filtering/search
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = if (searchQuery.isBlank()) Icons.Outlined.FilterList else Icons.Outlined.SearchOff,
                        contentDescription = null,
                        tint = TextTertiary,
                        modifier = Modifier.size(36.dp)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        if (searchQuery.isBlank()) "No matches for current filters" else "No results for \"$searchQuery\"",
                        color = TextTertiary,
                        fontSize = 13.sp
                    )
                }
            } else {
                // Download items
                Column(
                    modifier = Modifier.padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    filtered.forEach { record ->
                        DownloadItem(
                            record = record,
                            isExpanded = vm.expandedDownloadId == record.id,
                            onToggleExpand = { vm.toggleExpandedDownload(record.id) },
                            onPlay = { playMedia(context, record) },
                            onDelete = { vm.requestDelete(record.id) }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    // Delete confirmation dialog
    val pendingDeleteId = vm.pendingDeleteId
    if (pendingDeleteId != null) {
        val recordTitle = vm.downloadHistory.find { it.id == pendingDeleteId }?.title ?: "this file"
        AlertDialog(
            onDismissRequest = { vm.cancelDelete() },
            containerColor = Ink2,
            shape = RoundedCornerShape(20.dp),
            icon = {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(Rose.copy(alpha = 0.10f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.DeleteForever,
                        contentDescription = null,
                        tint = Rose,
                        modifier = Modifier.size(24.dp)
                    )
                }
            },
            title = {
                Text(
                    "Delete Download?",
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 18.sp
                )
            },
            text = {
                Text(
                    "\"$recordTitle\" will be permanently deleted from your device.",
                    color = TextSecondary,
                    fontSize = 13.sp,
                    lineHeight = 20.sp
                )
            },
            confirmButton = {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Rose.copy(alpha = 0.10f))
                        .clickable { vm.confirmDelete() }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Delete,
                        contentDescription = null,
                        tint = Rose,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Delete", color = Rose, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
            },
            dismissButton = {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(SurfaceCard.copy(alpha = 0.4f))
                        .clickable { vm.cancelDelete() }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Cancel", color = TextSecondary, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                }
            }
        )
    }
}

// --- Single Download Item (compact expandable card) -----------------------------
@Composable
fun DownloadItem(
    record: DownloadRecord,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onPlay: () -> Unit,
    onDelete: () -> Unit
) {
    val typeColor = if (record.type == "audio") VioletLight else Cyan
    val typeBg = if (record.type == "audio") VioletDim else CyanDim
    val borderColor by animateColorAsState(
        if (isExpanded) typeColor.copy(alpha = 0.25f) else SurfaceBorder.copy(alpha = 0.08f),
        tween(250), label = "dlBorder"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Ink2.copy(alpha = 0.8f))
            .border(1.dp, borderColor, RoundedCornerShape(14.dp))
            .animateContentSize(animationSpec = tween(280, easing = FastOutSlowInEasing))
    ) {
        // ── Compact header row (always visible) ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggleExpand() }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Thumbnail or type icon badge
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(typeBg)
                    .border(1.dp, typeColor.copy(alpha = 0.15f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (record.thumbnailUrl.isNotBlank()) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(record.thumbnailUrl)
                            .crossfade(true)
                            .build(),
                        contentDescription = record.title,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(10.dp)),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        imageVector = if (record.type == "audio") Icons.Rounded.MusicNote else Icons.Rounded.Videocam,
                        contentDescription = record.type,
                        tint = typeColor,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            // Title + time
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    record.title,
                    color = TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = if (isExpanded) 3 else 1,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 18.sp
                )
                if (!isExpanded) {
                    Text(
                        formatHistoryDate(record.timestamp),
                        color = TextTertiary,
                        fontSize = 10.sp,
                        maxLines = 1
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Play button
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(typeColor.copy(alpha = 0.12f))
                    .border(1.dp, typeColor.copy(alpha = 0.2f), CircleShape)
                    .clickable { onPlay() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = "Play",
                    tint = typeColor,
                    modifier = Modifier.size(18.dp)
                )
            }

            Spacer(modifier = Modifier.width(6.dp))

            // Expand/collapse chevron
            val rotation by animateFloatAsState(
                if (isExpanded) 180f else 0f, tween(280), label = "chevron"
            )
            Icon(
                imageVector = Icons.Rounded.ExpandMore,
                contentDescription = if (isExpanded) "Collapse" else "Expand",
                tint = TextTertiary,
                modifier = Modifier
                    .size(20.dp)
                    .graphicsLayer { rotationZ = rotation }
                    .clickable { onToggleExpand() }
            )
        }

        // ── Expanded details ──
        if (isExpanded) {
            HorizontalDivider(
                color = SurfaceBorder.copy(alpha = 0.08f),
                thickness = 0.5.dp,
                modifier = Modifier.padding(horizontal = 12.dp)
            )

            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                // Detail chips row
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Platform badge
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(VioletDim)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            record.platform,
                            color = VioletLight,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    // Type label
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (record.type == "audio") Icons.Outlined.MusicNote else Icons.Outlined.Videocam,
                            contentDescription = null,
                            tint = TextTertiary,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(
                            if (record.type == "audio") "Audio" else "Video",
                            color = TextTertiary, fontSize = 10.sp
                        )
                    }
                    if (record.fileSize > 0) {
                        Text(
                            formatBytes(record.fileSize),
                            color = TextTertiary, fontSize = 10.sp
                        )
                    }
                    Text(
                        formatHistoryDate(record.timestamp),
                        color = TextTertiary, fontSize = 10.sp
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Play button
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(typeColor.copy(alpha = 0.10f))
                            .border(1.dp, typeColor.copy(alpha = 0.15f), RoundedCornerShape(10.dp))
                            .clickable { onPlay() }
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = "Play",
                            tint = typeColor,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Play", color = typeColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                    // Delete button
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Rose.copy(alpha = 0.06f))
                            .border(1.dp, Rose.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
                            .clickable { onDelete() }
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.DeleteOutline,
                            contentDescription = "Delete",
                            tint = Rose.copy(alpha = 0.7f),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Delete", color = Rose.copy(alpha = 0.7f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

// --- History Screen -------------------------------------------------------------
@Composable
fun HistoryScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val allHistory = vm.downloadHistory
    val visibleCount = vm.visibleHistoryCount()

    var searchQuery by remember { mutableStateOf("") }

    val filtered = vm.filteredHistory().let { list ->
        if (searchQuery.isBlank()) list
        else list.filter {
            it.title.contains(searchQuery, ignoreCase = true) ||
            it.platform.contains(searchQuery, ignoreCase = true) ||
            it.format.contains(searchQuery, ignoreCase = true)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    "History",
                    color = TextPrimary,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-1).sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "$visibleCount downloads logged",
                    color = TextTertiary,
                    fontSize = 13.sp
                )
            }

            if (visibleCount > 0) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Rose.copy(alpha = 0.08f))
                        .border(1.dp, Rose.copy(alpha = 0.15f), RoundedCornerShape(10.dp))
                        .clickable { vm.clearHistory() }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(
                        "Clear All",
                        color = Rose.copy(alpha = 0.7f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        if (visibleCount == 0) {
            // Empty state
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 80.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Outlined.History,
                    contentDescription = null,
                    tint = TextTertiary,
                    modifier = Modifier.size(48.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "No History Yet",
                    color = TextSecondary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Your download history will appear here.\nTap any item to re-fetch the URL.",
                    color = TextTertiary,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 20.sp
                )
            }
        } else {
            // Search bar
            SearchBar(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                placeholder = "Search history…",
                modifier = Modifier.padding(horizontal = 20.dp)
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Filter chips
            FilterChipsRow(
                mediaFilter = vm.historyMediaFilter,
                dateFilter = vm.historyDateFilter,
                onMediaFilterChange = { vm.historyMediaFilter = it },
                onDateFilterChange = { vm.historyDateFilter = it }
            )

            Spacer(modifier = Modifier.height(12.dp))

            if (filtered.isEmpty()) {
                // No results after filtering/search
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = if (searchQuery.isBlank()) Icons.Outlined.FilterList else Icons.Outlined.SearchOff,
                        contentDescription = null,
                        tint = TextTertiary,
                        modifier = Modifier.size(36.dp)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        if (searchQuery.isBlank()) "No matches for current filters" else "No results for \"$searchQuery\"",
                        color = TextTertiary,
                        fontSize = 13.sp
                    )
                }
            } else {
                // Group by date sections
                val grouped = filtered.groupBy { record ->
                    val now = System.currentTimeMillis()
                    val diff = now - record.timestamp
                    when {
                        diff < 86_400_000 -> "Today"
                        diff < 172_800_000 -> "Yesterday"
                        diff < 604_800_000 -> "This Week"
                        else -> "Older"
                    }
                }

                grouped.forEach { (section, records) ->
                    // Section header
                    Text(
                        section.uppercase(),
                        color = TextTertiary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                    )

                    Column(
                        modifier = Modifier.padding(horizontal = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        records.forEach { record ->
                            HistoryItem(
                                record = record,
                                onRemove = { vm.removeHistoryItem(record.id) },
                                onClick = {
                                    vm.url = record.url
                                    vm.currentNavTab = NavTab.HOME
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

// --- Aurora Background ----------------------------------------------------------
@Composable
fun AuroraBackground() {
    val infiniteTransition = rememberInfiniteTransition(label = "aurora")
    val offset by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(12000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ), label = "auroraOffset"
    )
    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width; val h = size.height
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Violet.copy(alpha = 0.15f), Color.Transparent),
                center = Offset(w * (0.15f + offset * 0.1f), h * 0.1f), radius = w * 0.6f
            ), radius = w * 0.6f, center = Offset(w * (0.15f + offset * 0.1f), h * 0.1f)
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Cyan.copy(alpha = 0.08f), Color.Transparent),
                center = Offset(w * (0.85f - offset * 0.05f), h * 0.2f), radius = w * 0.5f
            ), radius = w * 0.5f, center = Offset(w * (0.85f - offset * 0.05f), h * 0.2f)
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Violet.copy(alpha = 0.08f), Color.Transparent),
                center = Offset(w * 0.5f, h * 0.95f), radius = w * 0.7f
            ), radius = w * 0.7f, center = Offset(w * 0.5f, h * 0.95f)
        )
    }
}

// --- Header Bar -----------------------------------------------------------------
@Composable
fun HeaderBar(ytdlpVersion: String, isUpdating: Boolean, onUpdate: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Brush.linearGradient(listOf(Violet, Color(0xFF5A8AF5)))),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Download,
                    contentDescription = "MM-DL",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Text("MM", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text("-DL", color = VioletLight, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }

        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(SurfaceCard.copy(alpha = 0.6f))
                .border(1.dp, SurfaceBorder, RoundedCornerShape(999.dp))
                .clickable(enabled = !isUpdating) { onUpdate() }
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val dotColor by animateColorAsState(if (isUpdating) Amber else Emerald, label = "dotColor")
            Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(dotColor))
            Spacer(modifier = Modifier.width(7.dp))
            Text(
                text = if (isUpdating) "Updating..." else ytdlpVersion,
                color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium
            )
        }
    }
}

// --- Hero Section ---------------------------------------------------------------
@Composable
fun HeroSection() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp)
            .padding(top = 32.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(VioletDim)
                .border(1.dp, Violet.copy(alpha = 0.22f), RoundedCornerShape(999.dp))
                .padding(horizontal = 16.dp, vertical = 6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PulsingDot()
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "1000+ SUPPORTED PLATFORMS", color = VioletLight,
                    fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.9.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            "Download Any Video,", color = TextPrimary,
            fontSize = 32.sp, fontWeight = FontWeight.Bold,
            letterSpacing = (-1.5).sp, lineHeight = 36.sp
        )
        Text(
            "Anywhere.",
            fontSize = 32.sp, fontWeight = FontWeight.Bold,
            letterSpacing = (-1.5).sp, lineHeight = 36.sp,
            style = LocalTextStyle.current.copy(
                brush = Brush.linearGradient(listOf(VioletLight, Cyan, VioletLight))
            )
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            "Paste a link from YouTube, Instagram, Facebook, TikTok, Twitter/X, Vimeo and more \u2014 get your file in seconds.",
            color = TextSecondary, fontSize = 14.sp, lineHeight = 22.sp,
            modifier = Modifier.padding(horizontal = 16.dp),
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
fun PulsingDot() {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ), label = "pulseAlpha"
    )
    Box(modifier = Modifier.size(6.dp).alpha(alpha).clip(CircleShape).background(VioletLight))
}

// --- SmartBar (enhanced URL bar with magnet/torrent detection) ------------------
@Composable
fun SmartBar(
    url: String,
    onUrlChange: (String) -> Unit,
    screenState: ScreenState,
    isUrlValid: Boolean,
    onFetch: () -> Unit,
    onCancel: () -> Unit,
    onClear: () -> Unit,
    onAddTorrent: () -> Unit = {},
    onSearchTorrents: () -> Unit = {}
) {
    val inputType = remember(url) { detectInputType(url) }
    val platform = remember(url) { detectPlatform(url) }
    val isFetching = screenState is ScreenState.Fetching
    val isSuccess = screenState is ScreenState.Success
    val fetchEnabled = !isFetching && (isUrlValid || inputType == InputType.MAGNET || inputType == InputType.TORRENT_URL || inputType == InputType.SEARCH)

    // Animate border color based on state
    val borderColor by animateColorAsState(
        when {
            isFetching -> Violet.copy(alpha = 0.45f)
            isSuccess -> Emerald.copy(alpha = 0.3f)
            screenState is ScreenState.Error -> Rose.copy(alpha = 0.25f)
            inputType == InputType.MAGNET -> Cyan.copy(alpha = 0.35f)
            inputType == InputType.TORRENT_URL -> Amber.copy(alpha = 0.35f)
            else -> SurfaceBorder.copy(alpha = 0.15f)
        },
        animationSpec = tween(350),
        label = "smartBorder"
    )

    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(Ink2)
                .border(1.dp, borderColor, RoundedCornerShape(24.dp))
                .padding(6.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = url,
                    onValueChange = onUrlChange,
                    placeholder = { Text("Paste a URL, magnet link, or search…", color = TextTertiary, fontSize = 14.sp) },
                    singleLine = true,
                    enabled = !isFetching,
                    modifier = Modifier.weight(1f),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        disabledBorderColor = Color.Transparent,
                        cursorColor = VioletLight,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        disabledTextColor = TextSecondary
                    ),
                    textStyle = LocalTextStyle.current.copy(fontSize = 14.sp),
                    trailingIcon = {
                        AnimatedVisibility(
                            visible = isSuccess,
                            enter = fadeIn(tween(200)) + scaleIn(tween(200)),
                            exit = fadeOut(tween(150)) + scaleOut(tween(150))
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(SurfaceCard.copy(alpha = 0.6f))
                                    .border(1.dp, SurfaceBorder.copy(alpha = 0.15f), CircleShape)
                                    .clickable { onClear() },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Rounded.Close, "Clear", tint = TextSecondary, modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                )

                // Type indicator pill
                val pillText = when (inputType) {
                    InputType.MAGNET -> "Magnet"
                    InputType.TORRENT_URL -> "Torrent"
                    InputType.SEARCH -> if (url.isNotBlank()) "Search" else null
                    InputType.URL -> platform
                    else -> null
                }
                val pillColor = when (inputType) {
                    InputType.MAGNET -> Cyan
                    InputType.TORRENT_URL -> Amber
                    InputType.SEARCH -> Emerald
                    else -> VioletLight
                }
                if (pillText != null && !isFetching) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(pillColor.copy(alpha = 0.12f))
                            .border(1.dp, pillColor.copy(alpha = 0.25f), RoundedCornerShape(999.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(pillText, color = pillColor, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }

                // ── Smart action buttons ──
                AnimatedContent(
                    targetState = Pair(isFetching, inputType),
                    transitionSpec = {
                        (fadeIn(tween(200)) + scaleIn(tween(200), initialScale = 0.85f))
                            .togetherWith(fadeOut(tween(150)) + scaleOut(tween(150), targetScale = 0.85f))
                    },
                    label = "smartBtnSwap"
                ) { (fetching, type) ->
                    if (fetching) {
                        Button(
                            onClick = onCancel,
                            modifier = Modifier.height(44.dp),
                            shape = RoundedCornerShape(20.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Rose.copy(alpha = 0.85f)),
                            contentPadding = PaddingValues(horizontal = 18.dp)
                        ) {
                            Icon(Icons.Rounded.Close, null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Stop", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        }
                    } else {
                        when (type) {
                            InputType.MAGNET, InputType.TORRENT_URL -> {
                                Button(
                                    onClick = onAddTorrent,
                                    modifier = Modifier.height(44.dp),
                                    shape = RoundedCornerShape(20.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Cyan.copy(alpha = 0.85f)),
                                    contentPadding = PaddingValues(horizontal = 18.dp)
                                ) {
                                    Icon(Icons.Outlined.CloudDownload, null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Torrent", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                }
                            }
                            InputType.SEARCH -> {
                                Button(
                                    onClick = onSearchTorrents,
                                    enabled = url.isNotBlank(),
                                    modifier = Modifier.height(44.dp),
                                    shape = RoundedCornerShape(20.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Emerald.copy(alpha = 0.85f)),
                                    contentPadding = PaddingValues(horizontal = 14.dp)
                                ) {
                                    Icon(Icons.Rounded.Search, null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Torrents", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                }
                            }
                            else -> {
                                Button(
                                    onClick = onFetch,
                                    enabled = !isFetching && isUrlValid,
                                    modifier = Modifier.height(44.dp),
                                    shape = RoundedCornerShape(20.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Violet,
                                        disabledContainerColor = Violet.copy(alpha = 0.25f)
                                    ),
                                    contentPadding = PaddingValues(horizontal = 20.dp)
                                ) {
                                    Text(
                                        "Fetch",
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 14.sp,
                                        color = if (!isFetching && isUrlValid) Color.White else Color.White.copy(alpha = 0.4f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// --- Smart Bar Chips (quick actions below search bar) ---------------------------
@Composable
fun SmartBarChips(
    onSearchTorrents: () -> Unit,
    onBrowseFiles: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SmartChip(Icons.Rounded.Search, "Search Torrents", Emerald) { onSearchTorrents() }
        SmartChip(Icons.Outlined.Folder, "Browse Files", Cyan) { onBrowseFiles() }
    }
}

@Composable
private fun SmartChip(icon: ImageVector, label: String, color: Color, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(color.copy(alpha = 0.08f))
            .border(1.dp, color.copy(alpha = 0.15f), RoundedCornerShape(999.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(14.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text(label, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

// --- Error Bar ------------------------------------------------------------------
@Composable
fun ErrorBar(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Rose.copy(alpha = 0.10f))
            .border(1.dp, Rose.copy(alpha = 0.3f), RoundedCornerShape(14.dp))
            .padding(14.dp)
    ) {
        Text(text = message, color = Rose, fontSize = 13.sp, lineHeight = 18.sp)
    }
}

// --- Status Bar -----------------------------------------------------------------
@Composable
fun StatusBar(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(EmeraldDim)
            .border(1.dp, Emerald.copy(alpha = 0.3f), RoundedCornerShape(14.dp))
            .padding(14.dp)
    ) {
        Text(text = message, color = Emerald, fontSize = 13.sp, lineHeight = 18.sp)
    }
}

// --- Result Card ----------------------------------------------------------------
@Composable
fun ResultCard(
    info: VideoInfo,
    selectedTab: String,
    onTabChange: (String) -> Unit,
    selectedFormatId: String,
    onFormatSelect: (String) -> Unit,
    isDownloading: Boolean,
    downloadProgress: Float,
    onDownload: () -> Unit
) {
    val videoFormats = info.formats.filter { it.type == "video" }
    val audioFormats = info.formats.filter { it.type == "audio" }
    val displayFormats = if (selectedTab == "video") videoFormats else audioFormats

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Ink2.copy(alpha = 0.85f)),
        border = BorderStroke(1.dp, SurfaceBorder.copy(alpha = 0.12f))
    ) {
        // Animated gradient top border
        val tr = rememberInfiniteTransition(label = "cardBorder")
        val phase by tr.animateFloat(
            initialValue = 0f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(4000, easing = LinearEasing), RepeatMode.Restart),
            label = "gradSlide"
        )
        Box(
            modifier = Modifier.fillMaxWidth().height(2.dp).drawBehind {
                val w = size.width; val shift = phase * 2f * w
                drawRect(Brush.horizontalGradient(
                    listOf(Violet, Cyan, Color(0xFFEC4899), Violet),
                    startX = -shift, endX = 3f * w - shift
                ))
            }
        )

        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                info.title, color = TextPrimary, fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 2,
                overflow = TextOverflow.Ellipsis, lineHeight = 22.sp
            )
            Spacer(modifier = Modifier.height(6.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                val platform = detectPlatform(info.url)
                if (platform != null) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(VioletDim)
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(platform, color = VioletLight, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(info.uploader, color = TextSecondary, fontSize = 12.sp)
            }

            if (info.duration.isNotBlank() || info.viewCount.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (info.duration.isNotBlank()) StatChip(info.duration)
                    if (info.viewCount.isNotBlank()) StatChip("${info.viewCount} views")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = SurfaceBorder, thickness = 0.5.dp)
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                "Quality & Format", color = TextSecondary,
                fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp
            )
            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(SurfaceCard.copy(alpha = 0.3f))
                    .border(1.dp, SurfaceBorder, RoundedCornerShape(10.dp))
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                QualityTab("Video", videoFormats.size, selectedTab == "video", { onTabChange("video") }, Modifier.weight(1f))
                QualityTab("Audio", audioFormats.size, selectedTab == "audio", { onTabChange("audio") }, Modifier.weight(1f))
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (displayFormats.isEmpty()) {
                Text("No $selectedTab streams available", color = TextTertiary, fontSize = 13.sp, modifier = Modifier.padding(vertical = 12.dp))
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    displayFormats.forEach { fmt ->
                        FormatItem(fmt, fmt.formatId == selectedFormatId) { onFormatSelect(fmt.formatId) }
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            Box(modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = onDownload,
                    enabled = !isDownloading && selectedFormatId.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Emerald,
                        disabledContainerColor = Emerald.copy(alpha = 0.35f)
                    )
                ) {
                    if (isDownloading) {
                        Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Downloading... ${"%.0f".format(downloadProgress * 100)}%", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    } else {
                        Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Download", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }
                }
                if (isDownloading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(downloadProgress.coerceIn(0f, 1f))
                            .height(52.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.White.copy(alpha = 0.10f))
                    )
                }
            }
        }
    }
}

@Composable
fun StatChip(label: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(SurfaceCard.copy(alpha = 0.5f))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(label, color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun QualityTab(label: String, count: Int, isActive: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val bg by animateColorAsState(if (isActive) VioletDim else Color.Transparent, label = "tabBg")
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(7.dp))
            .background(bg)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Text(label, color = if (isActive) VioletLight else TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.width(6.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(if (isActive) Violet.copy(alpha = 0.2f) else SurfaceCard.copy(alpha = 0.5f))
                .padding(horizontal = 6.dp, vertical = 1.dp)
        ) {
            Text(count.toString(), color = if (isActive) VioletLight else TextTertiary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun FormatItem(format: FormatOption, isSelected: Boolean, onSelect: () -> Unit) {
    val bg by animateColorAsState(if (isSelected) VioletDim else Color.Transparent, label = "fmtBg")
    val bc by animateColorAsState(if (isSelected) Violet.copy(alpha = 0.35f) else SurfaceBorder.copy(alpha = 0.08f), label = "fmtBc")

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .border(1.dp, bc, RoundedCornerShape(10.dp))
            .animateContentSize(animationSpec = tween(250, easing = FastOutSlowInEasing))
            .clickable { onSelect() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 13.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (format.qualityTag.isNotBlank()) {
                val tagBrush = when (format.qualityTag) {
                    "BEST" -> Brush.linearGradient(listOf(Color(0xFFFBBF24), Color(0xFFF59E0B)))
                    "8K" -> Brush.linearGradient(listOf(Color(0xFFF43F5E), Color(0xFFEC4899)))
                    "4K" -> Brush.linearGradient(listOf(Color(0xFFF59E0B), Color(0xFFF97316)))
                    "2K" -> Brush.linearGradient(listOf(Color(0xFF8B5CF6), Color(0xFF7C3AED)))
                    "FHD" -> Brush.linearGradient(listOf(Color(0xFF10B981), Color(0xFF059669)))
                    "HD" -> Brush.linearGradient(listOf(Color(0xFF3B82F6), Color(0xFF2563EB)))
                    "\u266A" -> Brush.linearGradient(listOf(Color(0xFFEC4899), Color(0xFFDB2777)))
                    else -> Brush.linearGradient(listOf(SurfaceHighlight, SurfaceBorder))
                }
                val tagText = when (format.qualityTag) {
                    "BEST" -> Color(0xFF1A1A2E)
                    "SD", "360p", "Low" -> TextSecondary
                    else -> Color.White
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .background(tagBrush)
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(format.qualityTag, color = tagText, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.5.sp)
                }
                Spacer(modifier = Modifier.width(10.dp))
            }

            Text(
                format.label, color = if (isSelected) TextPrimary else TextSecondary,
                fontSize = 13.sp, modifier = Modifier.weight(1f),
                maxLines = if (isSelected) 3 else 1, overflow = TextOverflow.Ellipsis
            )

            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(if (isSelected) Violet else Color.Transparent)
                    .border(1.5.dp, if (isSelected) Violet else SurfaceBorder, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = "Selected",
                        tint = Color.White,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}

// --- Download History Helpers ----------------------------------------------------

fun formatHistoryDate(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    val mins = diff / 60_000
    val hours = diff / 3_600_000
    val days = diff / 86_400_000
    return when {
        mins < 1 -> "Just now"
        mins < 60 -> "${mins}m ago"
        hours < 24 -> "${hours}h ago"
        days < 7 -> "${days}d ago"
        else -> {
            val sdf = java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault())
            sdf.format(java.util.Date(timestamp))
        }
    }
}

@Composable
fun HistoryStatPill(
    icon: ImageVector,
    label: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.06f))
            .border(1.dp, color.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(14.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            label,
            color = color,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

@Composable
fun HistoryItem(
    record: DownloadRecord,
    onRemove: () -> Unit,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Ink2.copy(alpha = 0.6f))
            .border(1.dp, SurfaceBorder.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Thumbnail or type icon
        val typeIcon = if (record.type == "audio") Icons.Outlined.MusicNote else Icons.Outlined.Videocam
        val typeBg = if (record.type == "audio") VioletDim else CyanDim
        val typeColor = if (record.type == "audio") VioletLight else Cyan
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(typeBg),
            contentAlignment = Alignment.Center
        ) {
            if (record.thumbnailUrl.isNotBlank()) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(record.thumbnailUrl)
                        .crossfade(true)
                        .build(),
                    contentDescription = record.title,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(9.dp)),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(
                    imageVector = typeIcon,
                    contentDescription = record.type,
                    tint = typeColor,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                record.title,
                color = TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(3.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Platform badge
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(VioletDim)
                        .padding(horizontal = 6.dp, vertical = 1.dp)
                ) {
                    Text(
                        record.platform,
                        color = VioletLight,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                if (record.fileSize > 0) {
                    Text(
                        formatBytes(record.fileSize),
                        color = TextTertiary,
                        fontSize = 10.sp
                    )
                }
                Text(
                    formatHistoryDate(record.timestamp),
                    color = TextTertiary,
                    fontSize = 10.sp
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Remove button
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(SurfaceCard.copy(alpha = 0.4f))
                .border(1.dp, SurfaceBorder.copy(alpha = 0.1f), CircleShape)
                .clickable { onRemove() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = "Remove",
                tint = TextTertiary,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

// --- Platform Chips -------------------------------------------------------------
@Composable
fun PlatformChips() {
    val platforms = listOf(
        "YouTube", "Instagram", "Facebook", "TikTok",
        "Twitter/X", "Vimeo", "Reddit", "Twitch",
        "SoundCloud", "Dailymotion"
    )
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 32.dp)
    ) {
        Text(
            "SUPPORTED PLATFORMS", color = TextTertiary,
            fontSize = 10.sp, fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp, modifier = Modifier.align(Alignment.CenterHorizontally)
        )
        Spacer(modifier = Modifier.height(14.dp))

        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            platforms.forEach { name ->
                Box(
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(SurfaceCard.copy(alpha = 0.4f))
                        .border(1.dp, SurfaceBorder, RoundedCornerShape(999.dp))
                        .padding(horizontal = 14.dp, vertical = 7.dp)
                ) {
                    Text(name, color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

// --- About Screen ---------------------------------------------------------------
@Composable
fun AboutScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    val versionName = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0"
    } catch (_: Exception) { "1.0" }

    var showCrashDebugger by remember { mutableStateOf(false) }

    if (showCrashDebugger) {
        CrashDebuggerScreen(
            onBack = { showCrashDebugger = false },
            modifier = modifier
        )
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        // Header
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            Text(
                "About",
                color = TextPrimary,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-1).sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "App info & credits",
                color = TextTertiary,
                fontSize = 13.sp
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // App identity card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Ink2.copy(alpha = 0.8f))
                .border(1.dp, SurfaceBorder.copy(alpha = 0.12f), RoundedCornerShape(16.dp))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // App icon
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Brush.linearGradient(listOf(Violet, Color(0xFF5A8AF5)))),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Download,
                    contentDescription = "MM-DL",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            Row {
                Text("MM", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                Text("-DL", color = VioletLight, fontWeight = FontWeight.Bold, fontSize = 22.sp)
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                "Version $versionName",
                color = TextTertiary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                "A free, open-source multi-platform media downloader.\nSupports 1000+ sites.",
                color = TextSecondary,
                fontSize = 13.sp,
                lineHeight = 20.sp,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Developer pill
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(Emerald.copy(alpha = 0.08f))
                    .border(1.dp, Emerald.copy(alpha = 0.2f), RoundedCornerShape(999.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.Code,
                    contentDescription = null,
                    tint = Emerald,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    "Developed with \u2665 by Bismaya",
                    color = Emerald,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Website button
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(SurfaceCard.copy(alpha = 0.3f))
                    .border(1.dp, SurfaceBorder, RoundedCornerShape(999.dp))
                    .clickable { uriHandler.openUri("https://bismaya.xyz") }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.Language,
                    contentDescription = null,
                    tint = TextSecondary,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("bismaya.xyz", color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // yt-dlp version info
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Ink2.copy(alpha = 0.6f))
                .border(1.dp, SurfaceBorder.copy(alpha = 0.10f), RoundedCornerShape(14.dp))
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Terminal,
                    contentDescription = null,
                    tint = Cyan,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Engine", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("yt-dlp version", color = TextTertiary, fontSize = 12.sp)
                Text(
                    vm.ytdlpVersion.ifBlank { "loading…" },
                    color = TextSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Resources section
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Ink2.copy(alpha = 0.6f))
                .border(1.dp, SurfaceBorder.copy(alpha = 0.10f), RoundedCornerShape(14.dp))
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Link,
                    contentDescription = null,
                    tint = VioletLight,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Resources", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(modifier = Modifier.height(12.dp))

            AboutLinkRow(
                icon = Icons.Outlined.Code,
                label = "yt-dlp on GitHub",
                color = TextSecondary
            ) { uriHandler.openUri("https://github.com/yt-dlp/yt-dlp") }

            AboutLinkRow(
                icon = Icons.Outlined.Public,
                label = "All Supported Sites",
                color = TextSecondary
            ) { uriHandler.openUri("https://github.com/yt-dlp/yt-dlp/blob/master/supportedsites.md") }

            AboutLinkRow(
                icon = Icons.Outlined.Language,
                label = "Developer Website",
                color = TextSecondary
            ) { uriHandler.openUri("https://bismaya.xyz") }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Feedback & Bug Report section
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Ink2.copy(alpha = 0.6f))
                .border(1.dp, SurfaceBorder.copy(alpha = 0.10f), RoundedCornerShape(14.dp))
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Feedback,
                    contentDescription = null,
                    tint = Cyan,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Feedback & Support", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                "Spotted a bug? Have a suggestion? Reach out directly — every report helps.",
                color = TextTertiary,
                fontSize = 12.sp,
                lineHeight = 18.sp
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Bug report button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Rose.copy(alpha = 0.08f))
                    .border(1.dp, Rose.copy(alpha = 0.20f), RoundedCornerShape(10.dp))
                    .clickable {
                        val intent = android.content.Intent(android.content.Intent.ACTION_SENDTO).apply {
                            data = android.net.Uri.parse("mailto:")
                            putExtra(android.content.Intent.EXTRA_EMAIL, arrayOf("dev.bismaya@gmail.com"))
                            putExtra(android.content.Intent.EXTRA_SUBJECT, "MM-DL Bug Report")
                            putExtra(android.content.Intent.EXTRA_TEXT,
                                "Hi Bismaya,\n\nI found a bug in MM-DL:\n\n[Describe the issue here]\n\nDevice: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\nAndroid: ${android.os.Build.VERSION.RELEASE}\nApp version: $versionName\n")
                        }
                        if (intent.resolveActivity(context.packageManager) != null) {
                            context.startActivity(intent)
                        }
                    }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.BugReport,
                    contentDescription = null,
                    tint = Rose,
                    modifier = Modifier.size(18.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Report a Bug",
                        color = Rose,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "dev.bismaya@gmail.com",
                        color = Rose.copy(alpha = 0.6f),
                        fontSize = 11.sp
                    )
                }
                Icon(
                    imageVector = Icons.Outlined.ChevronRight,
                    contentDescription = null,
                    tint = Rose.copy(alpha = 0.5f),
                    modifier = Modifier.size(16.dp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // General feedback button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Emerald.copy(alpha = 0.08f))
                    .border(1.dp, Emerald.copy(alpha = 0.20f), RoundedCornerShape(10.dp))
                    .clickable { uriHandler.openUri("https://mediadl.bismaya.xyz/feedback") }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.RateReview,
                    contentDescription = null,
                    tint = Emerald,
                    modifier = Modifier.size(18.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Send Feedback",
                        color = Emerald,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "Suggestions, ideas, feature requests",
                        color = Emerald.copy(alpha = 0.6f),
                        fontSize = 11.sp
                    )
                }
                Icon(
                    imageVector = Icons.Outlined.OpenInNew,
                    contentDescription = null,
                    tint = Emerald.copy(alpha = 0.5f),
                    modifier = Modifier.size(16.dp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Crash Debugger button
            val crashLogCount = remember { CrashLogger.getCrashLogs(context).size }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Amber.copy(alpha = 0.08f))
                    .border(1.dp, Amber.copy(alpha = 0.20f), RoundedCornerShape(10.dp))
                    .clickable { showCrashDebugger = true }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.BugReport,
                    contentDescription = null,
                    tint = Amber,
                    modifier = Modifier.size(18.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Crash Debugger",
                        color = Amber,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "View, share & copy crash logs",
                        color = Amber.copy(alpha = 0.6f),
                        fontSize = 11.sp
                    )
                }
                if (crashLogCount > 0) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Rose.copy(alpha = 0.15f))
                            .padding(horizontal = 7.dp, vertical = 2.dp)
                    ) {
                        Text(
                            "$crashLogCount",
                            color = Rose,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Icon(
                    imageVector = Icons.Outlined.ChevronRight,
                    contentDescription = null,
                    tint = Amber.copy(alpha = 0.5f),
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Legal section
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Ink2.copy(alpha = 0.6f))
                .border(1.dp, SurfaceBorder.copy(alpha = 0.10f), RoundedCornerShape(14.dp))
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Gavel,
                    contentDescription = null,
                    tint = Amber,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Legal", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(modifier = Modifier.height(12.dp))

            AboutLinkRow(
                icon = Icons.Outlined.Description,
                label = "Terms of Use",
                color = TextSecondary
            ) { uriHandler.openUri("https://mediadl.bismaya.xyz/terms.html") }

            AboutLinkRow(
                icon = Icons.Outlined.Shield,
                label = "Privacy Policy",
                color = TextSecondary
            ) { uriHandler.openUri("https://mediadl.bismaya.xyz/privacy.html") }

            AboutLinkRow(
                icon = Icons.Outlined.Copyright,
                label = "DMCA / Copyright",
                color = TextSecondary
            ) { uriHandler.openUri("https://mediadl.bismaya.xyz/dmca.html") }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Copyright footer
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "MM-DL \u00A9 2026 \u00B7 Developed by Bismaya",
                color = TextTertiary.copy(alpha = 0.5f),
                fontSize = 11.sp
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "All brand logos, trademarks, and images belong to their\nrespective owners. MM-DL is not affiliated with any platform.",
                color = TextTertiary.copy(alpha = 0.35f),
                fontSize = 10.sp,
                lineHeight = 14.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

// --- About Link Row (reusable) --------------------------------------------------
@Composable
fun AboutLinkRow(
    icon: ImageVector,
    label: String,
    color: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color.copy(alpha = 0.6f),
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            label,
            color = color,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
        Icon(
            imageVector = Icons.Outlined.ChevronRight,
            contentDescription = null,
            tint = TextTertiary.copy(alpha = 0.4f),
            modifier = Modifier.size(16.dp)
        )
    }
}

// --- Footer (compact - details moved to About screen) ---------------------------
@Composable
fun FooterSection() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 48.dp)
    ) {
        HorizontalDivider(color = SurfaceBorder, thickness = 0.5.dp)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Ink.copy(alpha = 0.6f))
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(Brush.linearGradient(listOf(Violet, Color(0xFF5A8AF5)))),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Download,
                        contentDescription = "MM-DL",
                        tint = Color.White,
                        modifier = Modifier.size(13.dp)
                    )
                }
                Spacer(modifier = Modifier.width(7.dp))
                Text("MM", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text("-DL", color = VioletLight, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text(
                    " \u00B7 Developed by Bismaya",
                    color = TextTertiary.copy(alpha = 0.5f),
                    fontSize = 11.sp
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "\u00A9 2026 Bismaya. All rights reserved.",
                color = TextTertiary.copy(alpha = 0.35f),
                fontSize = 10.sp
            )
        }
    }
}

// --- Common yt-dlp options for maximum site compatibility -----------------------
internal fun applyCommonOptions(request: YoutubeDLRequest, strategy: Int = 0) {
    val userAgents = listOf(
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.6778.135 Mobile Safari/537.36",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.6 Safari/605.1.15"
    )
    request.addOption("--user-agent", userAgents[strategy % userAgents.size])

    // Browser headers
    request.addOption("--add-header", "Accept:text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
    request.addOption("--add-header", "Accept-Language:en-US,en;q=0.9")
    request.addOption("--add-header", "Sec-Fetch-Mode:navigate")
    request.addOption("--add-header", "Sec-Fetch-Site:none")
    request.addOption("--add-header", "Sec-Fetch-Dest:document")
    request.addOption("--add-header", "Upgrade-Insecure-Requests:1")

    // Auto-set referer to the site itself (helps many protected sites)
    request.addOption("--referer", inferReferer(request))

    // Network resilience — generous timeout for Cloudflare challenges
    request.addOption("--socket-timeout", if (strategy == 0) "90" else "120")
    request.addOption("--retries", "10")
    request.addOption("--extractor-retries", "5")
    request.addOption("--fragment-retries", "10")
    request.addOption("--retry-sleep", "exp=1:2:30")

    // Certificate and TLS
    request.addOption("--no-check-certificates")
    if (strategy >= 1) request.addOption("--legacy-server-connect")
    if (strategy >= 2) request.addOption("--prefer-insecure")

    // IPv4 on first attempt; allow system choice on retries
    if (strategy == 0) request.addOption("--force-ipv4")

    // Errors & geo
    request.addOption("--ignore-errors")
    request.addOption("--geo-bypass")

    // Prevent partial .part files that cause issues on Android storage
    request.addOption("--no-part")

    // Sanitize filenames for Android filesystem
    request.addOption("--windows-filenames")
    request.addOption("--trim-filenames", "200")
}

internal fun inferReferer(request: YoutubeDLRequest): String {
    // yt-dlp's YoutubeDLRequest doesn't expose the URL easily,
    // so we return a generic empty string (yt-dlp will auto-fill for most extractors)
    return ""
}

/** Apply common options to a request, also setting --referer to the given URL's origin */
internal fun applyCommonOptionsWithUrl(request: YoutubeDLRequest, url: String, strategy: Int = 0) {
    applyCommonOptions(request, strategy)
    // Override referer with the actual site origin
    try {
        val uri = Uri.parse(url)
        val origin = "${uri.scheme}://${uri.host}/"
        request.addOption("--referer", origin)
    } catch (_: Exception) { }
}

// Strip WARNING/verbose lines, extract just the core error for the user
internal fun cleanErrorMessage(raw: String): String {
    val lines = raw.lines()
    val errorLine = lines.firstOrNull { it.trimStart().startsWith("ERROR:") }
    if (errorLine != null) {
        var clean = errorLine.replace(Regex("^\\s*ERROR:\\s*"), "").trim()
        clean = clean.replace(Regex("^\\[\\w+]\\s*"), "")
        return clean.ifBlank { raw.take(200) }
    }
    val filtered = lines
        .filter { !it.trimStart().startsWith("WARNING:") }
        .joinToString("\n").trim()
    return filtered.ifBlank { raw }.take(200)
}

internal fun needsImpersonation(errText: String): Boolean =
    "impersonat" in errText.lowercase() || "no impersonate target" in errText.lowercase()

// --- Fetch Video Info via yt-dlp (multi-strategy) -------------------------------

/** Try up to 3 strategies with progressive fallback options */
internal fun fetchVideoInfo(url: String): VideoInfo {
    var lastError: Exception? = null
    for (strategy in 0..2) {
        try {
            return fetchVideoInfoOnce(url, strategy)
        } catch (e: Exception) {
            lastError = e
            val msg = e.message ?: ""
            // If it's "Unsupported URL", no point retrying with different strategy
            if ("Unsupported URL" in msg || "is not a valid URL" in msg) throw e
            // If impersonation is required and we've tried all strategies, give up early
            if (strategy == 2 && needsImpersonation(msg)) {
                throw Exception("This site requires browser impersonation which is not yet supported on Android. Try YouTube, Instagram, TikTok, Twitter/X, Facebook, or other major platforms.")
            }
            // Otherwise continue to next strategy
        }
    }
    throw lastError ?: Exception("Failed to fetch video info")
}

internal fun fetchVideoInfoOnce(url: String, strategy: Int): VideoInfo {
    val request = YoutubeDLRequest(url)
    applyCommonOptionsWithUrl(request, url, strategy)
    request.addOption("--dump-json")
    request.addOption("--no-playlist")
    request.addOption("--no-download")

    val response = YoutubeDL.getInstance().execute(request, null, null)
    val output = response.out
    if (output.isNullOrBlank()) {
        val errMsg = response.err?.takeIf { it.isNotBlank() } ?: "No data returned"
        throw Exception(errMsg)
    }

    val json = JSONObject(output)

    val title = json.optString("title", "Untitled Video")
    val uploader = json.optString("uploader", json.optString("channel", "Unknown"))
    val durationSec = json.optInt("duration", 0)
    val durationStr = json.optString("duration_string", "").ifBlank {
        if (durationSec > 0) {
            val h = durationSec / 3600; val m = (durationSec % 3600) / 60; val s = durationSec % 60
            if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
        } else ""
    }
    val thumbnail = json.optString("thumbnail", "")
    val viewCount = json.optLong("view_count", 0)
    val viewCountStr = if (viewCount > 0) formatNumber(viewCount) else ""

    val formatsArray = json.optJSONArray("formats")
    val formats = mutableListOf<FormatOption>()
    val videoSeen = mutableSetOf<String>()

    formats.add(FormatOption("bestvideo+bestaudio[acodec^=mp4a]/bestvideo+bestaudio/best", "Best Quality - Auto Merged [MP4]", "video", "BEST", 9999))

    if (formatsArray != null) {
        for (i in 0 until formatsArray.length()) {
            val f = formatsArray.getJSONObject(i)
            val vcodec = f.optString("vcodec", "none")
            val acodec = f.optString("acodec", "none")
            val height = f.optInt("height", 0)
            val fps = f.optInt("fps", 30)
            val ext = f.optString("ext", "mp4")
            val fmtId = f.optString("format_id", "")

            if (vcodec != "none" && height > 0 && acodec == "none") {
                val fpsBucket = if (fps > 30) "hfr" else "std"
                val codec = simplifyCodec(vcodec)
                val key = "${height}_${codec}_$fpsBucket"
                if (videoSeen.contains(key)) continue
                videoSeen.add(key)
                val fpsLabel = if (fps > 30) " ${fps}fps" else ""
                val qualityTag = getQualityTag(height)
                val filesize = f.optLong("filesize", f.optLong("filesize_approx", 0))
                val sizeLabel = if (filesize > 0) " \u00B7 ~${formatBytes(filesize)}" else ""
                // Prefer AAC audio for H.264/H.265 to ensure clean MP4 merge (opus→MP4 can fail)
                val audioSelector = if (codec == "H.264" || codec == "H.265") {
                    "bestaudio[acodec^=mp4a]/bestaudio"
                } else {
                    "bestaudio"
                }
                formats.add(FormatOption("$fmtId+$audioSelector", "${height}p$fpsLabel \u00B7 $codec [${ext.uppercase()}]$sizeLabel", "video", qualityTag, height))
            }

            if (vcodec != "none" && acodec != "none" && height > 0) {
                val fpsBucket = if (fps > 30) "hfr" else "std"
                val codec = simplifyCodec(vcodec)
                val key = "${height}_${codec}_$fpsBucket"
                if (videoSeen.contains(key)) continue
                videoSeen.add(key)
                val fpsLabel = if (fps > 30) " ${fps}fps" else ""
                val qualityTag = getQualityTag(height)
                val filesize = f.optLong("filesize", f.optLong("filesize_approx", 0))
                val sizeLabel = if (filesize > 0) " \u00B7 ~${formatBytes(filesize)}" else ""
                formats.add(FormatOption(fmtId, "${height}p$fpsLabel \u00B7 $codec [${ext.uppercase()}]$sizeLabel", "video", qualityTag, height))
            }
        }

        val audioSeen = mutableSetOf<String>()
        for (i in 0 until formatsArray.length()) {
            val f = formatsArray.getJSONObject(i)
            val vcodec = f.optString("vcodec", "none")
            val acodec = f.optString("acodec", "none")
            if (acodec != "none" && vcodec == "none") {
                val codec = simplifyCodec(acodec)
                val bitrate = f.optInt("abr", f.optInt("tbr", 0))
                val key = "${codec}_$bitrate"
                if (audioSeen.contains(key)) continue
                audioSeen.add(key)
                val fmtId = f.optString("format_id", "")
                val filesize = f.optLong("filesize", f.optLong("filesize_approx", 0))
                val sizeLabel = if (filesize > 0) " \u00B7 ~${formatBytes(filesize)}" else ""
                val bitrateLabel = if (bitrate > 0) " ${bitrate}kbps" else ""
                formats.add(FormatOption(fmtId, "Audio \u00B7 $codec$bitrateLabel$sizeLabel", "audio", "\u266A"))
                if (formats.count { it.type == "audio" } >= 6) break
            }
        }
    }

    val audioInsertIndex = formats.indexOfFirst { it.type == "audio" }.let { if (it < 0) formats.size else it }
    formats.add(audioInsertIndex, FormatOption("__extract_mp3", "Extract Audio \u00B7 MP3 (Best Quality)", "audio", "\u266A"))
    formats.add(audioInsertIndex + 1, FormatOption("__extract_m4a", "Extract Audio \u00B7 M4A/AAC (Best Quality)", "audio", "\u266A"))

    val videoPart = formats.filter { it.type == "video" }.sortedByDescending { it.height }
    val audioPart = formats.filter { it.type == "audio" }

    return VideoInfo(title, uploader, durationStr, thumbnail, viewCountStr, videoPart + audioPart, url)
}

internal fun simplifyCodec(codec: String): String = when {
    codec == "none" -> ""
    Regex("avc|h\\.?264", RegexOption.IGNORE_CASE).containsMatchIn(codec) -> "H.264"
    Regex("hev|h\\.?265|hevc", RegexOption.IGNORE_CASE).containsMatchIn(codec) -> "H.265"
    Regex("vp0?9", RegexOption.IGNORE_CASE).containsMatchIn(codec) -> "VP9"
    Regex("av0?1", RegexOption.IGNORE_CASE).containsMatchIn(codec) -> "AV1"
    Regex("opus", RegexOption.IGNORE_CASE).containsMatchIn(codec) -> "Opus"
    Regex("aac|mp4a", RegexOption.IGNORE_CASE).containsMatchIn(codec) -> "AAC"
    Regex("mp3", RegexOption.IGNORE_CASE).containsMatchIn(codec) -> "MP3"
    Regex("vorbis", RegexOption.IGNORE_CASE).containsMatchIn(codec) -> "Vorbis"
    Regex("flac", RegexOption.IGNORE_CASE).containsMatchIn(codec) -> "FLAC"
    else -> codec.split(".").first().uppercase()
}

internal fun formatBytes(bytes: Long): String = when {
    bytes < 1024 * 1024 -> "${bytes / 1024}KB"
    bytes < 1024 * 1024 * 1024 -> "${"%.1f".format(bytes / (1024.0 * 1024.0))}MB"
    else -> "${"%.2f".format(bytes / (1024.0 * 1024.0 * 1024.0))}GB"
}