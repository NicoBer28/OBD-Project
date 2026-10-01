package com.example.obd_app

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [Trip::class, TelemetryChunk::class, Association::class], version = 2)
abstract class AppDatabase : RoomDatabase() {
    abstract fun obdDao(): ObdDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        // Sin fallbackToDestructiveMigration: borraría los datos que todavía no se subieron.
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `associations` (`mac` TEXT NOT NULL, `associationId` INTEGER NOT NULL, `carId` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`mac`))"
                )
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "obd_local_database"
                ).addMigrations(MIGRATION_1_2).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
