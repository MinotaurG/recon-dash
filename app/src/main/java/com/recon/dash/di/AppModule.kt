package com.recon.dash.di

import android.content.Context
import com.recon.dash.dash.DashConfig
import com.recon.dash.dash.nav.Router
import com.recon.dash.data.AppDatabase
import com.recon.dash.data.FavoritePlaceDao
import com.recon.dash.data.FuelFillupDao
import com.recon.dash.data.RideRecordDao
import com.recon.dash.data.RouteDivergenceDao
import com.recon.dash.data.ServiceItemDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

/** Marks the process-lifetime CoroutineScope for work that must outlive any ViewModel. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun provideDashConfig(@ApplicationContext context: Context): DashConfig =
        DashConfig.get(context)

    /**
     * A single, process-lifetime Router. Previously each ViewModel constructed its own, so the
     * Valhalla engine loaded up to 3x and each instance leaked its native handle on VM teardown.
     * As a Singleton it loads once and lives for the process.
     */
    @Provides
    @Singleton
    fun provideRouter(@ApplicationContext context: Context): Router = Router(context)

    /**
     * Process-lifetime scope for fire-and-forget work that must survive a ViewModel being cleared
     * (e.g. saving a ride when the nav screen closes). SupervisorJob so one failure can't cancel
     * siblings; Dispatchers.Default as a sane base (DB work switches to IO internally).
     */
    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        AppDatabase.get(context)

    @Provides
    fun provideFavoritePlaceDao(db: AppDatabase): FavoritePlaceDao =
        db.favoritePlaceDao()

    @Provides
    fun provideRideRecordDao(db: AppDatabase): RideRecordDao =
        db.rideRecordDao()

    @Provides
    fun provideFuelFillupDao(db: AppDatabase): FuelFillupDao =
        db.fuelFillupDao()

    @Provides
    fun provideServiceItemDao(db: AppDatabase): ServiceItemDao =
        db.serviceItemDao()

    @Provides
    fun provideRouteDivergenceDao(db: AppDatabase): RouteDivergenceDao =
        db.routeDivergenceDao()
}
