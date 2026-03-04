package com.bismaya.mediadl

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.PowerManager
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bismaya.mediadl.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Convert a DocumentTree URI (content://...) to an absolute file path
private fun treeUriToPath(context: Context, treeUri: Uri): String? {
    return try {
        val docId = DocumentsContract.getTreeDocumentId(treeUri) ?: return null
        val split = docId.split(":")
        if (split.size < 2) return null
        val type = split[0]
        val relative = split[1]
        if (type.equals("primary", ignoreCase = true)) {
            Environment.getExternalStorageDirectory().absolutePath + if (relative.isNotBlank()) "/$relative" else ""
        } else {
            "/storage/$type/$relative"
        }
    } catch (_: Exception) { null }
}

// ══════════════════════════════════════════════════════════════════════════════════
// TORRENT SCREEN — Main composable with inner tabs
// ══════════════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TorrentScreen(
    vm: TorrentViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val torrentFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { vm.addTorrentFromUri(it) } }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        // ── Header ──
        TorrentHeader(
            isRunning = vm.isEngineRunning,
            onSettingsClick = { vm.showSettings = true }
        )

        // ── Error banner ──
        AnimatedBanner(
            visible = vm.errorMessage != null,
            text = vm.errorMessage ?: "",
            color = Rose,
            icon = Icons.Outlined.ErrorOutline,
            onDismiss = { vm.dismissError() }
        )

        // ── Status banner ──
        AnimatedBanner(
            visible = vm.statusMessage != null,
            text = vm.statusMessage ?: "",
            color = Cyan,
            icon = Icons.Outlined.Info,
            onDismiss = null
        )

        // ── Battery optimization banner ──
        if (vm.needsBatteryOptPrompt) {
            val batteryCtx = LocalContext.current
            AnimatedBanner(
                visible = true,
                text = "Allow background running so torrents continue when app is closed",
                color = Amber,
                icon = Icons.Outlined.BatteryAlert,
                onDismiss = { vm.dismissBatteryOptPrompt(permanent = false) },
                actionLabel = "Allow",
                onAction = {
                    val intent = Intent(
                        android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:${batteryCtx.packageName}")
                    )
                    batteryCtx.startActivity(intent)
                    vm.dismissBatteryOptPrompt(permanent = true)
                }
            )
        }

        // ── Inner tab bar ──
        TorrentTabBar(
            activeTab = vm.activeTab,
            onTabSelect = { vm.activeTab = it },
            activeCount = vm.torrents.count {
                it.state == TorrentState.DOWNLOADING ||
                it.state == TorrentState.METADATA ||
                it.state == TorrentState.CHECKING
            }
        )

        Spacer(modifier = Modifier.height(8.dp))

        // ── Tab content ──
        when (vm.activeTab) {
            TorrentTab.ACTIVE -> ActiveTabContent(
                vm = vm,
                onPickFile = { torrentFileLauncher.launch(arrayOf("application/x-bittorrent", "*/*")) }
            )
            TorrentTab.SEARCH -> SearchTabContent(vm = vm)
            TorrentTab.FILES -> {
                // Refresh file list when switching to files tab
                LaunchedEffect(vm.activeTab) { vm.loadBrowseDir() }
                FileBrowserContent(
                    currentPath = vm.currentBrowsePath,
                    items = vm.browseItems,
                    canGoUp = vm.canGoUp,
                    onNavigate = { vm.navigateTo(it) },
                    onGoUp = { vm.navigateUp() },
                    onOpen = { vm.openBrowseFile(context, it) },
                    onDelete = { vm.deleteBrowseFile(it) },
                    onShare = { vm.shareBrowseFile(context, it) },
                    onRename = { item, name -> vm.renameBrowseFile(item, name) },
                    onDeleteMultiple = { fileItems -> fileItems.forEach { vm.deleteBrowseFile(it) } }
                )
            }
        }

        Spacer(modifier = Modifier.height(100.dp))
    }

    // ── Delete confirmation dialog ──
    if (vm.pendingDeleteHash != null) {
        val name = vm.torrents.find { it.infoHash == vm.pendingDeleteHash }?.name ?: "this torrent"
        AlertDialog(
            onDismissRequest = { vm.cancelDelete() },
            containerColor = Ink3,
            titleContentColor = TextPrimary,
            textContentColor = TextSecondary,
            title = { Text("Remove Torrent", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("Remove \"$name\"?", fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { vm.pendingDeleteFiles = !vm.pendingDeleteFiles }
                            .padding(vertical = 4.dp)
                    ) {
                        Checkbox(
                            checked = vm.pendingDeleteFiles,
                            onCheckedChange = { vm.pendingDeleteFiles = it },
                            colors = CheckboxDefaults.colors(
                                checkedColor = Rose,
                                uncheckedColor = TextTertiary,
                                checkmarkColor = TextPrimary
                            )
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            "Also delete downloaded files",
                            color = if (vm.pendingDeleteFiles) Rose else TextSecondary,
                            fontSize = 13.sp
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.confirmDelete(vm.pendingDeleteFiles) }) {
                    Text("Remove", color = Rose, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { vm.cancelDelete() }) {
                    Text("Cancel", color = TextTertiary)
                }
            }
        )
    }

    // ── Settings bottom sheet ──
    if (vm.showSettings) {
        TorrentSettingsSheet(
            settings = vm.settings,
            onDismiss = { vm.showSettings = false },
            onSave = { vm.updateSettings(it); vm.showSettings = false }
        )
    }
}

// ══════════════════════════════════════════════════════════════════════════════════
// HEADER
// ══════════════════════════════════════════════════════════════════════════════════

@Composable
private fun TorrentHeader(isRunning: Boolean, onSettingsClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Torrents",
                color = TextPrimary,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-1).sp
            )
            Spacer(modifier = Modifier.width(10.dp))
            val statusColor = if (isRunning) Emerald else Rose
            val statusText = if (isRunning) "Active" else "Offline"
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(statusColor.copy(alpha = 0.1f))
                    .border(1.dp, statusColor.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(statusText, color = statusColor, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = onSettingsClick, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Outlined.Settings, contentDescription = "Settings", tint = TextTertiary, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text("Search, download, and manage torrents", color = TextTertiary, fontSize = 13.sp)
    }
}

// ══════════════════════════════════════════════════════════════════════════════════
// INNER TAB BAR
// ══════════════════════════════════════════════════════════════════════════════════

@Composable
private fun TorrentTabBar(activeTab: TorrentTab, onTabSelect: (TorrentTab) -> Unit, activeCount: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        TorrentTab.entries.forEach { tab ->
            val isActive = tab == activeTab
            val chipColor = if (isActive) VioletLight else TextTertiary
            val chipBg = if (isActive) Violet.copy(alpha = 0.15f) else Ink2

            val icon = when (tab) {
                TorrentTab.ACTIVE -> Icons.Outlined.Download
                TorrentTab.SEARCH -> Icons.Outlined.Search
                TorrentTab.FILES -> Icons.Outlined.Folder
            }

            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(chipBg)
                    .border(
                        1.dp,
                        if (isActive) Violet.copy(alpha = 0.3f) else SurfaceBorder.copy(alpha = 0.1f),
                        RoundedCornerShape(12.dp)
                    )
                    .clickable { onTabSelect(tab) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(icon, contentDescription = tab.label, tint = chipColor, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(tab.label, color = chipColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                if (tab == TorrentTab.ACTIVE && activeCount > 0) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(Cyan.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "$activeCount",
                            color = Cyan,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            lineHeight = 10.sp,
                            modifier = Modifier.offset(y = (-0.5).dp)
                        )
                    }
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════════════
// ACTIVE TAB — active torrents + input
// ══════════════════════════════════════════════════════════════════════════════════

@Composable
private fun ActiveTabContent(vm: TorrentViewModel, onPickFile: () -> Unit) {
    val context = LocalContext.current

    // ── Input bar ──
    TorrentInputBar(
        value = vm.magnetInput,
        onValueChange = { vm.onMagnetInputChange(it) },
        onSubmit = { vm.addMagnet() },
        onPickFile = onPickFile,
        isValid = vm.isMagnetValid(),
        modifier = Modifier.padding(horizontal = 20.dp)
    )

    Spacer(modifier = Modifier.height(8.dp))

    // ── Save location ──
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Outlined.FolderOpen, null, tint = TextTertiary, modifier = Modifier.size(14.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            if (vm.settings.savePath.isNotBlank()) vm.settings.savePath.replace("/storage/emulated/0/", "")
            else "Private storage · Torrents (app-only)",
            color = TextTertiary, fontSize = 12.sp
        )
    }

    Spacer(modifier = Modifier.height(16.dp))

    if (vm.torrents.isEmpty()) {
        // ── Empty state ──
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = 60.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(Icons.Outlined.Downloading, null, tint = TextTertiary, modifier = Modifier.size(48.dp))
            Spacer(modifier = Modifier.height(16.dp))
            Text("No Torrents", color = TextSecondary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Paste a magnet link, open a .torrent file,\nor search for torrents to start downloading.",
                color = TextTertiary, fontSize = 13.sp, textAlign = TextAlign.Center, lineHeight = 20.sp
            )
        }
    } else {
        // ── Stats row ──
        val downloading = vm.torrents.count {
            it.state == TorrentState.DOWNLOADING || it.state == TorrentState.METADATA
        }
        val completed = vm.torrents.count {
            it.state == TorrentState.FINISHED || it.state == TorrentState.SEEDING
        }
        val totalDown = vm.torrents.sumOf { it.downloadSpeed }
        val totalUp = vm.torrents.sumOf { it.uploadSpeed }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TorrentStatPill(Icons.Outlined.Download, "$downloading active", Cyan, Modifier.weight(1f))
            TorrentStatPill(Icons.Outlined.CheckCircle, "$completed done", Emerald, Modifier.weight(1f))
            TorrentStatPill(Icons.Outlined.Speed, "${formatSpeed(totalDown)}/s", VioletLight, Modifier.weight(1f))
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ── Torrent cards ──
        vm.torrents.forEach { torrent ->
            TorrentCard(
                item = torrent,
                isExpanded = vm.expandedHash == torrent.infoHash,
                onToggleExpand = { vm.toggleExpanded(torrent.infoHash) },
                onPause = { vm.pauseTorrent(torrent.infoHash) },
                onResume = { vm.resumeTorrent(torrent.infoHash) },
                onDelete = { vm.requestDelete(torrent.infoHash) },
                onToggleSequential = { vm.toggleSequential(torrent.infoHash) },
                onToggleFile = { idx -> vm.toggleFileDownload(torrent.infoHash, idx) },
                onShare = { vm.shareMagnet(context, torrent.infoHash) },
                onOpen = { vm.openFile(context, torrent) },
                vm = vm,
                modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 12.dp)
            )
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════════════
// SEARCH TAB
// ══════════════════════════════════════════════════════════════════════════════════

@Composable
private fun SearchTabContent(vm: TorrentViewModel) {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current

    // ── Search input ──
    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Ink2)
                .border(1.dp, SurfaceBorder.copy(alpha = 0.15f), RoundedCornerShape(18.dp))
                .padding(6.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.Search, null,
                    tint = TextTertiary,
                    modifier = Modifier.padding(start = 8.dp).size(20.dp)
                )
                BasicTextField(
                    value = vm.searchQuery,
                    onValueChange = { vm.onSearchQueryChange(it) },
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(color = TextPrimary, fontSize = 14.sp),
                    cursorBrush = SolidColor(VioletLight),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); vm.searchTorrents() }),
                    modifier = Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 10.dp),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (vm.searchQuery.isEmpty()) {
                                Text("Search torrents…", color = TextTertiary, fontSize = 14.sp)
                            }
                            inner()
                        }
                    }
                )
                if (vm.searchQuery.isNotEmpty()) {
                    IconButton(onClick = { vm.onSearchQueryChange("") }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Rounded.Close, "Clear", tint = TextTertiary, modifier = Modifier.size(16.dp))
                    }
                }
                val searchEnabled = vm.searchQuery.isNotBlank() && !vm.isSearching
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(if (searchEnabled) Violet else Violet.copy(alpha = 0.3f))
                        .clickable(enabled = searchEnabled) { keyboard?.hide(); vm.searchTorrents() },
                    contentAlignment = Alignment.Center
                ) {
                    if (vm.isSearching) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = TextPrimary,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(Icons.Rounded.Search, "Search", tint = if (searchEnabled) TextPrimary else TextTertiary, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(12.dp))

    // ── Search error ──
    vm.searchError?.let { error ->
        Row(
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Rose.copy(alpha = 0.08f))
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Outlined.ErrorOutline, null, tint = Rose, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(10.dp))
            Text(error, color = Rose.copy(alpha = 0.85f), fontSize = 13.sp)
        }
        Spacer(modifier = Modifier.height(12.dp))
    }

    // ── Results ──
    if (vm.searchResults.isEmpty() && !vm.isSearching && vm.searchError == null) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = 60.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(Icons.Outlined.TravelExplore, null, tint = TextTertiary, modifier = Modifier.size(48.dp))
            Spacer(modifier = Modifier.height(16.dp))
            Text("Search Torrents", color = TextSecondary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Type a query and search for torrents.\nResults are fetched from public indexes.",
                color = TextTertiary, fontSize = 13.sp, textAlign = TextAlign.Center, lineHeight = 20.sp
            )
        }
    } else if (vm.searchResults.isNotEmpty()) {

        // ── Filter chips ──
        val categories = listOf("All", "Video", "Audio", "Games", "Apps", "Other")
        val sizeFilters = listOf("Any", "<100 MB", "100 MB–1 GB", ">1 GB")

        // Category filter row
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(categories) { cat ->
                val selected = vm.searchCategoryFilter == cat
                val chipColor = when (cat) {
                    "Video" -> Cyan; "Audio" -> VioletLight; "Games" -> Emerald; "Apps" -> Amber; "Other" -> TextTertiary; else -> Violet
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (selected) chipColor else chipColor.copy(alpha = 0.08f))
                        .border(1.dp, chipColor.copy(alpha = if (selected) 0f else 0.25f), RoundedCornerShape(20.dp))
                        .clickable { vm.searchCategoryFilter = cat }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(cat, color = if (selected) TextPrimary else chipColor, fontSize = 12.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Size filter row
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(sizeFilters) { sf ->
                val selected = vm.searchSizeFilter == sf
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (selected) Violet else Violet.copy(alpha = 0.08f))
                        .border(1.dp, Violet.copy(alpha = if (selected) 0f else 0.2f), RoundedCornerShape(20.dp))
                        .clickable { vm.searchSizeFilter = sf }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(sf, color = if (selected) TextPrimary else VioletLight, fontSize = 12.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Apply filters
        val filteredResults = vm.searchResults.filter { r ->
            val catOk = vm.searchCategoryFilter == "All" || r.category == vm.searchCategoryFilter
            val sizeOk = when (vm.searchSizeFilter) {
                "<100 MB"       -> r.size < 100L * 1024 * 1024
                "100 MB–1 GB"   -> r.size in (100L * 1024 * 1024)..(1024L * 1024 * 1024)
                ">1 GB"         -> r.size > 1024L * 1024 * 1024
                else            -> true
            }
            catOk && sizeOk
        }

        Text(
            "${filteredResults.size} results${if (filteredResults.size != vm.searchResults.size) " (filtered from ${vm.searchResults.size})" else ""}",
            color = TextTertiary,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 20.dp)
        )
        Spacer(modifier = Modifier.height(8.dp))

        filteredResults.forEach { result ->
            SearchResultCard(
                result = result,
                onDownload = { vm.addFromSearchResult(result) },
                onOpenExternal = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(result.magnetUri)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    try {
                        context.startActivity(Intent.createChooser(intent, "Open magnet with…").apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        })
                    } catch (_: Exception) {}
                },
                modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 10.dp)
            )
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════════════
// SEARCH RESULT CARD
// ══════════════════════════════════════════════════════════════════════════════════

@Composable
private fun SearchResultCard(
    result: TorrentSearchResult,
    onDownload: () -> Unit,
    onOpenExternal: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val catColor = when (result.category) {
        "Video" -> Cyan
        "Audio" -> VioletLight
        "Games" -> Emerald
        "Apps" -> Amber
        else -> TextTertiary
    }
    val catIcon = when (result.category) {
        "Video" -> Icons.Outlined.Videocam
        "Audio" -> Icons.Outlined.MusicNote
        "Games" -> Icons.Outlined.SportsEsports
        "Apps" -> Icons.Outlined.Apps
        else -> Icons.Outlined.InsertDriveFile
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
        border = BorderStroke(1.dp, catColor.copy(alpha = 0.1f)),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(catColor.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(catIcon, null, tint = catColor, modifier = Modifier.size(18.dp))
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        result.name,
                        color = TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(catColor.copy(alpha = 0.1f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(result.category, color = catColor, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                        }
                        if (result.source.isNotBlank()) {
                            val srcColor = when (result.source) {
                                "YTS"     -> Amber
                                "TPB"     -> VioletLight
                                "Knaben"  -> Color(0xFF29B6F6)
                                "TorrCSV" -> Emerald
                                "Solid"   -> Color(0xFFAB47BC)
                                "Nyaa"    -> Rose
                                else      -> TextTertiary
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(srcColor.copy(alpha = 0.1f))
                                    .border(0.5.dp, srcColor.copy(alpha = 0.35f), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 5.dp, vertical = 2.dp)
                            ) {
                                Text(result.source, color = srcColor, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            TorrentSearchProvider.formatFileSize(result.size),
                            color = TextTertiary,
                            fontSize = 11.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Seeders
                Icon(Icons.Outlined.ArrowUpward, null, tint = Emerald, modifier = Modifier.size(12.dp))
                Spacer(modifier = Modifier.width(3.dp))
                Text("${result.seeders}", color = Emerald, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.width(10.dp))

                // Leechers
                Icon(Icons.Outlined.ArrowDownward, null, tint = Rose, modifier = Modifier.size(12.dp))
                Spacer(modifier = Modifier.width(3.dp))
                Text("${result.leechers}", color = Rose, fontSize = 11.sp, fontWeight = FontWeight.Bold)

                Spacer(modifier = Modifier.weight(1f))

                // Open in external torrent app (µTorrent, BitTorrent, TorrDroid, etc.)
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Cyan.copy(alpha = 0.12f))
                        .border(1.dp, Cyan.copy(alpha = 0.25f), RoundedCornerShape(8.dp))
                        .clickable { onOpenExternal() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Outlined.OpenInNew, "Open in external app", tint = Cyan, modifier = Modifier.size(16.dp))
                }
                Spacer(modifier = Modifier.width(8.dp))
                // Download in-app button
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Violet.copy(alpha = 0.15f))
                        .border(1.dp, Violet.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                        .clickable { onDownload() }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.Download, null, tint = VioletLight, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Download", color = VioletLight, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════════════
// TORRENT INPUT BAR
// ══════════════════════════════════════════════════════════════════════════════════

@Composable
private fun TorrentInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onPickFile: () -> Unit,
    isValid: Boolean,
    modifier: Modifier = Modifier
) {
    val borderColor by animateColorAsState(
        when {
            value.isNotBlank() && isValid -> Emerald.copy(alpha = 0.3f)
            value.isNotBlank() && !isValid -> Rose.copy(alpha = 0.2f)
            else -> SurfaceBorder.copy(alpha = 0.15f)
        }, tween(350), label = "inputBorder"
    )

    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Ink2)
                .border(1.dp, borderColor, RoundedCornerShape(18.dp))
                .padding(6.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPickFile, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Outlined.AttachFile, "Open .torrent file", tint = TextTertiary, modifier = Modifier.size(20.dp))
                }

                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(color = TextPrimary, fontSize = 14.sp),
                    cursorBrush = SolidColor(VioletLight),
                    modifier = Modifier.weight(1f),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (value.isEmpty()) Text("Paste magnet link or torrent URL…", color = TextTertiary, fontSize = 14.sp)
                            inner()
                        }
                    }
                )

                val addEnabled = value.isNotBlank() && isValid
                val addBg by animateColorAsState(
                    if (addEnabled) Violet else Violet.copy(alpha = 0.3f), tween(250), label = "addBg"
                )
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(addBg)
                        .clickable(enabled = addEnabled) { onSubmit() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.Add, "Add torrent", tint = if (addEnabled) TextPrimary else TextTertiary, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════════════
// TORRENT CARD — enhanced with sequential, file selection, share, open
// ══════════════════════════════════════════════════════════════════════════════════

@Composable
private fun TorrentCard(
    item: TorrentItem,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onDelete: () -> Unit,
    onToggleSequential: () -> Unit,
    onToggleFile: (Int) -> Unit,
    onShare: () -> Unit,
    onOpen: () -> Unit,
    vm: TorrentViewModel,
    modifier: Modifier = Modifier
) {
    val stateColor = when (item.state) {
        TorrentState.DOWNLOADING, TorrentState.METADATA -> Cyan
        TorrentState.FINISHED, TorrentState.SEEDING -> Emerald
        TorrentState.PAUSED -> if (item.infoHash in vm.autoPausedForSeeding) Emerald else Amber
        TorrentState.ERROR -> Rose
        TorrentState.CHECKING -> VioletLight
        TorrentState.MOVING -> Amber
        TorrentState.QUEUED -> TextTertiary
        TorrentState.STOPPED, TorrentState.UNKNOWN -> TextTertiary
    }

    val stateLabel = when (item.state) {
        TorrentState.DOWNLOADING -> "Downloading"
        TorrentState.METADATA -> "Getting metadata…"
        TorrentState.FINISHED -> "Completed"
        TorrentState.SEEDING -> "Seeding"
        TorrentState.PAUSED -> if (item.infoHash in vm.autoPausedForSeeding) "Completed" else "Paused"
        TorrentState.ERROR -> "Error"
        TorrentState.CHECKING -> "Checking"
        TorrentState.QUEUED -> "Queued"
        TorrentState.MOVING -> "Moving…"
        TorrentState.STOPPED -> "Stopped"
        TorrentState.UNKNOWN -> "Unknown"
    }

    Card(
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { onToggleExpand() },
        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
        border = BorderStroke(1.dp, stateColor.copy(alpha = 0.12f)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // ── Top row ──
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(stateColor.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = when (item.state) {
                            TorrentState.FINISHED, TorrentState.SEEDING -> Icons.Outlined.CheckCircle
                            TorrentState.PAUSED -> if (item.infoHash in vm.autoPausedForSeeding) Icons.Outlined.CheckCircle else Icons.Outlined.PauseCircle
                            TorrentState.ERROR -> Icons.Outlined.ErrorOutline
                            TorrentState.CHECKING -> Icons.Outlined.Refresh
                            TorrentState.MOVING -> Icons.Outlined.Autorenew
                            TorrentState.QUEUED -> Icons.Outlined.Schedule
                            TorrentState.STOPPED, TorrentState.UNKNOWN -> Icons.Outlined.Block
                            else -> Icons.Outlined.Downloading
                        },
                        contentDescription = null, tint = stateColor, modifier = Modifier.size(22.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(item.name, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(modifier = Modifier.height(3.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(stateColor.copy(alpha = 0.1f)).padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(stateLabel, color = stateColor, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                        }
                        if (item.isSequential) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(
                                modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Amber.copy(alpha = 0.1f)).padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text("Sequential", color = Amber, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        if (item.totalSize > 0) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(formatBytes(item.totalSize), color = TextTertiary, fontSize = 11.sp)
                        }
                    }
                }

                Icon(
                    if (isExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    "Expand", tint = TextTertiary, modifier = Modifier.size(22.dp)
                )
            }

            // ── Progress bar ──
            val showProgress = item.state == TorrentState.DOWNLOADING ||
                    item.state == TorrentState.CHECKING ||
                    item.state == TorrentState.METADATA ||
                    item.state == TorrentState.SEEDING ||
                    item.state == TorrentState.FINISHED
            if (showProgress) {
                Spacer(modifier = Modifier.height(12.dp))
                val animatedProgress by animateFloatAsState(item.progress, tween(500), label = "torrentProgress")
                // Speed row
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("${"%.1f".format(item.progress * 100)}%", color = stateColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    if (item.state == TorrentState.SEEDING || item.state == TorrentState.FINISHED) {
                        val ratio = if (item.shareRatio > 0) "%.2f".format(item.shareRatio) else "0.00"
                        Text("↑ ${formatSpeed(item.uploadSpeed)}/s  ⋄$ratio", color = Emerald, fontSize = 11.sp)
                    } else {
                        Text("${formatSpeed(item.downloadSpeed)}/s ↓  ${formatSpeed(item.uploadSpeed)}/s ↑", color = TextTertiary, fontSize = 11.sp)
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Box(modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(stateColor.copy(alpha = 0.08f))) {
                    Box(modifier = Modifier.fillMaxHeight().fillMaxWidth(animatedProgress.coerceIn(0f, 1f)).clip(RoundedCornerShape(3.dp)).background(stateColor))
                }
                Spacer(modifier = Modifier.height(6.dp))
                // seeds·peers row / ETA
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (item.state == TorrentState.SEEDING || item.state == TorrentState.FINISHED) {
                        val seedText = if (item.totalSeeds > 0) "${item.connectedSeeds}(${item.totalSeeds})" else "${item.seeders}"
                        val peerText = if (item.totalPeers > 0) "${item.connectedPeers}(${item.totalPeers})" else "${item.peers}"
                        Text("🌱 $seedText  📤 ${formatBytes(item.totalSentBytes)}", color = TextTertiary, fontSize = 11.sp)
                        Spacer(modifier = Modifier.weight(1f))
                        Text(peerText + " peers", color = TextTertiary, fontSize = 11.sp)
                    } else {
                        val seedText = if (item.totalSeeds > 0) "${item.connectedSeeds}(${item.totalSeeds})" else "${item.seeders}"
                        val peerText = if (item.totalPeers > 0) "${item.connectedPeers}(${item.totalPeers})" else "${item.peers}"
                        Text("🌱 $seedText · 👥 $peerText", color = TextTertiary, fontSize = 11.sp)
                        Spacer(modifier = Modifier.weight(1f))
                        if (item.etaSec < Long.MAX_VALUE && item.etaSec >= 0) {
                            Text("ETA ${formatEta(item.etaSec)}", color = TextTertiary, fontSize = 11.sp)
                        } else {
                            Text("${formatBytes(item.downloadedBytes)} / ${formatBytes(item.totalSize)}", color = TextTertiary, fontSize = 11.sp)
                        }
                    }
                }
            }

            // ── Expanded content ──
            AnimatedVisibility(isExpanded, enter = fadeIn(tween(200)) + expandVertically(tween(250)), exit = fadeOut(tween(150)) + shrinkVertically(tween(200))) {
                TorrentExpandedPanel(
                    item = item,
                    onPause = onPause,
                    onResume = onResume,
                    onDelete = onDelete,
                    onToggleSequential = onToggleSequential,
                    onToggleFile = onToggleFile,
                    onShare = onShare,
                    onOpen = onOpen,
                    vm = vm
                )
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════════════
// ETA formatter
// ══════════════════════════════════════════════════════════════════════════════════

internal fun formatEta(seconds: Long): String {
    if (seconds <= 0) return "0s"
    val d = seconds / 86400
    val h = (seconds % 86400) / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return when {
        d > 0  -> "${d}d ${h}h"
        h > 0  -> "${h}h ${m}m"
        m > 0  -> "${m}m ${s}s"
        else   -> "${s}s"
    }
}

// ══════════════════════════════════════════════════════════════════════════════════
// TORRENT EXPANDED PANEL — libretorrent-style Info/Trackers/Peers tabs
// ══════════════════════════════════════════════════════════════════════════════════

@Composable
private fun TorrentExpandedPanel(
    item: TorrentItem,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onDelete: () -> Unit,
    onToggleSequential: () -> Unit,
    onToggleFile: (Int) -> Unit,
    onShare: () -> Unit,
    onOpen: () -> Unit,
    vm: TorrentViewModel
) {
    val context = LocalContext.current
    // tab selection: 0=Info, 1=Files, 2=Trackers, 3=Peers
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Info", "Files", "Trackers", "Peers")

    // lazy-loaded lists (refresh on tab switch)
    var trackers by remember { mutableStateOf<List<TrackerInfo>>(emptyList()) }
    var peers    by remember { mutableStateOf<List<PeerInfo>>(emptyList()) }

    LaunchedEffect(selectedTab, item.infoHash) {
        when (selectedTab) {
            2 -> trackers = vm.getTrackerList(item.infoHash)
            3 -> peers    = vm.getPeerList(item.infoHash)
        }
    }

    Column {
        Spacer(modifier = Modifier.height(12.dp))
        HorizontalDivider(color = SurfaceBorder.copy(alpha = 0.1f))
        Spacer(modifier = Modifier.height(10.dp))

        // ── Tab chips ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            tabs.forEachIndexed { idx, label ->
                val active = idx == selectedTab
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (active) Cyan.copy(alpha = 0.15f) else Ink2.copy(alpha = 0.5f))
                        .clickable { selectedTab = idx }
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        label,
                        color = if (active) Cyan else TextTertiary,
                        fontSize = 12.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        when (selectedTab) {
            // ── INFO tab ──
            0 -> {
                // Sequential toggle
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Ink2.copy(alpha = 0.5f))
                        .clickable { onToggleSequential() }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.Sort, null, tint = Amber, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Sequential Download", color = TextSecondary, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Switch(
                        checked = item.isSequential,
                        onCheckedChange = { onToggleSequential() },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Amber,
                            checkedTrackColor = Amber.copy(alpha = 0.3f),
                            uncheckedThumbColor = TextTertiary,
                            uncheckedTrackColor = Ink3
                        ),
                        modifier = Modifier.height(24.dp)
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Stat rows
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Ink2.copy(alpha = 0.5f))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Download/Upload
                    TorrentInfoRow(Icons.Outlined.Speed, "Speed",
                        "↓ ${formatSpeed(item.downloadSpeed)}/s  ↑ ${formatSpeed(item.uploadSpeed)}/s")

                    if (item.downloadSpeedLimit > 0 || item.uploadSpeedLimit > 0) {
                        TorrentInfoRow(Icons.Outlined.Tune, "Limits",
                            "↓ ${if (item.downloadSpeedLimit > 0) formatSpeed(item.downloadSpeedLimit.toLong()) else "∞"}/s  " +
                            "↑ ${if (item.uploadSpeedLimit > 0) formatSpeed(item.uploadSpeedLimit.toLong()) else "∞"}/s")
                    }

                    // ETA
                    if (item.etaSec < Long.MAX_VALUE && item.etaSec >= 0 && item.state == TorrentState.DOWNLOADING) {
                        TorrentInfoRow(Icons.Outlined.Schedule, "ETA", formatEta(item.etaSec))
                    }

                    // Seeds / Peers
                    val seedLabel = if (item.totalSeeds > 0) "${item.connectedSeeds} of ${item.totalSeeds}" else "${item.seeders}"
                    val peerLabel = if (item.totalPeers > 0) "${item.connectedPeers} of ${item.totalPeers}" else "${item.peers}"
                    TorrentInfoRow(Icons.Outlined.Group, "Seeds / Peers", "$seedLabel  ·  $peerLabel")

                    // Downloaded / Uploaded
                    TorrentInfoRow(Icons.Outlined.Download, "Downloaded", formatBytes(item.downloadedBytes) + " of " + formatBytes(item.totalSize))
                    if (item.totalSentBytes > 0) {
                        TorrentInfoRow(Icons.Outlined.Upload, "Uploaded", formatBytes(item.totalSentBytes))
                    }

                    // Share ratio
                    val ratioText = "%.3f".format(item.shareRatio)
                    TorrentInfoRow(Icons.Outlined.SwapVert, "Share Ratio", ratioText)

                    // Active / Seeding time
                    if (item.activeTimeSec > 0) {
                        TorrentInfoRow(Icons.Outlined.Timer, "Active Time", formatEta(item.activeTimeSec))
                    }
                    if (item.seedingTimeSec > 0) {
                        TorrentInfoRow(Icons.Outlined.CloudUpload, "Seeding Time", formatEta(item.seedingTimeSec))
                    }

                    // Save path
                    if (item.savePath.isNotBlank()) {
                        TorrentInfoRow(Icons.Outlined.FolderOpen, "Save Path", item.savePath)
                    }

                    // Info hash
                    if (item.infoHash.isNotBlank()) {
                        TorrentInfoRow(Icons.Outlined.Tag, "Info Hash", item.infoHash)
                    }
                }

                // Error
                if (item.error != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Rose.copy(alpha = 0.06f)).padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Outlined.ErrorOutline, null, tint = Rose, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(item.error, color = Rose.copy(alpha = 0.85f), fontSize = 12.sp)
                    }
                }
            }

            // ── FILES tab ──
            1 -> {
                // If the live item has no file metadata, try loading it from the engine
                var fetchedFiles by remember(item.infoHash) { mutableStateOf<List<TorrentFileInfo>>(emptyList()) }
                var isLoadingFiles by remember(item.infoHash) { mutableStateOf(false) }
                LaunchedEffect(item.infoHash) {
                    if (item.files.isEmpty()) {
                        isLoadingFiles = true
                        withContext(Dispatchers.IO) {
                            val files = vm.getFilesForTorrent(item.infoHash)
                            withContext(Dispatchers.Main) {
                                fetchedFiles = files
                                isLoadingFiles = false
                            }
                        }
                    }
                }
                val displayFiles = if (item.files.isEmpty()) fetchedFiles else item.files

                // Multi-select state
                var selectedIndices by remember(item.infoHash) { mutableStateOf(setOf<Int>()) }

                if (isLoadingFiles) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), color = Cyan, strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Loading file info…", color = TextTertiary, fontSize = 12.sp)
                    }
                } else if (displayFiles.isEmpty()) {
                    Text("No file info available", color = TextTertiary, fontSize = 12.sp, modifier = Modifier.padding(4.dp))
                } else {
                    // Header row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val allSelected = selectedIndices.size == displayFiles.size
                            Checkbox(
                                checked = allSelected,
                                onCheckedChange = {
                                    selectedIndices = if (allSelected) emptySet()
                                    else displayFiles.map { it.index }.toSet()
                                },
                                colors = CheckboxDefaults.colors(
                                    checkedColor = Violet,
                                    uncheckedColor = TextTertiary,
                                    checkmarkColor = TextPrimary
                                ),
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                "Files (${displayFiles.size})",
                                color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold
                            )
                        }
                        if (selectedIndices.isNotEmpty()) {
                            TextButton(
                                onClick = {
                                    vm.deleteSelectedFiles(item.infoHash, selectedIndices)
                                    selectedIndices = emptySet()
                                }
                            ) {
                                Icon(Icons.Outlined.Delete, null, tint = Rose, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Delete (${selectedIndices.size})", color = Rose, fontSize = 11.sp)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))

                    displayFiles.forEach { file ->
                        TorrentFileRow(
                            file = file,
                            isSelected = file.index in selectedIndices,
                            selectionMode = selectedIndices.isNotEmpty(),
                            onSelect = {
                                selectedIndices = if (file.index in selectedIndices)
                                    selectedIndices - file.index
                                else
                                    selectedIndices + file.index
                            },
                            onToggle = { onToggleFile(file.index) },
                            onStream = { vm.streamFile(context, item.infoHash, file.index, file.name) }
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                }
            }

            // ── TRACKERS tab ──
            2 -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Trackers (${trackers.size})", color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = {
                        vm.requestTrackerAnnounce(item.infoHash)
                        trackers = vm.getTrackerList(item.infoHash)
                    }) {
                        Icon(Icons.Outlined.Refresh, null, tint = Cyan, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Announce All", color = Cyan, fontSize = 11.sp)
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                if (trackers.isEmpty()) {
                    Text("No trackers found", color = TextTertiary, fontSize = 12.sp, modifier = Modifier.padding(4.dp))
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        trackers.forEach { t -> TrackerRow(t) }
                    }
                }
            }

            // ── PEERS tab ──
            3 -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Peers (${peers.size})", color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = { peers = vm.getPeerList(item.infoHash) }) {
                        Icon(Icons.Outlined.Refresh, null, tint = Cyan, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Refresh", color = Cyan, fontSize = 11.sp)
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                if (peers.isEmpty()) {
                    Text("No peers connected", color = TextTertiary, fontSize = 12.sp, modifier = Modifier.padding(4.dp))
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        peers.take(20).forEach { p -> PeerRow(p) }
                        if (peers.size > 20) {
                            Text("…and ${peers.size - 20} more peers", color = TextTertiary, fontSize = 11.sp,
                                modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // ── Action buttons ──
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (item.state == TorrentState.DOWNLOADING || item.state == TorrentState.METADATA || item.state == TorrentState.CHECKING || item.state == TorrentState.SEEDING) {
                TorrentActionButton(Icons.Outlined.Pause, "Pause", Amber, onPause, Modifier.weight(1f))
            } else if (item.state == TorrentState.PAUSED || item.state == TorrentState.STOPPED) {
                val isCompleted = item.infoHash in vm.autoPausedForSeeding
                TorrentActionButton(
                    icon = if (isCompleted) Icons.Outlined.CloudUpload else Icons.Outlined.PlayArrow,
                    label = if (isCompleted) "Seed" else "Resume",
                    color = if (isCompleted) Cyan else Emerald,
                    onClick = onResume,
                    modifier = Modifier.weight(1f)
                )
            }

            if (item.state == TorrentState.FINISHED || item.state == TorrentState.SEEDING) {
                TorrentActionButton(Icons.Outlined.OpenInNew, "Open", Cyan, onOpen, Modifier.weight(1f))
            }

            TorrentActionButton(Icons.Outlined.FindReplace, "Recheck", VioletLight, { vm.forceRecheck(item.infoHash) }, Modifier.weight(1f))
            TorrentActionButton(Icons.Outlined.Share, "Share", Cyan, onShare, Modifier.weight(1f))
            TorrentActionButton(Icons.Outlined.Delete, "Remove", Rose, onDelete, Modifier.weight(1f))
        }
    }
}

// ── Single info row inside the Info tab ──────────────────────────────────────────

@Composable
private fun TorrentInfoRow(icon: ImageVector, label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = TextTertiary, modifier = Modifier.size(14.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(label, color = TextTertiary, fontSize = 11.sp, lineHeight = 11.sp, modifier = Modifier.width(82.dp))
        Text(value, color = TextSecondary, fontSize = 11.sp, lineHeight = 11.sp, modifier = Modifier.weight(1f))
    }
}

// ── Tracker row ──────────────────────────────────────────────────────────────────

@Composable
private fun TrackerRow(t: TrackerInfo) {
    val (dotColor, statusText) = when (t.status) {
        TrackerStatus.WORKING       -> Emerald to "Working"
        TrackerStatus.UPDATING      -> Amber   to "Updating…"
        TrackerStatus.NOT_CONTACTED -> TextTertiary to "Not contacted"
        TrackerStatus.NOT_WORKING   -> Rose    to "Not working"
        else                        -> TextTertiary to "Unknown"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Ink2.copy(alpha = 0.5f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .padding(top = 3.dp)
                .size(8.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(t.url, color = TextSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (t.message.isNotBlank()) {
                Text(t.message, color = TextTertiary, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(statusText, color = dotColor, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ── Peer row ─────────────────────────────────────────────────────────────────────

@Composable
private fun PeerRow(p: PeerInfo) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Ink2.copy(alpha = 0.5f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Outlined.PersonOutline, null, tint = TextTertiary, modifier = Modifier.size(14.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(p.ip, color = TextSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (p.client.isNotBlank()) {
                Text(p.client, color = TextTertiary, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text("↓ ${formatSpeed(p.downloadSpeed)}/s", color = Cyan, fontSize = 10.sp)
            Text("↑ ${formatSpeed(p.uploadSpeed)}/s", color = Emerald, fontSize = 10.sp)
        }
        Spacer(modifier = Modifier.width(6.dp))
        Box(
            modifier = Modifier
                .width(36.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Ink3)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(p.progress.coerceIn(0f, 1f))
                    .clip(RoundedCornerShape(2.dp))
                    .background(Cyan)
            )
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════════════
// TORRENT FILE ROW — with selection checkbox
// ══════════════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TorrentFileRow(
    file: TorrentFileInfo,
    isSelected: Boolean,
    selectionMode: Boolean,
    onSelect: () -> Unit,
    onToggle: () -> Unit,
    onStream: () -> Unit = {}
) {
    val isEnabled = file.priority > 0
    val ext = file.name.substringAfterLast('.', "").lowercase()

    val fileIcon = when (ext) {
        "mp4", "mkv", "avi", "webm", "mov" -> Icons.Outlined.Videocam
        "mp3", "flac", "ogg", "m4a", "wav", "aac" -> Icons.Outlined.MusicNote
        "jpg", "jpeg", "png", "gif", "webp", "bmp" -> Icons.Outlined.Image
        "pdf" -> Icons.Outlined.PictureAsPdf
        "zip", "rar", "7z", "tar", "gz" -> Icons.Outlined.FolderZip
        "txt", "srt", "sub", "nfo" -> Icons.Outlined.Description
        else -> Icons.Outlined.InsertDriveFile
    }
    val fileColor = when (ext) {
        "mp4", "mkv", "avi", "webm", "mov" -> Cyan
        "mp3", "flac", "ogg", "m4a", "wav", "aac" -> VioletLight
        else -> TextTertiary
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isSelected) Violet.copy(alpha = 0.1f) else Ink2.copy(alpha = 0.4f)
            )
            .combinedClickable(
                onClick = { if (selectionMode) onSelect() else onToggle() },
                onLongClick = { if (!selectionMode) onSelect() }
            )
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selectionMode) {
            // In selection mode — checkbox tracks selection
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onSelect() },
                colors = CheckboxDefaults.colors(
                    checkedColor = Violet,
                    uncheckedColor = TextTertiary,
                    checkmarkColor = TextPrimary
                ),
                modifier = Modifier.size(24.dp)
            )
        } else {
            // Normal mode — small radio-style dot = file included/excluded in download
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .border(
                        1.5.dp,
                        if (isEnabled) Emerald.copy(alpha = 0.6f) else TextTertiary.copy(alpha = 0.3f),
                        CircleShape
                    )
                    .background(if (isEnabled) Emerald.copy(alpha = 0.1f) else Color.Transparent)
                    .clickable { onToggle() },
                contentAlignment = Alignment.Center
            ) {
                if (isEnabled) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(Emerald)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.width(6.dp))
        Icon(fileIcon, null, tint = if (isEnabled || isSelected) fileColor else TextTertiary.copy(alpha = 0.4f), modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                file.name,
                color = if (isEnabled || isSelected) TextPrimary else TextTertiary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (file.size > 0) {
                Text(
                    "${formatBytes(file.size)} · ${"%.0f".format(file.progress * 100)}%",
                    color = TextTertiary,
                    fontSize = 10.sp
                )
            }
        }
        // Stream button — shown for video/audio files in normal (non-selection) mode
        val isStreamable = !selectionMode && ext in setOf("mp4", "mkv", "avi", "webm", "mov",
            "mp3", "flac", "ogg", "m4a", "wav", "aac") && file.progress > 0.01f
        if (isStreamable) {
            Spacer(modifier = Modifier.width(4.dp))
            IconButton(
                onClick = onStream,
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    Icons.Outlined.PlayCircle,
                    contentDescription = "Stream",
                    tint = Cyan,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════════════
// SETTINGS BOTTOM SHEET
// ══════════════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TorrentSettingsSheet(
    settings: TorrentPrefs,
    onDismiss: () -> Unit,
    onSave: (TorrentPrefs) -> Unit
) {
    val context = LocalContext.current
    var dlLimit by remember { mutableStateOf(if (settings.downloadSpeedLimit > 0) (settings.downloadSpeedLimit / 1024).toString() else "") }
    var ulLimit by remember { mutableStateOf(if (settings.uploadSpeedLimit > 0) (settings.uploadSpeedLimit / 1024).toString() else "") }
    var wifiOnly by remember { mutableStateOf(settings.wifiOnly) }
    var sequential by remember { mutableStateOf(settings.sequentialByDefault) }
    var notifications by remember { mutableStateOf(settings.showNotifications) }
    var autoQueue by remember { mutableStateOf(settings.autoQueueOnLowRam) }
    var maxDl by remember { mutableStateOf(settings.maxActiveDownloads.toString()) }
    var pickedPath by remember { mutableStateOf(settings.savePath) }
    var stopSeedingOnComplete by remember { mutableStateOf(settings.stopSeedingOnComplete) }
    var maxSeedRatioStr by remember { mutableStateOf(if (settings.maxSeedRatio > 0f) "%.2f".format(settings.maxSeedRatio) else "") }

    val folderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val path = treeUriToPath(context, uri)
            if (path != null) pickedPath = path
        }
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Ink2,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 12.dp)
                    .width(32.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(TextTertiary)
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 40.dp)
        ) {
            Text("Torrent Settings", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(20.dp))

            // ── Speed Limits ──
            Text("SPEED LIMITS", color = TextTertiary, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
            Spacer(modifier = Modifier.height(10.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SettingsTextField(
                    label = "Download (KB/s)",
                    value = dlLimit,
                    onValueChange = { dlLimit = it.filter { c -> c.isDigit() } },
                    placeholder = "Unlimited",
                    modifier = Modifier.weight(1f)
                )
                SettingsTextField(
                    label = "Upload (KB/s)",
                    value = ulLimit,
                    onValueChange = { ulLimit = it.filter { c -> c.isDigit() } },
                    placeholder = "Unlimited",
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text("Leave empty for unlimited speed", color = TextTertiary, fontSize = 11.sp)

            Spacer(modifier = Modifier.height(20.dp))

            // ── Active Limits ──
            Text("ACTIVE DOWNLOADS", color = TextTertiary, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
            Spacer(modifier = Modifier.height(10.dp))
            SettingsTextField(
                label = "Max simultaneous downloads",
                value = maxDl,
                onValueChange = { maxDl = it.filter { c -> c.isDigit() } },
                placeholder = "5",
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(20.dp))

            // ── Toggles ──
            Text("OPTIONS", color = TextTertiary, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
            Spacer(modifier = Modifier.height(10.dp))

            SettingsToggle("Wi-Fi Only", "Only download when connected to Wi-Fi", wifiOnly, Icons.Outlined.Wifi) { wifiOnly = it }
            SettingsToggle("Sequential Download", "Download pieces in order (for streaming)", sequential, Icons.Outlined.Sort) { sequential = it }
            SettingsToggle("Notifications", "Show download progress in notifications", notifications, Icons.Outlined.Notifications) { notifications = it }
            SettingsToggle("Auto-Pause Low RAM", "Pause downloads when memory is low", autoQueue, Icons.Outlined.Memory) { autoQueue = it }

            Spacer(modifier = Modifier.height(20.dp))

            // ── Storage Path ──
            Text("STORAGE", color = TextTertiary, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Ink3)
                    .border(1.dp, SurfaceBorder.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                    .clickable { folderLauncher.launch(null) }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.FolderOpen, null, tint = VioletLight, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Download Folder", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(
                        pickedPath.ifBlank { "Private storage · Torrents (app-only)" }
                            .replace("/storage/emulated/0/", ""),
                        color = TextTertiary, fontSize = 11.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(Icons.Outlined.ChevronRight, null, tint = TextTertiary, modifier = Modifier.size(18.dp))
            }
            if (pickedPath.isNotBlank()) {
                TextButton(
                    onClick = { pickedPath = "" },
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 2.dp)
                ) {
                    Text("Reset to default", color = TextTertiary, fontSize = 11.sp)
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ── Seeding ──
            Text("SEEDING", color = TextTertiary, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
            Spacer(modifier = Modifier.height(10.dp))
            SettingsToggle(
                title = "Stop seeding when download finishes",
                subtitle = "Pause automatically once 100% is reached",
                checked = stopSeedingOnComplete,
                icon = Icons.Outlined.CloudOff
            ) { stopSeedingOnComplete = it }
            Spacer(modifier = Modifier.height(8.dp))
            SettingsTextField(
                label = "Max seed ratio (0 = unlimited)",
                value = maxSeedRatioStr,
                onValueChange = { v ->
                    val filtered = v.filter { it.isDigit() || it == '.' }
                    maxSeedRatioStr = filtered
                },
                placeholder = "e.g. 1.0",
                modifier = Modifier.fillMaxWidth()
            )
            Text("Stop seeding when upload÷download reaches this ratio", color = TextTertiary, fontSize = 11.sp)

            Spacer(modifier = Modifier.height(20.dp))

            // ── Background ──
            Text("BACKGROUND", color = TextTertiary, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
            Spacer(modifier = Modifier.height(10.dp))
            val pm = remember { context.getSystemService(Context.POWER_SERVICE) as PowerManager }
            val isBatteryExempt = remember { pm.isIgnoringBatteryOptimizations(context.packageName) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Ink3)
                    .border(1.dp, SurfaceBorder.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (isBatteryExempt) Icons.Outlined.BatteryChargingFull else Icons.Outlined.BatteryAlert,
                    null,
                    tint = if (isBatteryExempt) Emerald else Amber,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Background service", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(
                        if (isBatteryExempt) "App is exempt from battery optimization" else "Battery optimization may stop torrents",
                        color = if (isBatteryExempt) Emerald else Amber,
                        fontSize = 11.sp
                    )
                }
                if (!isBatteryExempt) {
                    TextButton(onClick = {
                        val intent = Intent(
                            android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:${context.packageName}")
                        )
                        context.startActivity(intent)
                    }) {
                        Text("Allow", color = Amber, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ── Save button ──
            Button(
                onClick = {
                    val dlBytes = dlLimit.toIntOrNull()?.let { it * 1024 } ?: 0
                    val ulBytes = ulLimit.toIntOrNull()?.let { it * 1024 } ?: 0
                    val maxActive = maxDl.toIntOrNull()?.coerceIn(1, 20) ?: 5
                    val seedRatio = maxSeedRatioStr.toFloatOrNull()?.coerceAtLeast(0f) ?: 0f
                    onSave(
                        settings.copy(
                            downloadSpeedLimit = dlBytes,
                            uploadSpeedLimit = ulBytes,
                            wifiOnly = wifiOnly,
                            sequentialByDefault = sequential,
                            showNotifications = notifications,
                            autoQueueOnLowRam = autoQueue,
                            maxActiveDownloads = maxActive,
                            savePath = pickedPath,
                            stopSeedingOnComplete = stopSeedingOnComplete,
                            maxSeedRatio = seedRatio
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Violet)
            ) {
                Text("Save Settings", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            }
        }
    }
}

@Composable
private fun SettingsTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontSize = 12.sp) },
        placeholder = { Text(placeholder, color = TextTertiary, fontSize = 13.sp) },
        singleLine = true,
        modifier = modifier,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = VioletLight,
            unfocusedBorderColor = SurfaceBorder.copy(alpha = 0.3f),
            cursorColor = VioletLight,
            focusedLabelColor = VioletLight,
            unfocusedLabelColor = TextTertiary,
            focusedTextColor = TextPrimary,
            unfocusedTextColor = TextPrimary,
            focusedContainerColor = Ink3,
            unfocusedContainerColor = Ink3
        ),
        shape = RoundedCornerShape(12.dp)
    )
}

@Composable
private fun SettingsToggle(
    title: String,
    subtitle: String,
    checked: Boolean,
    icon: ImageVector,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = if (checked) VioletLight else TextTertiary, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = TextTertiary, fontSize = 11.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = VioletLight,
                checkedTrackColor = Violet.copy(alpha = 0.3f),
                uncheckedThumbColor = TextTertiary,
                uncheckedTrackColor = Ink3
            )
        )
    }
}

// ══════════════════════════════════════════════════════════════════════════════════
// SHARED COMPONENTS
// ══════════════════════════════════════════════════════════════════════════════════

@Composable
private fun AnimatedBanner(
    visible: Boolean,
    text: String,
    color: Color,
    icon: ImageVector,
    onDismiss: (() -> Unit)?,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(300)) + expandVertically(tween(300)),
        exit = fadeOut(tween(200)) + shrinkVertically(tween(200))
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .padding(bottom = 12.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(color.copy(alpha = 0.08f))
                .border(1.dp, color.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(10.dp))
            Text(text, color = color.copy(alpha = 0.9f), fontSize = 13.sp, modifier = Modifier.weight(1f))
            if (actionLabel != null && onAction != null) {
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(
                    onClick = onAction,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(actionLabel, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            if (onDismiss != null) {
                IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Outlined.Close, "Dismiss", tint = color.copy(alpha = 0.6f), modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
private fun TorrentActionButton(
    icon: ImageVector,
    label: String,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.08f))
            .border(1.dp, color.copy(alpha = 0.15f), RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, label, tint = color, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun TorrentStatPill(
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
        Icon(icon, null, tint = color, modifier = Modifier.size(14.dp).offset(y = 0.5.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text(label, color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, lineHeight = 11.sp)
    }
}

// ── Speed formatter ─────────────────────────────────────────────────────────────
internal fun formatSpeed(bytesPerSec: Long): String = when {
    bytesPerSec < 1024 -> "${bytesPerSec} B"
    bytesPerSec < 1024 * 1024 -> "${"%.1f".format(bytesPerSec / 1024.0)} KB"
    bytesPerSec < 1024L * 1024 * 1024 -> "${"%.1f".format(bytesPerSec / (1024.0 * 1024.0))} MB"
    else -> "${"%.2f".format(bytesPerSec / (1024.0 * 1024.0 * 1024.0))} GB"
}
