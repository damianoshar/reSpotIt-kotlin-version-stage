package com.spotifydashboard.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [AlbumEntity::class, PlaylistEntity::class, PlaylistAlbumEntity::class, ConcertEntity::class], version = 4)
abstract class AppDatabase : RoomDatabase() {
    abstract fun albumDao(): AlbumDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun concertDao(): ConcertDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE playlist_albums ADD COLUMN addedAt INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * Existing libraries were synced before Spotify's release type was
         * stored. They start as "" (unknown) and are refreshed from the
         * saved-albums pages on the next app start, so old singles are not
         * stuck under the "Albums" filter.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE albums ADD COLUMN albumType TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS concerts (" +
                    "id TEXT NOT NULL PRIMARY KEY, " +
                    "artist TEXT NOT NULL, " +
                    "artistId TEXT NOT NULL DEFAULT '', " +
                    "title TEXT NOT NULL DEFAULT '', " +
                    "date TEXT NOT NULL DEFAULT '', " +
                    "venue TEXT NOT NULL DEFAULT '', " +
                    "url TEXT NOT NULL DEFAULT '', " +
                    "sourceUrl TEXT NOT NULL DEFAULT '', " +
                    "followed INTEGER NOT NULL DEFAULT 0, " +
                    "createdAt TEXT NOT NULL DEFAULT ''" +
                    ")"
                )
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "albums_db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
