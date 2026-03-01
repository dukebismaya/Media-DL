package com.bismaya.mediadl

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// ── Data model ──────────────────────────────────────────────────────────────────
data class DownloadRecord(
    val id: String,
    val title: String,
    val platform: String,
    val type: String,          // "video" or "audio"
    val format: String,        // e.g. "1080p · H.264 [MP4]"
    val fileSize: Long,        // bytes
    val timestamp: Long,       // System.currentTimeMillis()
    val url: String,           // original source URL
    val fileName: String,      // saved file name
    val contentUri: String = "", // MediaStore content:// URI for playback & deletion
    val thumbnailUrl: String = "" // video/audio thumbnail URL
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("platform", platform)
        put("type", type)
        put("format", format)
        put("fileSize", fileSize)
        put("fileName", fileName)
        put("timestamp", timestamp)
        put("url", url)
        put("contentUri", contentUri)
        put("thumbnailUrl", thumbnailUrl)
    }

    companion object {
        fun fromJson(json: JSONObject): DownloadRecord = DownloadRecord(
            id = json.optString("id", ""),
            title = json.optString("title", ""),
            platform = json.optString("platform", ""),
            type = json.optString("type", "video"),
            format = json.optString("format", ""),
            fileSize = json.optLong("fileSize", 0),
            fileName = json.optString("fileName", ""),
            timestamp = json.optLong("timestamp", 0),
            url = json.optString("url", ""),
            contentUri = json.optString("contentUri", ""),
            thumbnailUrl = json.optString("thumbnailUrl", "")
        )
    }
}

// ── Repository ──────────────────────────────────────────────────────────────────
class DownloadHistoryRepo(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("mediadl_history", Context.MODE_PRIVATE)

    private val key = "records"

    fun getAll(): List<DownloadRecord> {
        val raw = prefs.getString(key, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { DownloadRecord.fromJson(arr.getJSONObject(it)) }
                .sortedByDescending { it.timestamp }
        } catch (_: Exception) { emptyList() }
    }

    fun add(record: DownloadRecord) {
        val current = getAll().toMutableList()
        current.add(0, record) // newest first
        // Keep last 100 records
        val trimmed = current.take(100)
        save(trimmed)
        backupToSharedStorage()
    }

    fun remove(id: String) {
        val current = getAll().toMutableList()
        current.removeAll { it.id == id }
        save(current)
        backupToSharedStorage()
    }

    fun clearAll() {
        prefs.edit().remove(key).apply()
        backupToSharedStorage()
    }

    /** Total downloads count */
    fun count(): Int = getAll().size

    /** Total size of all downloads */
    fun totalSize(): Long = getAll().sumOf { it.fileSize }

    /**
     * Restore history from the external backup file in Documents/MediaDL/.
     * Called when SharedPreferences are empty (e.g. after reinstall).
     * Returns the number of records restored.
     */
    fun restoreFromBackup(): Int {
        val existing = getAll()
        if (existing.isNotEmpty()) return 0 // already have data

        val restored = readBackupFromSharedStorage()
        if (restored.isNotEmpty()) {
            save(restored)
        }
        return restored.size
    }

    /**
     * Scan MediaStore for files in Movies/MediaDL and Music/MediaDL that are
     * NOT already tracked in SharedPreferences history. This recovers download
     * records after a reinstall when the user's media files still exist in
     * shared storage.
     */
    fun recoverFromMediaStore(): Int {
        val existing = getAll()
        val existingUris = existing.map { it.contentUri }.toSet()
        val existingNames = existing.map { it.fileName }.toSet()
        val recovered = mutableListOf<DownloadRecord>()

        // Scan video files in Movies/MediaDL
        recovered.addAll(
            scanMediaStoreFolder(
                collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                relativePath = "${Environment.DIRECTORY_MOVIES}/MediaDL",
                type = "video",
                existingUris = existingUris,
                existingNames = existingNames
            )
        )

        // Scan audio files in Music/MediaDL
        recovered.addAll(
            scanMediaStoreFolder(
                collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                relativePath = "${Environment.DIRECTORY_MUSIC}/MediaDL",
                type = "audio",
                existingUris = existingUris,
                existingNames = existingNames
            )
        )

        if (recovered.isNotEmpty()) {
            // Merge: existing records first (already sorted), then recovered sorted by date
            val merged = (existing + recovered.sortedByDescending { it.timestamp }).take(200)
            save(merged)
        }

        return recovered.size
    }

    private fun scanMediaStoreFolder(
        collection: android.net.Uri,
        relativePath: String,
        type: String,
        existingUris: Set<String>,
        existingNames: Set<String>
    ): List<DownloadRecord> {
        val results = mutableListOf<DownloadRecord>()
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.MIME_TYPE
        )

        // Query for files in our specific subfolder
        val selection: String
        val selectionArgs: Array<String>
        if (Build.VERSION.SDK_INT >= 29) {
            selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
            selectionArgs = arrayOf("$relativePath%")
        } else {
            selection = "${MediaStore.MediaColumns.DATA} LIKE ?"
            selectionArgs = arrayOf("%/$relativePath/%")
        }

        try {
            context.contentResolver.query(
                collection, projection, selection, selectionArgs,
                "${MediaStore.MediaColumns.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)

                while (cursor.moveToNext()) {
                    val mediaId = cursor.getLong(idCol)
                    val fileName = cursor.getString(nameCol) ?: continue
                    val fileSize = cursor.getLong(sizeCol)
                    val dateAdded = cursor.getLong(dateCol) * 1000 // seconds → millis

                    val contentUri = android.content.ContentUris.withAppendedId(collection, mediaId).toString()

                    // Skip if already tracked
                    if (contentUri in existingUris || fileName in existingNames) continue

                    // Build a recovered record
                    val title = fileName.substringBeforeLast(".")
                    val ext = fileName.substringAfterLast(".", "").lowercase()
                    val formatLabel = when {
                        ext == "mp4" -> "Recovered · MP4"
                        ext == "webm" -> "Recovered · WebM"
                        ext == "mp3" -> "Recovered · MP3"
                        ext == "m4a" -> "Recovered · M4A"
                        ext == "opus" -> "Recovered · Opus"
                        else -> "Recovered · ${ext.uppercase()}"
                    }

                    results.add(
                        DownloadRecord(
                            id = "recovered_${mediaId}",
                            title = title,
                            platform = "Recovered",
                            type = type,
                            format = formatLabel,
                            fileSize = fileSize,
                            fileName = fileName,
                            timestamp = dateAdded,
                            url = "",
                            contentUri = contentUri
                        )
                    )
                }
            }
        } catch (_: Exception) { /* permission or query failure - skip gracefully */ }

        return results
    }

    private fun save(records: List<DownloadRecord>) {
        val arr = JSONArray()
        records.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(key, arr.toString()).apply()
    }

    // ── Shared Storage Backup (survives uninstall) ──────────────────────────────

    private val backupFileName = "mediadl_history.json"
    private val backupRelativePath = "${Environment.DIRECTORY_DOCUMENTS}/MediaDL"

    /**
     * Write current history as JSON to Documents/MediaDL/mediadl_history.json via MediaStore.
     * This file persists even when the app is uninstalled.
     */
    private fun backupToSharedStorage() {
        try {
            val records = getAll()
            val arr = JSONArray()
            records.forEach { arr.put(it.toJson()) }
            val jsonBytes = arr.toString().toByteArray(Charsets.UTF_8)

            if (Build.VERSION.SDK_INT >= 29) {
                // Delete existing backup first
                val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
                val selectionArgs = arrayOf(backupFileName, "$backupRelativePath/")
                context.contentResolver.delete(
                    MediaStore.Files.getContentUri("external"),
                    selection, selectionArgs
                )

                // Write new backup
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, backupFileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, backupRelativePath)
                }
                val uri = context.contentResolver.insert(
                    MediaStore.Files.getContentUri("external"), values
                ) ?: return
                context.contentResolver.openOutputStream(uri)?.use { it.write(jsonBytes) }
            } else {
                // Legacy: write directly to external storage
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "MediaDL")
                if (!dir.exists()) dir.mkdirs()
                File(dir, backupFileName).writeBytes(jsonBytes)
            }
        } catch (_: Exception) { /* best-effort backup */ }
    }

    /**
     * Read history from the Documents/MediaDL/mediadl_history.json backup file.
     */
    private fun readBackupFromSharedStorage(): List<DownloadRecord> {
        try {
            val jsonString: String? = if (Build.VERSION.SDK_INT >= 29) {
                val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
                val selectionArgs = arrayOf(backupFileName, "$backupRelativePath/")
                context.contentResolver.query(
                    MediaStore.Files.getContentUri("external"),
                    arrayOf(MediaStore.MediaColumns._ID),
                    selection, selectionArgs, null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                        val uri = android.content.ContentUris.withAppendedId(
                            MediaStore.Files.getContentUri("external"), id
                        )
                        context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
                    } else null
                }
            } else {
                val file = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                    "MediaDL/$backupFileName"
                )
                if (file.exists()) file.readText() else null
            }

            if (!jsonString.isNullOrBlank()) {
                val arr = JSONArray(jsonString)
                return (0 until arr.length()).map { DownloadRecord.fromJson(arr.getJSONObject(it)) }
                    .sortedByDescending { it.timestamp }
            }
        } catch (_: Exception) { /* backup corrupt or inaccessible */ }
        return emptyList()
    }
}