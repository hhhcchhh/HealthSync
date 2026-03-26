package com.example.healthsync.testutil

/**
 * Helper for creating Room in-memory databases in instrumented tests (DESIGN §12.2).
 *
 * Usage (in androidTest):
 * ```
 * val db = InMemoryDbFactory.create(ApplicationProvider.getApplicationContext())
 * val dao = db.heartRateDao()
 * // ... run tests ...
 * db.close()
 * ```
 *
 * Note: This factory requires Android instrumentation context and should be
 * used in `androidTest` source set. For pure JVM unit tests, prefer Fake DAOs
 * (e.g. [FakeHeartRateDao], [FakeStepCountDao], [FakeSleepRecordDao]).
 */
object InMemoryDbFactory {

    /**
     * Creates an in-memory [com.example.healthsync.data.local.HealthDatabase].
     *
     * Requires `android.content.Context` — call from instrumented tests only.
     * The database is destroyed when the process ends or [close][androidx.room.RoomDatabase.close]
     * is called, ensuring test isolation.
     *
     * @param context Application or instrumentation context
     * @return A fully initialized in-memory HealthDatabase instance
     */
    fun create(context: android.content.Context): com.example.healthsync.data.local.HealthDatabase {
        return androidx.room.Room.inMemoryDatabaseBuilder(
            context,
            com.example.healthsync.data.local.HealthDatabase::class.java
        )
            .allowMainThreadQueries()
            .build()
    }
}
