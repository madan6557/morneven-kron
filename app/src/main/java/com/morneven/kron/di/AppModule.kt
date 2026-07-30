package com.morneven.kron.di

import com.morneven.kron.team.TeamDriveRestClient
import com.morneven.kron.team.TeamSnapshotCryptor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun provideTeamDriveClient(): TeamDriveRestClient = TeamDriveRestClient()

    @Provides
    @Singleton
    fun provideTeamSnapshotCryptor(): TeamSnapshotCryptor = TeamSnapshotCryptor()
}
