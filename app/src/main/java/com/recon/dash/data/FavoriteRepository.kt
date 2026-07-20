package com.recon.dash.data

import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FavoriteRepository @Inject constructor(
    private val dao: FavoritePlaceDao,
) {
    fun observeAll(): Flow<List<FavoritePlace>> = dao.observeAll()

    suspend fun getBySlot(slot: FavoriteSlot): Result<FavoritePlace?> = runCatching {
        dao.getBySlot(slot)
    }

    suspend fun save(place: FavoritePlace): Result<Unit> = runCatching {
        dao.upsert(place)
    }

    suspend fun delete(slot: FavoriteSlot): Result<Unit> = runCatching {
        dao.deleteBySlot(slot)
    }

    suspend fun count(): Result<Int> = runCatching {
        dao.count()
    }
}
