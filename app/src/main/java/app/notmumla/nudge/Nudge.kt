package app.notmumla.nudge

/**
 * A nudge: one fixed message over the plugin-data relay (see docs/SIDE_CHANNELS.md). It carries no
 * payload worth parsing — anything but the exact body is dropped.
 */
object NudgeMessage {
    const val DATA_ID = "notmumla/nudge/1"
    private val BODY = "nudge".toByteArray(Charsets.US_ASCII)

    fun encode(): ByteArray = BODY.copyOf()
    fun isValid(data: ByteArray): Boolean = data.contentEquals(BODY)
}

/**
 * Throttles inbound nudges. Any user on the server can send one, and each buzzes the phone and
 * plays a sound, so a spammer must not be able to turn that into a siren: one per sender per
 * [perSenderMs], and one from anybody per [globalMs]. Throttled nudges are dropped, not queued.
 */
class NudgeLimiter(
    private val clock: () -> Long = System::currentTimeMillis,
    private val perSenderMs: Long = 10_000,
    private val globalMs: Long = 3_000,
) {
    private val lastBySender = HashMap<Int, Long>()
    private var lastAny = Long.MIN_VALUE / 2

    @Synchronized
    fun allow(sender: Int): Boolean {
        val now = clock()
        if (now - lastAny < globalMs) return false
        if (now - (lastBySender[sender] ?: Long.MIN_VALUE / 2) < perSenderMs) return false
        lastAny = now
        lastBySender[sender] = now
        // Session ids are per-connection and cheap to mint; don't let the map grow unbounded.
        if (lastBySender.size > 256) lastBySender.entries.removeAll { now - it.value >= perSenderMs }
        return true
    }

    @Synchronized
    fun reset() {
        lastBySender.clear()
        lastAny = Long.MIN_VALUE / 2
    }
}
