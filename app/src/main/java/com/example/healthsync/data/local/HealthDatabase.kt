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

/**
 * HealthSync Room 数据库（Milestone 0/1/2/5）。
 *
 * 事实来源（DESIGN §5.2/§5.3）：所有数据（心率、步数、睡眠）先落库于此，
 * 然后由同步引擎扫描待同步记录并上传云端。使用离线优先架构。
 *
 * 包含三个表：
 * - heart_rate：心率采样（append-only）
 * - step_count：步数增量（append-only）
 * - sleep_record：睡眠记录（可编辑，支持版本控制与冲突处理）
 *
 * @TypeConverters 用于自动序列化/反序列化枚举类型（SyncState、SleepQuality）。
 */
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
