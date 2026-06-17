package com.supportbubble.app.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [MessageEntity::class],
    version = 3,           // v2 added `pending`; v3 added `packageName`/`appName`
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun messageDao(): MessageDao

    companion object {

        /**
         * Adds the `pending` column introduced in schema version 2.
         * DEFAULT 0 means all existing messages are treated as delivered.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE messages ADD COLUMN pending INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /**
         * Adds the per-app thread columns introduced in schema version 3.
         * DEFAULT '' keeps every existing message in the general thread.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE messages ADD COLUMN packageName TEXT NOT NULL DEFAULT ''"
                )
                db.execSQL(
                    "ALTER TABLE messages ADD COLUMN appName TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "support_bubble.db",
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    // Safety net: destroy & recreate if migration path is missing.
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
