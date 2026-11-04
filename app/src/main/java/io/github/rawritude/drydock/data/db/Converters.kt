package io.github.rawritude.drydock.data.db

import androidx.room.TypeConverter

class Converters {

    @TypeConverter
    fun fromState(state: DownloadState): String = state.name

    @TypeConverter
    fun toState(value: String): DownloadState = DownloadState.valueOf(value)
}
