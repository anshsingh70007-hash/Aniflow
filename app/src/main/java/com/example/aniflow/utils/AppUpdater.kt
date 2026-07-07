package com.example.aniflow.utils

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

object AppUpdater {
    fun downloadAndInstall(context: Context, url: String, versionName: String) {
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val destinationFile = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
            "AniFinal_$versionName.apk"
        )
        
        // Delete existing file if present
        if (destinationFile.exists()) {
            destinationFile.delete()
        }

        try {
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle("Downloading AniFlow Update")
                .setDescription("Version $versionName")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "AniFinal_$versionName.apk")

            val downloadId = downloadManager.enqueue(request)
            Toast.makeText(context, "Download started in background...", Toast.LENGTH_SHORT).show()

            // Register broadcast receiver to handle download completion and install
            val onComplete = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                    if (id == downloadId) {
                        try {
                            ctx.unregisterReceiver(this)
                        } catch (e: Exception) {
                            // ignore
                        }
                        installApk(ctx, destinationFile)
                    }
                }
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(
                    onComplete,
                    IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                    Context.RECEIVER_EXPORTED
                )
            } else {
                context.registerReceiver(
                    onComplete,
                    IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "Failed to start download. Opening in browser instead...", Toast.LENGTH_LONG).show()
            try {
                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(browserIntent)
            } catch (ex: Exception) {
                Toast.makeText(context, "No app available to open link.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun installApk(context: Context, file: File) {
        try {
            val apkUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            context.startActivity(installIntent)
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "Failed to launch package installer.", Toast.LENGTH_LONG).show()
        }
    }
}
