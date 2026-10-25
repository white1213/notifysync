package com.notifysync.android.core

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 内存环形日志（最近 300 条），设置页可查看/复制，用于远程排错 */
object LogBus {
    private const val MAX = 300
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.CHINA)

    fun log(tag: String, msg: String) {
        val line = "${fmt.format(Date())} $tag: $msg"
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > MAX) lines.removeFirst()
        }
        Log.d("NS-$tag", msg)
    }

    fun dump(): String = synchronized(lines) { lines.joinToString("\n") }
}
