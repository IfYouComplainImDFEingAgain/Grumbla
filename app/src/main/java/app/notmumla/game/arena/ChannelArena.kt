package app.notmumla.game.arena

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Who's playing, for a free-for-all game among everyone in our channel who opens it, over the
 * plugin-data relay (see docs/SIDE_CHANNELS.md). The game itself lives in the subclass; this owns
 * joining and leaving, the peer list, invites, and the message budget.
 *
 * Inbound messages count only from users in our channel, and are ignored entirely unless we've
 * opened the game: then a channel mate's [hello] posts one chat line ([onInvite], throttled) if
 * games are [enabled], and nothing is ever sent back.
 *
 * Every public entry point is `@Synchronized` on this object; subclasses synchronize on it too.
 */
abstract class ChannelArena<M : Any, P : ChannelArena.Peer>(
    private val send: (receivers: List<Int>, message: M) -> Unit,
    protected val self: () -> Int?,
    /** Users in our current channel, minus us: session → name. */
    private val channelPeers: () -> Map<Int, String>,
    private val enabled: () -> Boolean,
    /** A channel mate opened the game while we haven't. */
    private val onInvite: (name: String) -> Unit,
    protected val clock: () -> Long,
    private val hello: M,
    private val bye: M,
    private val decode: (ByteArray) -> M?,
) {
    open class Peer(val name: String) {
        var lastHeard = 0L
    }

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    protected val peers = LinkedHashMap<Int, P>()
    private val feed = ArrayDeque<Pair<String, Long>>()
    private val invitedAt = HashMap<Int, Long>()
    private var lastHello = 0L
    private var channelKey: Set<Int>? = null
    private var tokens = BURST
    private var tokensAt = 0L

    protected abstract fun newPeer(name: String): P

    /** The game opened: start fresh (spawn and so on). */
    protected abstract fun begin(now: Long)

    /** The game closed: drop all game state. */
    protected abstract fun end()

    /** Anything but [hello]/[bye] from a channel mate, while the game is open. */
    protected abstract fun onMessage(sender: Int, name: String, msg: M, now: Long)

    /** [peer] said hello, maybe again after rejoining. */
    protected open fun onHello(peer: P, now: Long) {}

    /** We moved to another channel; the peers there have been greeted. */
    protected open fun onChannelChanged() {}

    @Synchronized
    fun join() {
        if (_active.value) return
        clear()
        val now = clock()
        _active.value = true
        tokensAt = now
        begin(now)
        greet(now, channelPeers().keys.toList())
    }

    @Synchronized
    fun leave() {
        if (!_active.value) return
        if (peers.isNotEmpty()) broadcast(bye)
        clear()
    }

    /** Session ids die with the connection: drop everything without telling anyone. */
    @Synchronized
    fun reset() {
        clear()
        invitedAt.clear()
    }

    private fun clear() {
        _active.value = false
        peers.clear(); feed.clear()
        channelKey = null
        tokens = BURST
        end()
    }

    /** Our channel or its members changed: forget players who left it; greet a new channel. */
    @Synchronized
    fun onUsersPresent(myChannel: Int?) {
        if (!_active.value) return
        val members = channelPeers()
        peers.keys.retainAll(members.keys)
        val key = setOfNotNull(myChannel)
        if (channelKey != null && channelKey != key) {
            greet(clock(), members.keys.toList())
            onChannelChanged()
        }
        channelKey = key
    }

    @Synchronized
    fun onData(sender: Int, data: ByteArray) {
        val msg = decode(data) ?: return
        if (sender == self()) return
        val name = channelPeers()[sender] ?: return
        val now = clock()
        if (!_active.value) {
            if (msg == hello && enabled() &&
                now - (invitedAt[sender] ?: Long.MIN_VALUE / 2) >= INVITE_REPEAT_MS
            ) {
                invitedAt[sender] = now
                if (invitedAt.size > 64) invitedAt.entries.removeAll { now - it.value >= INVITE_REPEAT_MS }
                onInvite(name)
            }
            return
        }
        when (msg) {
            hello -> peer(sender, name)?.let { it.lastHeard = now; onHello(it, now) }
            bye -> peers.remove(sender)?.let { addFeed("${it.name} left", now) }
            else -> onMessage(sender, name, msg, now)
        }
    }

    protected fun peer(session: Int, name: String): P? =
        peers[session] ?: if (peers.size >= MAX_PEERS) null else newPeer(name).also {
            peers[session] = it
            addFeed("$name joined", clock())
        }

    /** Start of a frame: refill the budget and drop players gone quiet. */
    protected fun housekeep(now: Long) {
        tokens = (tokens + (now - tokensAt) * TOKENS_PER_SEC / 1000.0).coerceAtMost(BURST)
        tokensAt = now
        peers.entries.removeAll { (_, p) ->
            (now - p.lastHeard > PEER_TIMEOUT_MS).also { if (it) addFeed("${p.name} timed out", now) }
        }
        while (feed.isNotEmpty() && now - feed.first().second > FEED_MS) feed.removeFirst()
    }

    /** End of a frame: now and then, say hello to channel mates who aren't playing (yet). */
    protected fun greetStrangers(now: Long) {
        if (now - lastHello < HELLO_INTERVAL_MS) return
        val strangers = channelPeers().keys.filter { it !in peers }
        if (strangers.isNotEmpty() && take()) greet(now, strangers) else lastHello = now
    }

    private fun greet(now: Long, to: List<Int>) {
        lastHello = now
        if (to.isNotEmpty()) send(to, hello)
    }

    protected fun broadcast(m: M) = send(peers.keys.toList(), m)

    // Our own token bucket, a little under the server's (4/s, burst 15): the server drops excess
    // plugin messages silently, so staying below it is the only way to know ours arrive.
    protected fun take(force: Boolean = false): Boolean {
        if (tokens >= 1 || force) {
            tokens = (tokens - 1).coerceAtLeast(-BURST)
            return true
        }
        return false
    }

    protected fun addFeed(text: String, now: Long) {
        feed.addLast(text to now)
        while (feed.size > 4) feed.removeFirst()
    }

    protected fun feedLines(): List<String> = feed.map { it.first }

    companion object {
        const val HELLO_INTERVAL_MS = 15_000L
        const val PEER_TIMEOUT_MS = 6_000L
        const val INVITE_REPEAT_MS = 120_000L
        const val MAX_PEERS = 15
        const val FEED_MS = 5_000L
        private const val TOKENS_PER_SEC = 3.5
        private const val BURST = 8.0
    }
}
