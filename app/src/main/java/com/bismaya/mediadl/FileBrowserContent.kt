package com.bismaya.mediadl

import android.content.Context
import android.content.Intent
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bismaya.mediadl.ui.theme.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

// ── File item data ──────────────────────────────────────────────────────────────
data class FileItem(
    val file: File,
    val name: String = file.name,
    val isDirectory: Boolean = file.isDirectory,
    val size: Long = if (file.isFile) file.length() else 0L,
    val lastModified: Long = file.lastModified(),
    val childCount: Int = if (file.isDirectory) (file.listFiles()?.size ?: 0) else 0
) {
    val extension: String get() = name.substringAfterLast('.', "").lowercase()
}

// ── File Browser ────────────────────────────────────────────────────────────────
@Composable
fun FileBrowserContent(
    currentPath: String,
    items: List<FileItem>,
    canGoUp: Boolean,
    onNavigate: (FileItem) -> Unit,
    onGoUp: () -> Unit,
    onOpen: (FileItem) -> Unit,
    onDelete: (FileItem) -> Unit,
    onShare: (FileItem) -> Unit,
    onRename: (FileItem, String) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedItem by remember { mutableStateOf<FileItem?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }
    var showActions by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        // ── Path breadcrumb ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (canGoUp) {
                IconButton(
                    onClick = onGoUp,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Outlined.ArrowBack,
                        contentDescription = "Go back",
                        tint = VioletLight,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
            }

            Icon(
                Icons.Outlined.Folder,
                contentDescription = null,
                tint = Amber,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))

            val displayPath = currentPath
                .replace("/storage/emulated/0/", "")
                .ifBlank { "Root" }
            Text(
                displayPath,
                color = TextSecondary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }

        HorizontalDivider(color = SurfaceBorder.copy(alpha = 0.1f))

        if (items.isEmpty()) {
            // ── Empty state ──
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 60.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Outlined.FolderOff,
                    contentDescription = null,
                    tint = TextTertiary,
                    modifier = Modifier.size(48.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "Empty Folder",
                    color = TextSecondary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "No files or folders here yet.\nDownloaded torrents will appear automatically.",
                    color = TextTertiary,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 20.sp
                )
            }
        } else {
            // ── File count ──
            val dirs = items.count { it.isDirectory }
            val files = items.count { !it.isDirectory }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
            ) {
                Text(
                    buildString {
                        if (dirs > 0) append("$dirs folder${if (dirs > 1) "s" else ""}")
                        if (dirs > 0 && files > 0) append(" · ")
                        if (files > 0) append("$files file${if (files > 1) "s" else ""}")
                    },
                    color = TextTertiary,
                    fontSize = 11.sp
                )
            }

            // ── File list ──
            items.forEach { item ->
                FileItemRow(
                    item = item,
                    onClick = {
                        if (item.isDirectory) {
                            onNavigate(item)
                        } else {
                            selectedItem = item
                            showActions = true
                        }
                    }
                )
            }
        }
    }

    // ── File action dialog ──
    if (showActions && selectedItem != null) {
        val item = selectedItem!!
        AlertDialog(
            onDismissRequest = { showActions = false; selectedItem = null },
            containerColor = Ink3,
            titleContentColor = TextPrimary,
            title = {
                Column {
                    Text(
                        item.name,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "${formatBytes(item.size)} · ${formatDate(item.lastModified)}",
                        color = TextTertiary,
                        fontSize = 12.sp
                    )
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FileActionRow(Icons.Outlined.OpenInNew, "Open", Cyan) {
                        showActions = false
                        onOpen(item)
                        selectedItem = null
                    }
                    FileActionRow(Icons.Outlined.Share, "Share", VioletLight) {
                        showActions = false
                        onShare(item)
                        selectedItem = null
                    }
                    FileActionRow(Icons.Outlined.DriveFileRenameOutline, "Rename", Amber) {
                        showActions = false
                        renameText = item.name
                        showRenameDialog = true
                    }
                    FileActionRow(Icons.Outlined.Delete, "Delete", Rose) {
                        showActions = false
                        showDeleteConfirm = true
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showActions = false; selectedItem = null }) {
                    Text("Cancel", color = TextTertiary)
                }
            }
        )
    }

    // ── Delete confirmation ──
    if (showDeleteConfirm && selectedItem != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false; selectedItem = null },
            containerColor = Ink3,
            titleContentColor = TextPrimary,
            title = { Text("Delete File", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Delete \"${selectedItem!!.name}\"? This cannot be undone.",
                    color = TextSecondary,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(selectedItem!!)
                    showDeleteConfirm = false
                    selectedItem = null
                }) {
                    Text("Delete", color = Rose, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false; selectedItem = null }) {
                    Text("Cancel", color = TextTertiary)
                }
            }
        )
    }

    // ── Rename dialog ──
    if (showRenameDialog && selectedItem != null) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false; selectedItem = null },
            containerColor = Ink3,
            titleContentColor = TextPrimary,
            title = { Text("Rename", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    label = { Text("File name") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = VioletLight,
                        unfocusedBorderColor = SurfaceBorder,
                        cursorColor = VioletLight,
                        focusedLabelColor = VioletLight,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (renameText.isNotBlank()) {
                            onRename(selectedItem!!, renameText)
                        }
                        showRenameDialog = false
                        selectedItem = null
                    },
                    enabled = renameText.isNotBlank()
                ) {
                    Text("Rename", color = Emerald, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false; selectedItem = null }) {
                    Text("Cancel", color = TextTertiary)
                }
            }
        )
    }
}

// ── File Item Row ───────────────────────────────────────────────────────────────
@Composable
private fun FileItemRow(item: FileItem, onClick: () -> Unit) {
    val icon = if (item.isDirectory) {
        Icons.Outlined.Folder
    } else {
        when (item.extension) {
            "mp4", "mkv", "avi", "webm", "mov", "flv" -> Icons.Outlined.Videocam
            "mp3", "flac", "ogg", "m4a", "wav", "aac", "opus" -> Icons.Outlined.MusicNote
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "svg" -> Icons.Outlined.Image
            "pdf" -> Icons.Outlined.PictureAsPdf
            "zip", "rar", "7z", "tar", "gz" -> Icons.Outlined.FolderZip
            "txt", "srt", "sub", "nfo", "log" -> Icons.Outlined.Description
            "torrent" -> Icons.Outlined.CloudDownload
            else -> Icons.Outlined.InsertDriveFile
        }
    }

    val iconColor = if (item.isDirectory) {
        Amber
    } else {
        when (item.extension) {
            "mp4", "mkv", "avi", "webm", "mov", "flv" -> Cyan
            "mp3", "flac", "ogg", "m4a", "wav", "aac", "opus" -> VioletLight
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "svg" -> Emerald
            "pdf" -> Rose
            "torrent" -> Violet
            else -> TextTertiary
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(iconColor.copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier.size(20.dp)
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                item.name,
                color = TextPrimary,
                fontSize = 14.sp,
                fontWeight = if (item.isDirectory) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                if (item.isDirectory) {
                    "${item.childCount} item${if (item.childCount != 1) "s" else ""}"
                } else {
                    "${formatBytes(item.size)} · ${formatDate(item.lastModified)}"
                },
                color = TextTertiary,
                fontSize = 11.sp
            )
        }

        if (item.isDirectory) {
            Icon(
                Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = TextTertiary,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

// ── File Action Row (inside dialog) ─────────────────────────────────────────────
@Composable
private fun FileActionRow(
    icon: ImageVector,
    label: String,
    color: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = label, tint = color, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(14.dp))
        Text(label, color = TextPrimary, fontSize = 14.sp)
    }
}

// ── Helpers ─────────────────────────────────────────────────────────────────────
// formatBytes defined in MainActivity.kt (same package)

internal fun formatDate(timestamp: Long): String {
    if (timestamp <= 0) return ""
    val sdf = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

// ── File operations helper ──────────────────────────────────────────────────────
object FileOperations {

    fun openFile(context: Context, file: File) {
        val uri = try {
            androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
        } catch (_: Exception) {
            return
        }
        val mime = context.contentResolver.getType(uri) ?: guessMime(file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (_: Exception) { }
    }

    fun shareFile(context: Context, file: File) {
        val uri = try {
            androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
        } catch (_: Exception) {
            return
        }
        val mime = context.contentResolver.getType(uri) ?: guessMime(file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share ${file.name}"))
    }

    fun deleteFile(file: File): Boolean {
        return if (file.isDirectory) file.deleteRecursively() else file.delete()
    }

    fun renameFile(file: File, newName: String): Boolean {
        val dest = File(file.parentFile, newName)
        return if (!dest.exists()) file.renameTo(dest) else false
    }

    private fun guessMime(file: File): String {
        return when (file.extension.lowercase()) {
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "avi" -> "video/x-msvideo"
            "webm" -> "video/webm"
            "mov" -> "video/quicktime"
            "mp3" -> "audio/mpeg"
            "flac" -> "audio/flac"
            "ogg" -> "audio/ogg"
            "m4a" -> "audio/mp4"
            "wav" -> "audio/wav"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "pdf" -> "application/pdf"
            "zip" -> "application/zip"
            "txt" -> "text/plain"
            else -> "*/*"
        }
    }
}
