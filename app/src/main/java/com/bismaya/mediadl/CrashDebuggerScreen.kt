package com.bismaya.mediadl

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bismaya.mediadl.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Full-screen crash debugger UI. Shows list of crash logs with
 * ability to expand, copy, share, and delete each log.
 */
@Composable
fun CrashDebuggerScreen(onBack: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var logs by remember { mutableStateOf(CrashLogger.getCrashLogs(context)) }
    var expandedLog by remember { mutableStateOf<String?>(null) }
    var showClearConfirm by remember { mutableStateOf(false) }

    fun refresh() { logs = CrashLogger.getCrashLogs(context) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        // ── Header ──
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            "Back",
                            tint = TextPrimary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    "Crash Debugger",
                    color = TextPrimary,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-1).sp
                )
                Spacer(modifier = Modifier.width(10.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Rose.copy(alpha = 0.1f))
                        .border(1.dp, Rose.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        "${logs.size} logs",
                        color = Rose,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                if (logs.isNotEmpty()) {
                    IconButton(
                        onClick = { showClearConfirm = true },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.Outlined.DeleteSweep,
                            "Clear all",
                            tint = TextTertiary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Crash logs are captured automatically.\nTap to expand, share, or copy.",
                color = TextTertiary,
                fontSize = 13.sp,
                lineHeight = 18.sp
            )
        }

        if (logs.isEmpty()) {
            // ── Empty state ──
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 80.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Outlined.BugReport,
                    null,
                    tint = Emerald.copy(alpha = 0.6f),
                    modifier = Modifier.size(56.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "No Crashes",
                    color = TextSecondary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "No crash logs recorded.\nThe app is running smoothly!",
                    color = TextTertiary,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 20.sp
                )
            }
        } else {
            // ── Log list ──
            logs.forEach { log ->
                CrashLogCard(
                    log = log,
                    isExpanded = expandedLog == log.fileName,
                    onToggle = {
                        expandedLog = if (expandedLog == log.fileName) null else log.fileName
                    },
                    onShare = { CrashLogger.shareCrashLog(context, log) },
                    onCopy = { CrashLogger.copyToClipboard(context, log) },
                    onDelete = {
                        CrashLogger.deleteLog(context, log.fileName)
                        refresh()
                    },
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 5.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(100.dp))
    }

    // ── Clear all confirmation ──
    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            containerColor = Ink3,
            titleContentColor = TextPrimary,
            textContentColor = TextSecondary,
            title = { Text("Clear All Logs", fontWeight = FontWeight.Bold) },
            text = { Text("Delete all ${logs.size} crash logs? This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    CrashLogger.clearLogs(context)
                    refresh()
                    showClearConfirm = false
                }) {
                    Text("Clear All", color = Rose, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text("Cancel", color = TextTertiary)
                }
            }
        )
    }
}

// ── Single crash log card ───────────────────────────────────────────────────────

@Composable
private fun CrashLogCard(
    log: CrashLogger.CrashLog,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    onShare: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val borderColor = if (log.isFatal) Rose.copy(alpha = 0.15f) else Amber.copy(alpha = 0.15f)
    val accentColor = if (log.isFatal) Rose else Amber
    val sdf = remember { SimpleDateFormat("MMM d, yyyy  HH:mm:ss", Locale.getDefault()) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable { onToggle() },
        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
        border = BorderStroke(1.dp, borderColor),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Severity icon
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(accentColor.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (log.isFatal) Icons.Outlined.ErrorOutline else Icons.Outlined.Warning,
                        null,
                        tint = accentColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(accentColor.copy(alpha = 0.1f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                if (log.isFatal) "FATAL" else "NON-FATAL",
                                color = accentColor,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            sdf.format(Date(log.timestamp)),
                            color = TextTertiary,
                            fontSize = 11.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        log.title,
                        color = TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(
                    if (isExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    "Toggle",
                    tint = TextTertiary,
                    modifier = Modifier.size(20.dp)
                )
            }

            // ── Expanded content ──
            AnimatedVisibility(
                visible = isExpanded,
                enter = fadeIn(tween(200)) + expandVertically(tween(250)),
                exit = fadeOut(tween(150)) + shrinkVertically(tween(200))
            ) {
                Column {
                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = SurfaceBorder.copy(alpha = 0.1f))
                    Spacer(modifier = Modifier.height(12.dp))

                    // Stack trace in monospace
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 400.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Ink2)
                            .border(1.dp, SurfaceBorder.copy(alpha = 0.1f), RoundedCornerShape(10.dp))
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp)
                    ) {
                        Text(
                            log.content,
                            color = TextSecondary,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 14.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Action buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CrashActionButton(
                            Icons.Outlined.Share,
                            "Share",
                            VioletLight,
                            onShare,
                            Modifier.weight(1f)
                        )
                        CrashActionButton(
                            Icons.Outlined.ContentCopy,
                            "Copy",
                            Cyan,
                            onCopy,
                            Modifier.weight(1f)
                        )
                        CrashActionButton(
                            Icons.Outlined.Delete,
                            "Delete",
                            Rose,
                            onDelete,
                            Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CrashActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.08f))
            .border(1.dp, color.copy(alpha = 0.15f), RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, label, tint = color, modifier = Modifier.size(15.dp))
        Spacer(modifier = Modifier.width(4.dp))
        Text(label, color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}
