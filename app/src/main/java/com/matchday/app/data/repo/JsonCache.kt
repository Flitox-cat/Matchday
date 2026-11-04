package com.matchday.app.data.repo

import com.matchday.app.data.local.CacheDao
import com.matchday.app.data.local.CacheEntity
import com.matchday.app.data.remote.Network
import kotlinx.serialization.KSerializer

/**
 * Stores API responses as JSON in Room with a time-to-live, so screens open instantly
 * and free-tier request limits are respected. If a refresh fails, stale data is served.
 */
class JsonCache(private val dao: CacheDao) {

    suspend fun <T> get(
        key: String,
        ttlMs: Long,
        serializer: KSerializer<T>,
        fetch: suspend () -> T,
    ): T {
        val now = System.currentTimeMillis()
        val hit = dao.get(key)
        if (hit != null && now - hit.fetchedAt < ttlMs) {
            val decoded = decode(hit.body, serializer)
            if (decoded != null) return decoded
        }
        return try {
            val value = fetch()
            dao.put(CacheEntity(key, Network.json.encodeToString(serializer, value), now))
            value
        } catch (e: Exception) {
            val stale = hit?.let { decode(it.body, serializer) }
            stale ?: throw e
        }
    }

    data class Timed<T>(val value: T, val fetchedAt: Long)

    /** Like [get] but also returns when the data was actually downloaded (for "updated" labels). */
    suspend fun <T> getTimed(
        key: String,
        ttlMs: Long,
        serializer: KSerializer<T>,
        fetch: suspend () -> T,
    ): Timed<T> {
        val value = get(key, ttlMs, serializer, fetch)
        val at = dao.get(key)?.fetchedAt ?: System.currentTimeMillis()
        return Timed(value, at)
    }

    suspend fun <T> peek(key: String, serializer: KSerializer<T>): T? =
        dao.get(key)?.let { decode(it.body, serializer) }

    suspend fun <T> put(key: String, serializer: KSerializer<T>, value: T) {
        dao.put(CacheEntity(key, Network.json.encodeToString(serializer, value), System.currentTimeMillis()))
    }

    suspend fun ageMs(key: String): Long? = dao.get(key)?.let { System.currentTimeMillis() - it.fetchedAt }

    suspend fun touch(key: String) = dao.put(CacheEntity(key, "{}", System.currentTimeMillis()))

    private fun <T> decode(body: String, serializer: KSerializer<T>): T? =
        try { Network.json.decodeFromString(serializer, body) } catch (_: Exception) { null }
}
