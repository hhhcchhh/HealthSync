package com.example.healthsync.di

import android.content.Context
import androidx.room.Room
import com.example.healthsync.data.local.HealthDatabase
import com.example.healthsync.data.local.dao.HeartRateDao
import com.example.healthsync.data.local.dao.SleepRecordDao
import com.example.healthsync.data.local.dao.StepCountDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): HealthDatabase {
        return Room.databaseBuilder(
            context,
            HealthDatabase::class.java,
            "health_sync_db"
        ).build()
    }

    @Provides
    fun provideHeartRateDao(db: HealthDatabase): HeartRateDao = db.heartRateDao()

    @Provides
    fun provideStepCountDao(db: HealthDatabase): StepCountDao = db.stepCountDao()

    @Provides
    fun provideSleepRecordDao(db: HealthDatabase): SleepRecordDao = db.sleepRecordDao()
}
