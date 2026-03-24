package com.example.healthsync.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.example.healthsync.data.local.dao.HeartRateDao
import com.example.healthsync.data.local.dao.SleepRecordDao
import com.example.healthsync.data.local.dao.StepCountDao
import com.example.healthsync.data.local.entity.HeartRateEntity
import com.example.healthsync.data.local.entity.SleepRecordEntity
import com.example.healthsync.data.local.entity.StepCountEntity

@Database(
    entities = [HeartRateEntity::class, StepCountEntity::class, SleepRecordEntity::class],
    version = 1,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class HealthDatabase : RoomDatabase() {
    abstract fun heartRateDao(): HeartRateDao
    abstract fun stepCountDao(): StepCountDao
    abstract fun sleepRecordDao(): SleepRecordDao
}
