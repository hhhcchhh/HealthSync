package com.example.healthsync.data.local

import androidx.room.TypeConverter
import com.example.healthsync.data.local.entity.SleepQuality
import com.example.healthsync.data.local.entity.SyncState

class Converters {

    @TypeConverter
    fun fromSyncState(value: SyncState): String = value.name

    @TypeConverter
    fun toSyncState(value: String): SyncState = SyncState.valueOf(value)

    @TypeConverter
    fun fromSleepQuality(value: SleepQuality): String = value.name

    @TypeConverter
    fun toSleepQuality(value: String): SleepQuality = SleepQuality.valueOf(value)
}
