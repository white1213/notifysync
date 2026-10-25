package com.notifysync.android

import android.app.Application
import android.os.Build
import android.util.Log
import com.notifysync.android.core.Db
import com.notifysync.android.core.NotifPoster
import com.notifysync.android.core.Prefs
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class App : Application() {

    lateinit var prefs: Prefs
        private set
    lateinit var db: Db
        private set

    /** 由 SyncService 创建/销毁，NotificationListener 通过它上报通知 */
    @Volatile var engine: com.notifysync.android.core.SyncEngine? = null

    val dbExecutor = Executors.newSingleThreadExecutor()

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        db = Db(this)
        NotifPoster.createChannels(this)
        installCrashHandler()
    }

    /** 崩溃时把堆栈写入 filesDir/crash.log，下次启动展示，方便远程排错 */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())
                File(filesDir, "crash.log").writeText(
                    "NotifySync crash · $stamp\n" +
                        "device=${Build.MANUFACTURER} ${Build.MODEL} android=${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
                        "thread=${t.name}\n\n" +
                        Log.getStackTraceString(e),
                )
            } catch (_: Exception) {
            }
            previous?.uncaughtException(t, e)
        }
    }

    fun readCrashLog(): String? = try {
        val f = File(filesDir, "crash.log")
        if (f.exists()) f.readText() else null
    } catch (_: Exception) {
        null
    }

    fun clearCrashLog() {
        try { File(filesDir, "crash.log").delete() } catch (_: Exception) {}
    }
}
