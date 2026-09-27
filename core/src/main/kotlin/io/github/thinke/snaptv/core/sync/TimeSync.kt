package io.github.thinke.snaptv.core.sync

/**
 * Tracks the offset between our monotonic clock and the server's, from Time round trips.
 *
 * For one exchange, c2s = serverRecv - clientSent = offset + d and
 * s2c = clientRecv - serverSent = -offset + d, so offset = (c2s - s2c) / 2 with the
 * (assumed symmetric) network delay d cancelled out. Like snapclient, we keep the median
 * of recent samples to reject the occasional delayed packet.
 */
class TimeSync(private val window: Int = 100) {
    private val samples = LongArray(window)
    private var count = 0
    private var next = 0
    private var lastUpdateUs = Long.MIN_VALUE

    @Volatile
    var offsetUs: Long = 0
        private set

    @Volatile
    var sampleCount: Int = 0
        private set

    /** Round trip time of the latest exchange, for diagnostics. */
    @Volatile
    var lastRttUs: Long = 0
        private set

    @Synchronized
    fun add(c2sUs: Long, s2cUs: Long, nowUs: Long) {
        // After a long gap (suspend, network loss) old samples describe a different world.
        if (lastUpdateUs != Long.MIN_VALUE && nowUs - lastUpdateUs > 60_000_000L) reset()
        lastUpdateUs = nowUs
        lastRttUs = c2sUs + s2cUs
        samples[next] = (c2sUs - s2cUs) / 2
        next = (next + 1) % window
        if (count < window) count++
        val sorted = samples.copyOf(count).also { it.sort() }
        offsetUs = sorted[count / 2]
        sampleCount = count
    }

    @Synchronized
    fun reset() {
        count = 0
        next = 0
        sampleCount = 0
    }

    fun toServer(localUs: Long): Long = localUs + offsetUs
}
