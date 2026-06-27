package app.notmumla.protocol

import app.notmumla.protocol.identity.IdentityCertificate
import app.notmumla.protocol.model.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.Socket
import java.util.concurrent.CompletableFuture

/** Measures end-to-end text delivery latency between two clients through the real server. */
class TextLatencyTest {
    private val host = "127.0.0.1"
    private val port = 64738
    private fun reachable() = runCatching { Socket(host, port).close() }.isSuccess
    private suspend fun awaitConnected(c: MumbleClient) = withTimeoutOrNull(15_000) {
        while (c.state.value.connection != ConnectionState.CONNECTED) {
            if (c.state.value.connection == ConnectionState.FAILED) return@withTimeoutOrNull false
            delay(50)
        }; true
    } ?: false

    @Test
    fun measureTextLatency() = runBlocking {
        assumeTrue("no server", reachable())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val alice = MumbleClient(IdentityCertificate.generate("alice"), scope, osVersion = "test")
        val bob = MumbleClient(IdentityCertificate.generate("bob"), scope, osVersion = "test")
        alice.connect(ConnectConfig(host = host, port = port, username = "alice"))
        bob.connect(ConnectConfig(host = host, port = port, username = "bob"))
        awaitConnected(alice); awaitConnected(bob)
        delay(500)

        val channel = alice.state.value.self!!.channelId
        repeat(5) { i ->
            val recvd = CompletableFuture<Long>()
            val job = scope.launch {
                bob.events.collect {
                    if (it is MumbleClient.Event.Text && it.text.message.contains("ping$i")) {
                        recvd.complete(System.nanoTime()); return@collect
                    }
                }
            }
            delay(100)
            val t0 = System.nanoTime()
            alice.sendText(channel, "ping$i")
            val t1 = recvd.get()
            println("LATENCY ping$i = ${(t1 - t0) / 1_000_000.0} ms")
            job.cancel()
            delay(300)
        }
        alice.disconnect(); bob.disconnect()
    }
}
