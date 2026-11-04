package io.github.rawritude.drydock.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(entities = [DownloadEntity::class], version = 1, exportSchema = false)
@TypeConverters(Converters::class)
abstract class DrydockDatabase : RoomDatabase() {

    abstract fun downloads(): DownloadDao

    companion object {
        fun open(context: Context): DrydockDatabase =
            Room.databaseBuilder(context, DrydockDatabase::class.java, "drydock.db").build()
    }
}
