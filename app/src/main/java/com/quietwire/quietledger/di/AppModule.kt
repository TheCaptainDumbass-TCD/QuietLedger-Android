package com.quietwire.quietledger.di

import android.content.Context
import com.quietwire.quietledger.data.backup.AutoBackup
import com.quietwire.quietledger.data.backup.ServerPublicKey
import com.quietwire.quietledger.core.crypto.DatabaseKeyStore
import com.quietwire.quietledger.BuildConfig
import com.quietwire.quietledger.data.backup.BackupRepository
import com.quietwire.quietledger.data.db.DatabaseHolder
import com.quietwire.quietledger.data.db.DatabaseProvider
import com.quietwire.quietledger.data.repository.DebtRepository
import com.quietwire.quietledger.session.SessionManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabaseKeyStore(@ApplicationContext context: Context): DatabaseKeyStore =
        DatabaseKeyStore(context)

    // Provided here rather than with constructor injection, matching the
    // pattern above: these take a plain Context, and Hilt needs the
    // @ApplicationContext qualifier to know which one.
    @Provides
    @Singleton
    fun provideServerPublicKey(@ApplicationContext context: Context): ServerPublicKey =
        ServerPublicKey(context)

    @Provides
    @Singleton
    fun provideAutoBackup(
        @ApplicationContext context: Context,
        backups: BackupRepository,
        serverKey: ServerPublicKey
    ): AutoBackup = AutoBackup(context, backups, serverKey)

    @Provides
    @Singleton
    fun provideDatabaseProvider(
        @ApplicationContext context: Context,
        keyStore: DatabaseKeyStore
    ): DatabaseProvider = DatabaseProvider(context, keyStore)

    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @Singleton
    fun provideSessionManager(
        keyStore: DatabaseKeyStore,
        databases: DatabaseProvider,
        @IoDispatcher io: CoroutineDispatcher
    ): SessionManager = SessionManager(keyStore, databases, io)

    /**
     * Binds the interface to the implementation, so repositories can simply
     * ask for a [DatabaseHolder] with an @Inject constructor.
     *
     * Without this every repository has to be hand-constructed in this module,
     * which is why the two below exist in that form. Repositories depend on the
     * interface rather than the concrete provider because that is what lets them
     * be tested against an in-memory database.
     */
    @Provides
    @Singleton
    fun provideDatabaseHolder(provider: DatabaseProvider): DatabaseHolder = provider

    @Provides
    @Singleton
    fun provideDebtRepository(databases: DatabaseProvider): DebtRepository =
        DebtRepository(databases)

    @Provides
    @Singleton
    fun provideBackupRepository(databases: DatabaseProvider): BackupRepository =
        BackupRepository(databases, BuildConfig.VERSION_NAME)
}
