package com.feldman.ha.widgets

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object WidgetSyncLogger {
    private const val LOG_FILE_NAME = "widget_sync_log.txt"
    private const val MAX_LOG_SIZE = 512 * 1024 // 512 KB

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    fun log(context: Context, message: String) {
        val timestamp = dateFormat.format(Date())
        val line = "[$timestamp] $message\n"
        
        // Also log to logcat for immediate debugging
        Log.d("HA_SyncLog", message)

        try {
            val file = File(context.filesDir, LOG_FILE_NAME)
            
            // Check size and rotate if necessary
            if (file.exists() && file.length() > MAX_LOG_SIZE) {
                val backup = File(context.filesDir, "${LOG_FILE_NAME}.old")
                if (backup.exists()) backup.delete()
                file.renameTo(backup)
            }

            file.appendText(line)
        } catch (e: Exception) {
            Log.e("HA_SyncLog", "Failed to write to persistent log", e)
        }
    }

    fun getLogs(context: Context): String {
        return try {
            val file = File(context.filesDir, LOG_FILE_NAME)
            if (file.exists()) file.readText() else "No logs found."
        } catch (e: Exception) {
            "Error reading logs: ${e.message}"
        }
    }

    fun clearLogs(context: Context) {
        try {
            val file = File(context.filesDir, LOG_FILE_NAME)
            if (file.exists()) file.delete()
            val backup = File(context.filesDir, "${LOG_FILE_NAME}.old")
            if (backup.exists()) backup.delete()
        } catch (e: Exception) {
            Log.e("HA_SyncLog", "Failed to clear logs", e)
        }
    }
}
