package com.recon.dash.data

import android.content.Context
import androidx.room.*

class Converters {
    @TypeConverter
    fun fromFavoriteSlot(slot: FavoriteSlot): String = slot.name

    @TypeConverter
    fun toFavoriteSlot(value: String): FavoriteSlot = FavoriteSlot.valueOf(value)
}

@Database(
    entities = [FavoritePlace::class, RideRecord::class],
    version = 1,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun favoritePlaceDao(): FavoritePlaceDao
    abstract fun rideRecordDao(): RideRecordDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "recon_dash.db",
                ).build().also { instance = it }
            }
    }
}
