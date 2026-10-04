package com.choidev.choifile.search

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** [SqlDb] over the platform SQLite. One shared helper per process; calls block, so keep them off the main thread. */
class AndroidSqlDb private constructor(context: Context) : SqlDb {
    private val helper = object : SQLiteOpenHelper(context.applicationContext, "search_index.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) = Unit // tables are created by SearchIndex
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }
    private val db: SQLiteDatabase get() = helper.writableDatabase

    override fun execute(sql: String, args: List<Any?>) {
        if (args.isEmpty()) db.execSQL(sql) else db.execSQL(sql, args.map { if (it is Boolean) (if (it) 1L else 0L) else it }.toTypedArray())
    }

    override fun <T> query(sql: String, args: List<Any?>, map: (SqlRow) -> T): List<T> {
        val out = ArrayList<T>()
        db.rawQuery(sql, args.map { it.toString() }.toTypedArray()).use { c ->
            val row = object : SqlRow {
                override fun string(i: Int): String = c.getString(i) ?: ""
                override fun long(i: Int): Long = c.getLong(i)
            }
            while (c.moveToNext()) out += map(row)
        }
        return out
    }

    override fun <T> transaction(block: () -> T): T {
        val d = db
        d.beginTransaction()
        try {
            val r = block()
            d.setTransactionSuccessful()
            return r
        } finally {
            d.endTransaction()
        }
    }

    companion object {
        @Volatile private var instance: AndroidSqlDb? = null
        fun get(context: Context): AndroidSqlDb =
            instance ?: synchronized(this) { instance ?: AndroidSqlDb(context).also { instance = it } }
    }
}
