package com.example.healthsync.data.sync

import com.example.healthsync.data.local.entity.SleepQuality
import com.example.healthsync.data.local.entity.SleepRecordEntity
import com.example.healthsync.data.local.entity.SyncState
import com.example.healthsync.data.remote.ConflictResponse
import com.example.healthsync.data.remote.ServerSleepData
import com.example.healthsync.testutil.FakeSleepRecordDao
import com.google.gson.Gson
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * [ConflictResolver] 的单元测试。
 *
 * 使用 [FakeSleepRecordDao] 替代 Room DAO，验证冲突处理与解决流程：
 * - [handleConflict]：将记录标记为 CONFLICT 并保存服务端快照
 * - [resolveConflict]：KEEP_LOCAL / KEEP_REMOTE / MERGE 三种策略各自的数据更新行为
 */
class ConflictResolverTest {

    private lateinit var sleepRecordDao: FakeSleepRecordDao
    private lateinit var resolver: ConflictResolver
    private val gson = Gson()

    @Before
    fun setup() {
        sleepRecordDao = FakeSleepRecordDao()
        resolver = ConflictResolver(sleepRecordDao, gson)
    }

    /** 构造测试用的睡眠记录实体，提供合理默认值以减少测试样板代码。 */
    private fun makeSleepRecord(
        id: String = "sleep-1",
        syncState: SyncState = SyncState.SYNCING,
        startTime: Long = 1000L,
        endTime: Long = 9000L,
        quality: SleepQuality = SleepQuality.GOOD,
        remoteVersion: Int = 1
    ) = SleepRecordEntity(
        id = id,
        startTime = startTime,
        endTime = endTime,
        quality = quality,
        sourceId = "test",
        syncState = syncState,
        remoteVersion = remoteVersion,
        baseRemoteVersion = remoteVersion
    )

    /** 构造模拟的服务端冲突响应，包含服务端最新数据与版本号。 */
    private fun makeConflictResponse(
        startTime: Long = 2000L,
        endTime: Long = 10000L,
        quality: String = "FAIR",
        remoteVersion: Int = 2
    ) = ConflictResponse(
        currentRemoteVersion = remoteVersion,
        serverData = ServerSleepData(
            startTime = startTime,
            endTime = endTime,
            quality = quality,
            remoteVersion = remoteVersion
        )
    )

    @Test
    fun `handleConflict marks record as CONFLICT with server snapshot`() = runTest {
        val record = makeSleepRecord()
        sleepRecordDao.store[record.id] = record
        val conflict = makeConflictResponse()

        resolver.handleConflict(record.id, conflict)

        val updated = sleepRecordDao.store[record.id]!!
        assertEquals(SyncState.CONFLICT, updated.syncState)
        assertEquals(gson.toJson(conflict.serverData), updated.serverSnapshot)
    }

    @Test
    fun `resolveConflict KEEP_LOCAL sets LOCAL_PENDING with server version`() = runTest {
        val record = makeSleepRecord(syncState = SyncState.CONFLICT)
        val serverData = ServerSleepData(2000L, 10000L, "FAIR", 2)
        val snapshot = gson.toJson(serverData)
        sleepRecordDao.store[record.id] = record.copy(serverSnapshot = snapshot)

        resolver.resolveConflict(record.id, ConflictResolution.KEEP_LOCAL)

        val resolved = sleepRecordDao.store[record.id]!!
        assertEquals(SyncState.LOCAL_PENDING, resolved.syncState)
        assertEquals(record.startTime, resolved.startTime)
        assertEquals(record.endTime, resolved.endTime)
        assertEquals(SleepQuality.GOOD, resolved.quality)
        assertEquals(2, resolved.baseRemoteVersion)
        assertNull(resolved.serverSnapshot)
    }

    @Test
    fun `resolveConflict KEEP_REMOTE sets SYNCED with server data`() = runTest {
        val record = makeSleepRecord(syncState = SyncState.CONFLICT)
        val serverData = ServerSleepData(2000L, 10000L, "FAIR", 2)
        val snapshot = gson.toJson(serverData)
        sleepRecordDao.store[record.id] = record.copy(serverSnapshot = snapshot)

        resolver.resolveConflict(record.id, ConflictResolution.KEEP_REMOTE)

        val resolved = sleepRecordDao.store[record.id]!!
        assertEquals(SyncState.SYNCED, resolved.syncState)
        assertEquals(2000L, resolved.startTime)
        assertEquals(10000L, resolved.endTime)
        assertEquals(SleepQuality.FAIR, resolved.quality)
        assertEquals(2, resolved.remoteVersion)
        assertNull(resolved.serverSnapshot)
    }

    @Test
    fun `resolveConflict MERGE takes union of time ranges and keeps local quality`() = runTest {
        val record = makeSleepRecord(
            syncState = SyncState.CONFLICT,
            startTime = 1000L,
            endTime = 9000L,
            quality = SleepQuality.GOOD
        )
        val serverData = ServerSleepData(2000L, 10000L, "FAIR", 2)
        val snapshot = gson.toJson(serverData)
        sleepRecordDao.store[record.id] = record.copy(serverSnapshot = snapshot)

        resolver.resolveConflict(record.id, ConflictResolution.MERGE)

        val resolved = sleepRecordDao.store[record.id]!!
        assertEquals(SyncState.LOCAL_PENDING, resolved.syncState)
        assertEquals(1000L, resolved.startTime)   // min(1000, 2000)
        assertEquals(10000L, resolved.endTime)     // max(9000, 10000)
        assertEquals(SleepQuality.GOOD, resolved.quality)  // local quality kept
        assertEquals(2, resolved.baseRemoteVersion)
        assertNull(resolved.serverSnapshot)
    }
}
