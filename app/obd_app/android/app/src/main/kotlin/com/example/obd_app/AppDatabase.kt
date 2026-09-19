package com.example.obd_app

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [Trip::class, TelemetryChunk::class], version = 1)
abstract class AppDatabase : RoomDatabase() {
    abstract fun obdDao(): ObdDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "obd_local_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}