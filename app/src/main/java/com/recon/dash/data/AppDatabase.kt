package com.recon.dash.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Converters {
    @TypeConverter
    fun fromFavoriteSlot(slot: FavoriteSlot): String = slot.name

    @TypeConverter
    fun toFavoriteSlot(value: String): FavoriteSlot = FavoriteSlot.valueOf(value)
}

@Database(
    entities = [FavoritePlace::class, RideRecord::class, FuelFillup::class, ServiceItem::class,
        RouteDivergenceRecord::class],
    version = 5,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun favoritePlaceDao(): FavoritePlaceDao
    abstract fun rideRecordDao(): RideRecordDao
    abstract fun fuelFillupDao(): FuelFillupDao
    abstract fun serviceItemDao(): ServiceItemDao
    abstract fun routeDivergenceDao(): RouteDivergenceDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        /**
         * v4→v5: saved-places overhaul. Add the `icon` column, and remap the old fixed custom
         * slots to the new presets (CUSTOM_1→GYM, _2→FRIEND_1, _3→FRIEND_2, _4→FUEL) so the
         * user's existing custom places survive with a sensible new home. Home/Office untouched.
         * (A destructive fallback would wipe the user's real saved Home/Office — hence a real
         * migration.)
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE favorite_places ADD COLUMN icon TEXT NOT NULL DEFAULT ''")
                val remap = mapOf(
                    "CUSTOM_1" to "GYM", "CUSTOM_2" to "FRIEND_1",
                    "CUSTOM_3" to "FRIEND_2", "CUSTOM_4" to "FUEL",
                )
                for ((old, new) in remap) {
                    // Only remap if the target slot isn't already taken (avoid PK collision).
                    db.execSQL(
                        "UPDATE OR IGNORE favorite_places SET slot = ? WHERE slot = ?",
                        arrayOf(new, old),
                    )
                    db.execSQL("DELETE FROM favorite_places WHERE slot = ?", arrayOf(old))
                }
            }
        }

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "recon_dash.db",
                ).addMigrations(MIGRATION_4_5)
                .fallbackToDestructiveMigration()
                .build().also { instance = it }
            }
    }
}
