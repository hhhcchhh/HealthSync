package com.example.healthsync.data.local.entity

enum class SyncState {
    LOCAL_PENDING,
    SYNCING,
    SYNCED,
    SYNC_FAILED,
    CONFLICT
}
