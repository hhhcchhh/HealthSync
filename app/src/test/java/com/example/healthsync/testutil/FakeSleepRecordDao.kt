package com.example.healthsync.testutil

import com.example.healthsync.data.local.dao.SleepRecordDao
import com.example.healthsync.data.local.entity.SleepQuality
import com.example.healthsync.data.local.entity.SleepRecordEntity
import com.example.healthsync.data.local.entity.SyncState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.util.concurrent.ConcurrentHashMap

/**
 * [SleepRecordDao] 的内存伪实现，用于单元测试。
 *
 * 内部使用 [ConcurrentHashMap] 作为存储，通过 [MutableStateFlow] 的自增计数器
 * 驱动所有 Flow 查询在数据变更后自动重新发射，模拟 Room 的 invalidation 行为。
 */
class FakeSleepRecordDao : SleepRecordDao {

    /** 公开存储映射，方便测试代码直接读写和断言。 */
    val store = ConcurrentHashMap<String, SleepRecordEntity>()
    private val storeFlow = MutableStateFlow(0)

    private fun notifyChange() { storeFlow.value++ }

    override suspend fun upsert(entity: SleepRecordEntity) {
        store[entity.id] = entity
        notifyChange()
    }

    override fun getAllFlow(): Flow<List<SleepRecordEntity>> =
        storeFlow.map { store.values.sortedByDescending { it.startTime } }

    override fun getLatestFlow(): Flow<SleepRecordEntity?> =
        storeFlow.map { store.values.maxByOrNull { it.endTime } }

    override suspend fun getById(id: String): SleepRecordEntity? = store[id]

    override fun getConflictsFlow(): Flow<List<SleepRecordEntity>> =
        storeFlow.map { store.values.filter { it.syncState == SyncState.CONFLICT } }

    override fun countByStatesFlow(states: List<SyncState>): Flow<Int> =
        storeFlow.map { store.values.count { it.syncState in states } }

    override suspend fun getPendingSync(states: List<SyncState>, now: Long, limit: Int): List<SleepRecordEntity> =
        store.values
            .filter { it.syncState in states && it.nextAttemptAt <= now }
            .sortedBy { it.nextAttemptAt }
            .take(limit)

    override suspend fun claimForSync(ids: List<String>): Int {
        var count = 0
        ids.forEach { id ->
            val entity = store[id]
            if (entity != null && entity.syncState in listOf(SyncState.LOCAL_PENDING, SyncState.SYNC_FAILED)) {
                store[id] = entity.copy(syncState = SyncState.SYNCING)
                count++
            }
        }
        notifyChange()
        return count
    }

    override suspend fun markSyncing(ids: List<String>, fromState1: SyncState, fromState2: SyncState): Int =
        claimForSync(ids)

    override suspend fun markSynced(id: String, remoteId: String, remoteVersion: Int, state: SyncState) {
        store[id]?.let {
            store[id] = it.copy(
                syncState = state,
                remoteId = remoteId,
                remoteVersion = remoteVersion,
                lastError = null,
                attemptCount = 0
            )
        }
        notifyChange()
    }

    override suspend fun markFailed(id: String, state: SyncState, attemptCount: Int, nextAttemptAt: Long, error: String?) {
        store[id]?.let {
            store[id] = it.copy(syncState = state, attemptCount = attemptCount, nextAttemptAt = nextAttemptAt, lastError = error)
        }
        notifyChange()
    }

    override suspend fun markConflict(id: String, snapshot: String) {
        store[id]?.let {
            store[id] = it.copy(syncState = SyncState.CONFLICT, serverSnapshot = snapshot)
        }
        notifyChange()
    }

    override suspend fun resetSyncingToLocal(): Int {
        var count = 0
        store.forEach { (id, entity) ->
            if (entity.syncState == SyncState.SYNCING) {
                store[id] = entity.copy(syncState = SyncState.LOCAL_PENDING)
                count++
            }
        }
        notifyChange()
        return count
    }

    override suspend fun releaseSyncingByIds(ids: List<String>): Int {
        var count = 0
        ids.forEach { id ->
            val entity = store[id]
            if (entity != null && entity.syncState == SyncState.SYNCING) {
                store[id] = entity.copy(syncState = SyncState.LOCAL_PENDING)
                count++
            }
        }
        if (count > 0) notifyChange()
        return count
    }

    override suspend fun resolveConflict(
        id: String,
        syncState: SyncState,
        startTime: Long,
        endTime: Long,
        quality: String,
        baseRemoteVersion: Int,
        remoteVersion: Int
    ) {
        store[id]?.let {
            store[id] = it.copy(
                syncState = syncState,
                startTime = startTime,
                endTime = endTime,
                quality = SleepQuality.valueOf(quality),
                baseRemoteVersion = baseRemoteVersion,
                remoteVersion = remoteVersion,
                localVersion = it.localVersion + 1,
                serverSnapshot = null,
                attemptCount = 0,
                nextAttemptAt = 0,
                lastError = null
            )
        }
        notifyChange()
    }
}
