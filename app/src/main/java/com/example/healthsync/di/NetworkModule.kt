package com.example.healthsync.di

import com.google.gson.Gson
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module for network / serialization dependencies (DESIGN §13).
 *
 * Provides [Gson] for JSON serialization (used by ConflictResolver
 * to serialize/deserialize serverSnapshot). MockCloudApi itself uses
 * constructor injection and does not need explicit binding here.
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideGson(): Gson = Gson()
}
