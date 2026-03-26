package com.example.healthsync.testutil

import com.example.healthsync.data.local.dao.HeartRateDao
import com.example.healthsync.data.local.entity.HeartRateEntity
import com.example.healthsync.data.local.entity.SyncState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.util.concurrent.ConcurrentHashMap

class FakeHeartRateDao : HeartRateDao {

    val store = ConcurrentHashMap<Long, HeartRateEntity>()
    private var autoId = 1L
    private val storeFlow = MutableStateFlow(0) // bump to trigger flow re-emit

    private fun notifyChange() {
        storeFlow.value++
    }

    override suspend fun insert(entity: HeartRateEntity): Long {
        val id = if (entity.id == 0L) autoId++ else entity.id
        val existing = store.values.find { it.eventId == entity.eventId }
        if (existing != null) return -1 // IGNORE on conflict
        store[id] = entity.copy(id = id)
        notifyChange()
        return id
    }

    override fun getRecentFlow(since: Long): Flow<List<HeartRateEntity>> =
        storeFlow.map { store.values.filter { it.timestamp >= since }.sortedByDescending { it.timestamp } }

    override fun getLatestFlow(): Flow<HeartRateEntity?> =
        storeFlow.map { store.values.maxByOrNull { it.timestamp } }

    override fun countByStatesFlow(states: List<SyncState>): Flow<Int> =
        storeFlow.map { store.values.count { it.syncState in states } }

    override suspend fun getPendingSync(states: List<SyncState>, now: Long, limit: Int): List<HeartRateEntity> =
        store.values
            .filter { it.syncState in states && it.nextAttemptAt <= now }
            .sortedBy { it.nextAttemptAt }
            .take(limit)

    override suspend fun claimForSync(ids: List<Long>): Int {
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

    override suspend fun markSyncing(ids: List<Long>, fromState1: SyncState, fromState2: SyncState): Int =
        claimForSync(ids)

    override suspend fun markSynced(id: Long, remoteId: String, state: SyncState) {
        store[id]?.let { store[id] = it.copy(syncState = state, remoteId = remoteId, lastError = null, attemptCount = 0) }
        notifyChange()
    }

    override suspend fun markFailed(id: Long, state: SyncState, attemptCount: Int, nextAttemptAt: Long, error: String?) {
        store[id]?.let {
            store[id] = it.copy(syncState = state, attemptCount = attemptCount, nextAttemptAt = nextAttemptAt, lastError = error)
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

    override suspend fun releaseSyncingByIds(ids: List<Long>): Int {
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

    override fun getAllFlow(): Flow<List<HeartRateEntity>> =
        storeFlow.map { store.values.sortedByDescending { it.timestamp } }
}
