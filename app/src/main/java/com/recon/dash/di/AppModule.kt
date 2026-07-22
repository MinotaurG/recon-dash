package com.recon.dash.di

import android.content.Context
import com.recon.dash.dash.DashConfig
import com.recon.dash.data.AppDatabase
import com.recon.dash.data.FavoritePlaceDao
import com.recon.dash.data.FuelFillupDao
import com.recon.dash.data.RideRecordDao
import com.recon.dash.data.ServiceItemDao
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
    fun provideDashConfig(@ApplicationContext context: Context): DashConfig =
        DashConfig.get(context)

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
}
