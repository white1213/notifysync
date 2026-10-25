package com.notifysync.android.core

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** 通知历史存储。主键 (origin_id, nid) 天然去重。 */
class Db(context: Context) : SQLiteOpenHelper(context, "notif.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS notif(
                nid TEXT NOT NULL,
                origin_id TEXT NOT NULL,
                origin_name TEXT NOT NULL,
                pkg TEXT NOT NULL,
                app TEXT NOT NULL,
                title TEXT NOT NULL,
                text TEXT NOT NULL,
                sub_text TEXT NOT NULL,
                conv_title TEXT NOT NULL,
                ts INTEGER NOT NULL,
                recv_ts INTEGER NOT NULL,
                ongoing INTEGER NOT NULL DEFAULT 0,
                otp TEXT,
                PRIMARY KEY(origin_id, nid)
            )""",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_notif_ts ON notif(ts DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1
    }

    @Synchronized
    fun insertIfAbsent(n: NotifRecord): Boolean {
        val cv = ContentValues().apply {
            put("nid", n.nid)
            put("origin_id", n.originId)
            put("origin_name", n.originName)
            put("pkg", n.pkg)
            put("app", n.app)
            put("title", n.title)
            put("text", n.text)
            put("sub_text", n.subText)
            put("conv_title", n.convTitle)
            put("ts", n.ts)
            put("recv_ts", n.recvTs)
            put("ongoing", if (n.ongoing) 1 else 0)
            put("otp", n.otp)
        }
        val rowId = writableDatabase.insertWithOnConflict("notif", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
        return rowId != -1L
    }

    @Synchronized
    fun queryRecent(limit: Int = 2000): List<NotifRecord> {
        val out = ArrayList<NotifRecord>(limit)
        try {
            readableDatabase.rawQuery(
                "SELECT nid,origin_id,origin_name,pkg,app,title,text,sub_text,conv_title,ts,recv_ts,ongoing,otp " +
                    "FROM notif ORDER BY ts DESC, recv_ts DESC LIMIT ?",
                arrayOf(limit.toString()),
            ).use { c ->
                while (c.moveToNext()) {
                    out.add(
                        NotifRecord(
                            nid = c.getString(0),
                            originId = c.getString(1),
                            originName = c.getString(2),
                            pkg = c.getString(3),
                            app = c.getString(4),
                            title = c.getString(5),
                            text = c.getString(6),
                            subText = c.getString(7),
                            convTitle = c.getString(8),
                            ts = c.getLong(9),
                            recvTs = c.getLong(10),
                            ongoing = c.getInt(11) != 0,
                            otp = if (c.isNull(12)) null else c.getString(12),
                        ),
                    )
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    /** 最近 N 秒内是否有完全相同内容的记录（本机通知去重，防聊天类 app 连续刷新） */
    @Synchronized
    fun hasSimilarRecently(originId: String, pkg: String, title: String, text: String, withinMs: Long): Boolean {
        try {
            readableDatabase.rawQuery(
                "SELECT 1 FROM notif WHERE origin_id=? AND pkg=? AND title=? AND text=? AND recv_ts>? LIMIT 1",
                arrayOf(originId, pkg, title, text, (System.currentTimeMillis() - withinMs).toString()),
            ).use { return it.moveToFirst() }
        } catch (_: Exception) {
            return false
        }
    }

    @Synchronized
    fun prune(days: Int) {
        try {
            writableDatabase.execSQL(
                "DELETE FROM notif WHERE ts < ?",
                arrayOf((System.currentTimeMillis() - days * 86400000L).toString()),
            )
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun clearAll() {
        try {
            writableDatabase.execSQL("DELETE FROM notif")
        } catch (_: Exception) {
        }
    }
}
