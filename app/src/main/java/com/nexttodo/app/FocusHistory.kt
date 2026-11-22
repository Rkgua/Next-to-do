package com.nexttodo.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

internal class FocusHistory private constructor(context: Context) : SQLiteOpenHelper(context, "focus-history.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE segments (id TEXT PRIMARY KEY, session TEXT NOT NULL, start INTEGER NOT NULL, end INTEGER NOT NULL, kind TEXT NOT NULL, completed INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX segment_time ON segments(start)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun record(segment: FocusSegment) {
        if (segment.end <= segment.start) return
        val values = ContentValues().apply {
            put("id", segment.id); put("session", segment.session); put("start", segment.start)
            put("end", segment.end); put("kind", segment.kind.name); put("completed", if (segment.completed) 1 else 0)
        }
        check(writableDatabase.insertWithOnConflict("segments", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L ||
            readableDatabase.rawQuery("SELECT id FROM segments WHERE id=?", arrayOf(segment.id)).use { it.moveToFirst() })
    }

    fun read(): List<FocusSegment> = readableDatabase.rawQuery("SELECT id,session,start,end,kind,completed FROM segments ORDER BY start DESC", null).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(FocusSegment(cursor.getString(0), cursor.getString(1), cursor.getLong(2),
                cursor.getLong(3), FocusKind.valueOf(cursor.getString(4)), cursor.getInt(5) == 1))
        }
    }

    companion object {
        @Volatile private var instance: FocusHistory? = null
        fun get(context: Context): FocusHistory = instance ?: synchronized(this) {
            instance ?: FocusHistory(context.applicationContext).also { instance = it }
        }
    }
}
