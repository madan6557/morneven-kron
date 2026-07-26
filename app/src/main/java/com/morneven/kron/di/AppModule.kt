package com.morneven.kron.di

import android.content.Context
import com.morneven.kron.data.KronDatabase
import com.morneven.kron.team.TeamDriveRestClient
import com.morneven.kron.team.TeamSnapshotCryptor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): KronDatabase = KronDatabase.getInstance(context)

    @Provides
    @Singleton
    fun provideTeamDriveClient(): TeamDriveRestClient = TeamDriveRestClient()

    @Provides
    @Singleton
    fun provideTeamSnapshotCryptor(): TeamSnapshotCryptor = TeamSnapshotCryptor()
}
