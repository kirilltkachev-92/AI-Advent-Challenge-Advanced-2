import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil

/**
 * Rate limit — скользящее окно на каждый IP: не больше `perMin` запросов
 * за последние 60 секунд. Отказ несёт честный Retry-After — сколько секунд
 * ждать, пока самый старый запрос выйдет из окна.
 */
class RateLimiter(private val perMin: Int) {

    data class Decision(val allowed: Boolean, val retryAfterSec: Int, val remaining: Int)

    private val windowNanos = 60_000_000_000L
    private val windows = ConcurrentHashMap<String, ArrayDeque<Long>>()

    fun check(key: String): Decision {
        evictIdle()
        val window = windows.computeIfAbsent(key) { ArrayDeque() }
        synchronized(window) {
            val now = System.nanoTime()
            while (window.isNotEmpty() && now - window.first() > windowNanos) window.removeFirst()
            return if (window.size < perMin) {
                window.addLast(now)
                Decision(allowed = true, retryAfterSec = 0, remaining = perMin - window.size)
            } else {
                val waitSec = ceil((window.first() + windowNanos - now) / 1e9).toInt().coerceAtLeast(1)
                Decision(allowed = false, retryAfterSec = waitSec, remaining = 0)
            }
        }
    }

    /**
     * Убирает IP, чьё окно давно опустело, — иначе map растёт бесконечно.
     * Редкая гонка (удалили окно, которое другой поток только что получил)
     * безобидна: тот запрос просто посчитается в свежем окне.
     */
    private fun evictIdle() {
        val now = System.nanoTime()
        windows.entries.removeIf { (_, window) ->
            synchronized(window) { window.isEmpty() || now - window.last() > windowNanos }
        }
    }
}
