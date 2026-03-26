package com.example.healthsync.di

import com.example.healthsync.data.source.HealthDataSource
import com.example.healthsync.data.source.ManualInputSource
import com.example.healthsync.data.source.SimulatedBluetoothSource
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/**
 * Hilt module for data source bindings (DESIGN §4.4, §13).
 *
 * Uses Multibinding to register all [HealthDataSource] implementations,
 * enabling extensibility: adding a new data source (e.g. Health Connect)
 * requires only a new implementation + a new `@Provides @IntoSet` binding
 * in this module — no changes to Repository or SyncEngine.
 */
@Module
@InstallIn(SingletonComponent::class)
object DataSourceModule {

    @Provides
    @IntoSet
    fun provideSimulatedBluetoothSource(
        source: SimulatedBluetoothSource
    ): HealthDataSource = source

    @Provides
    @IntoSet
    fun provideManualInputSource(
        source: ManualInputSource
    ): HealthDataSource = source
}
