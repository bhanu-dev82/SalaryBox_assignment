package com.bhanu.attendance.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import com.bhanu.attendance.core.common.crash.CrashHandler
import com.bhanu.attendance.core.common.crash.CrashLogStore
import com.bhanu.attendance.core.common.crash.FileCrashLogStore
import com.bhanu.attendance.core.common.dispatchers.AppDispatchers
import com.bhanu.attendance.core.common.dispatchers.DefaultAppDispatchers
import com.bhanu.attendance.core.common.logging.AppLogger
import com.bhanu.attendance.core.common.isDebuggable
import com.bhanu.attendance.core.common.logging.LogcatLogger
import com.bhanu.attendance.core.common.logging.NoOpLogger
import com.bhanu.attendance.data.face.FaceEngine
import com.bhanu.attendance.data.face.MediaPipeFaceEngine
import com.bhanu.attendance.data.local.AttendanceDatabase
import com.bhanu.attendance.data.local.dao.AttendanceDao
import com.bhanu.attendance.data.local.dao.AuditDao
import com.bhanu.attendance.data.local.dao.FaceTemplateDao
import com.bhanu.attendance.data.local.dao.SettingsDao
import com.bhanu.attendance.data.local.dao.StaffDao
import com.bhanu.attendance.data.security.Pbkdf2PinHasher
import com.bhanu.attendance.data.location.PlayServicesLocationProvider
import com.bhanu.attendance.domain.repository.LocationProvider
import com.bhanu.attendance.data.security.PinHasher
import com.bhanu.attendance.data.storage.FileSelfieStorage
import com.bhanu.attendance.domain.repository.AttendanceRepository
import com.bhanu.attendance.data.repository.AttendanceRepositoryImpl
import com.bhanu.attendance.domain.repository.AuditRepository
import com.bhanu.attendance.data.repository.AuditRepositoryImpl
import com.bhanu.attendance.domain.repository.AuthRepository
import com.bhanu.attendance.data.repository.AuthRepositoryImpl
import com.bhanu.attendance.domain.repository.FaceTemplateRepository
import com.bhanu.attendance.data.repository.FaceTemplateRepositoryImpl
import com.bhanu.attendance.domain.repository.SelfieStorage
import com.bhanu.attendance.domain.repository.SettingsRepository
import com.bhanu.attendance.data.repository.SettingsRepositoryImpl
import com.bhanu.attendance.domain.repository.StaffRepository
import com.bhanu.attendance.data.repository.StaffRepositoryImpl
import com.bhanu.attendance.domain.time.IdGenerator
import com.bhanu.attendance.domain.time.TimeProvider
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

/**
 * Core plumbing.
 *
 * Logging is bound to a no-op in release and logcat in debug, chosen by reading the running
 * APK's debuggable flag rather than a build constant, so `:data` does not need `BuildConfig`.
 */
@Module
@InstallIn(SingletonComponent::class)
object CoreModule {

    @Provides
    @Singleton
    fun provideDispatchers(): AppDispatchers = DefaultAppDispatchers()

    @Provides
    @Singleton
    fun provideLogger(@ApplicationContext context: Context): AppLogger =
        if (context.isDebuggable) LogcatLogger(context.isDebuggable) else NoOpLogger

    /**
     * A `SupervisorJob` so one failed child — an audit write, a camera rebind — cannot cancel
     * its siblings. Work is also given a `CoroutineExceptionHandler` so a failure on the root
     * scope is recorded rather than silently lost.
     */
    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(logger: AppLogger): CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + RecordingCoroutineExceptionHandler(logger)
    )

    @Provides
    @Singleton
    fun provideTimeProvider(): TimeProvider = object : TimeProvider {
        override fun now() = java.time.Instant.now()
    }

    @Provides
    @Singleton
    fun provideIdGenerator(): IdGenerator = IdGenerator.RANDOM

    @Provides
    @Singleton
    fun provideCrashLogStore(@ApplicationContext context: Context): CrashLogStore =
        FileCrashLogStore(File(context.filesDir, "crash-reports"))

    @Provides
    @Singleton
    fun provideCrashHandler(
        store: CrashLogStore,
        @ApplicationScope scope: CoroutineScope,
        logger: AppLogger,
    ): CrashHandler = CrashHandler(store, scope, logger, appVersion = "1.0.0")
}

/**
 * Records failures that escape the application scope.
 *
 * `SupervisorJob` stops one failing child from cancelling its siblings, but a failure on the
 * scope *itself* has nowhere else to go. Without this it would be swallowed, which is the
 * hardest kind of bug to notice: the work simply never happens and nothing is logged.
 */
private class RecordingCoroutineExceptionHandler(
    private val logger: AppLogger,
) : kotlinx.coroutines.CoroutineExceptionHandler {

    // CoroutineExceptionHandler is a CoroutineContext.Element, so the key is part of the
    // contract even though only the framework ever reads it.
    override val key: kotlin.coroutines.CoroutineContext.Key<*>
        get() = kotlinx.coroutines.CoroutineExceptionHandler

    override fun handleException(context: kotlin.coroutines.CoroutineContext, throwable: Throwable) {
        // CancellationException must pass through: it is control flow, not a failure.
        if (throwable is kotlinx.coroutines.CancellationException) return
        logger.e(TAG, "Unhandled coroutine failure", throwable)
    }

    private companion object {
        const val TAG = "AppScope"
    }
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AttendanceDatabase =
        Room.databaseBuilder(context, AttendanceDatabase::class.java, AttendanceDatabase.NAME)
            // WAL lets a read proceed while a write is in flight, which matters because the
            // attendance list is observed continuously while punches are being written.
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            // Enforced so a punch cannot reference a staff row that no longer exists. Without
            // this, SQLite defaults to foreign keys OFF and the constraint is decorative.
            .addCallback(
                object : androidx.room.RoomDatabase.Callback() {
                    override fun onOpen(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL("PRAGMA foreign_keys=ON")
                    }
                }
            )
            // No fallbackToDestructiveMigration: silently wiping someone's attendance on a
            // schema change would be far worse than failing loudly.
            .build()

    @Provides
    fun provideStaffDao(database: AttendanceDatabase): StaffDao = database.staffDao()

    @Provides
    fun provideFaceTemplateDao(database: AttendanceDatabase): FaceTemplateDao = database.faceTemplateDao()

    @Provides
    fun provideAttendanceDao(database: AttendanceDatabase): AttendanceDao = database.attendanceDao()

    @Provides
    fun provideAuditDao(database: AttendanceDatabase): AuditDao = database.auditDao()

    @Provides
    fun provideSettingsDao(database: AttendanceDatabase): SettingsDao = database.settingsDao()
}

@Module
@InstallIn(SingletonComponent::class)
object DataStoreModule {

    @Provides
    @Singleton
    fun providePreferencesDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create {
            context.preferencesDataStoreFile("bhanu_attendance.preferences_pb")
        }
}

@Module
@InstallIn(SingletonComponent::class)
object ExternalModule {

    @Provides
    @Singleton
    fun provideFusedLocationClient(@ApplicationContext context: Context) =
        com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(context)

    /**
     * The PIN hashing cost, stated once in the DI graph.
     *
     * Provided explicitly rather than relying on a constructor default, so that raising the
     * iteration count is a one-line, greppable change rather than a hunt through call sites.
     */
    @Provides
    @Singleton
    fun providePinHasher(): PinHasher =
        Pbkdf2PinHasher(iterations = Pbkdf2PinHasher.DEFAULT_ITERATIONS)

    @Provides
    @Singleton
    fun provideSelfieStorage(
        dispatchers: AppDispatchers,
        logger: AppLogger,
        @ApplicationContext context: Context,
    ): SelfieStorage = FileSelfieStorage(dispatchers, logger, context.filesDir)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindAuthRepository(impl: AuthRepositoryImpl): AuthRepository

    @Binds
    @Singleton
    abstract fun bindStaffRepository(impl: StaffRepositoryImpl): StaffRepository

    @Binds
    @Singleton
    abstract fun bindFaceTemplateRepository(impl: FaceTemplateRepositoryImpl): FaceTemplateRepository

    @Binds
    @Singleton
    abstract fun bindAttendanceRepository(impl: AttendanceRepositoryImpl): AttendanceRepository

    @Binds
    @Singleton
    abstract fun bindSettingsRepository(impl: SettingsRepositoryImpl): SettingsRepository

    @Binds
    @Singleton
    abstract fun bindAuditRepository(impl: AuditRepositoryImpl): AuditRepository

    @Binds
    @Singleton
    abstract fun bindFaceEngine(impl: MediaPipeFaceEngine): FaceEngine

    @Binds
    @Singleton
    abstract fun bindLocationProvider(impl: PlayServicesLocationProvider): LocationProvider
}
