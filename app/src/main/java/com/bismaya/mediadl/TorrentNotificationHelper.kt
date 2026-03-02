package com.bismaya.mediadl

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object TorrentNotificationHelper {
    private const val CHANNEL_ID = "mediadl_torrent_downloads"
    private const val GROUP_KEY = "com.bismaya.mediadl.TORRENT_GROUP"
    private const val SUMMARY_ID = 9000

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Torrent Downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Torrent download progress and completion"
                setShowBadge(false)
            }
            val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            mgr.createNotificationChannel(channel)
        }
    }

    private fun hasPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33) {
            return ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        }
        return true
    }

    private fun launchIntent(context: Context): PendingIntent {
        val intent = context.packageManager
            .getLaunchIntentForPackage(context.packageName)
            ?.apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("navigate_to", "torrent")
            } ?: Intent()
        return PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun showProgress(context: Context, torrent: TorrentItem) {
        if (!hasPermission(context)) return
        val notifId = safeNotifId(torrent.infoHash)
        val pct = (torrent.progress * 100).toInt()
        val speed = formatSpeed(torrent.downloadSpeed)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(torrent.name)
            .setContentText("$pct% · $speed/s ↓ · ${torrent.seeders} seeds")
            .setProgress(100, pct, torrent.state == TorrentState.METADATA)
            .setOngoing(true)
            .setSilent(true)
            .setGroup(GROUP_KEY)
            .setContentIntent(launchIntent(context))
            .setPriority(NotificationCompat.PRIORITY_LOW)

        @Suppress("MissingPermission")
        NotificationManagerCompat.from(context).notify(notifId, builder.build())
    }

    fun showComplete(context: Context, torrent: TorrentItem) {
        if (!hasPermission(context)) return
        val notifId = safeNotifId(torrent.infoHash)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Download Complete")
            .setContentText(torrent.name)
            .setAutoCancel(true)
            .setGroup(GROUP_KEY)
            .setContentIntent(launchIntent(context))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        @Suppress("MissingPermission")
        NotificationManagerCompat.from(context).notify(notifId, builder.build())
    }

    fun cancel(context: Context, infoHash: String) {
        NotificationManagerCompat.from(context).cancel(safeNotifId(infoHash))
    }

    fun cancelAll(context: Context) {
        NotificationManagerCompat.from(context).cancelAll()
    }

    private fun safeNotifId(hash: String): Int {
        val id = hash.hashCode()
        return if (id == SUMMARY_ID) id + 1 else id
    }
}
