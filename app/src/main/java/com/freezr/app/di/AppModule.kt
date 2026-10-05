package com.freezr.app.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import com.freezr.app.data.db.FreezrDatabase
import com.freezr.app.data.db.Migrations
import com.freezr.app.domain.time.SystemTimeSource
import com.freezr.app.domain.time.TimeSource
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier @Retention(AnnotationRetention.BINARY) annotation class ApplicationScope
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class IoDispatcher
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class OwnPackage

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton
    fun database(@ApplicationContext ctx: Context): FreezrDatabase =
        Room.databaseBuilder(ctx, FreezrDatabase::class.java, FreezrDatabase.NAME)
            .addMigrations(*Migrations.ALL)
            .build()

    @Provides fun scheduleDao(db: FreezrDatabase) = db.scheduleDao()
    @Provides fun appSelectionDao(db: FreezrDatabase) = db.appSelectionDao()
    @Provides fun limitDao(db: FreezrDatabase) = db.limitDao()
    @Provides fun emergencyDao(db: FreezrDatabase) = db.emergencyDao()
    @Provides fun quickFreezeDao(db: FreezrDatabase) = db.quickFreezeDao()
    @Provides fun whitelistDao(db: FreezrDatabase) = db.whitelistDao()
    @Provides fun insightsDao(db: FreezrDatabase) = db.insightsDao()
    @Provides fun contextRuleDao(db: FreezrDatabase) = db.contextRuleDao()
    @Provides fun domainDao(db: FreezrDatabase) = db.domainDao()

    @Provides @Singleton
    fun dataStore(@ApplicationContext ctx: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { ctx.preferencesDataStoreFile("freezr_settings") }

    @Provides @Singleton
    fun timeSource(): TimeSource = SystemTimeSource()

    @Provides @Singleton @ApplicationScope
    fun appScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides @IoDispatcher
    fun io(): CoroutineDispatcher = Dispatchers.IO

    @Provides @OwnPackage
    fun ownPackage(@ApplicationContext ctx: Context): String = ctx.packageName
}
