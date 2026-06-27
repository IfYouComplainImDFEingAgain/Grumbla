package app.notmumla.protocol.net

import app.notmumla.protocol.proto.MessageType
import com.squareup.wire.Message
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/**
 * The Mumble TLS control channel.
 *
 * Frames are `[2-byte BE type][4-byte BE length][protobuf payload]` (reference
 * src/Connection.cpp). The client certificate from [keyStore] is offered during the TLS
 * handshake; the server is trusted via [TofuTrustManager].
 */
class ControlChannel(
    private val trust: TofuTrustManager,
) {
    private var socket: SSLSocket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null

    @Volatile private var lastActivity = System.currentTimeMillis()
    /** Milliseconds since the last send or receive — used to skip redundant keep-alive pings. */
    fun idleMs(): Long = System.currentTimeMillis() - lastActivity
    private fun touch() { lastActivity = System.currentTimeMillis() }

    /** A raw framed message read off the wire. */
    data class Frame(val typeId: Int, val payload: ByteArray)

    fun connect(
        host: String,
        port: Int,
        keyStore: KeyStore,
        keyStorePassword: CharArray,
        connectTimeoutMs: Int = 10_000,
    ) {
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore, keyStorePassword)

        val ctx = SSLContext.getInstance("TLS")
        ctx.init(kmf.keyManagers, arrayOf(trust), null)

        val s = ctx.socketFactory.createSocket() as SSLSocket
        s.connect(java.net.InetSocketAddress(host, port), connectTimeoutMs)
        s.startHandshake()
        s.tcpNoDelay = true   // disable Nagle so small messages go out immediately
        s.keepAlive = true    // keep the connection (and Wi-Fi radio) responsive when idle

        socket = s
        input = DataInputStream(s.inputStream.buffered())
        output = DataOutputStream(s.outputStream.buffered())
    }

    /** Encode and send a control message. Thread-safe with respect to other [send] calls. */
    @Synchronized
    fun send(message: Message<*, *>) {
        @Suppress("UNCHECKED_CAST")
        val adapter = message.adapter as com.squareup.wire.ProtoAdapter<Message<*, *>>
        val body = adapter.encode(message)
        val out = output ?: error("Not connected")
        out.writeShort(MessageType.idFor(message))
        out.writeInt(body.size)
        out.write(body)
        out.flush()
        touch()
    }

    /** Send a pre-encoded payload under a raw type id (used for the UDP-over-TCP audio tunnel). */
    @Synchronized
    fun sendRaw(typeId: Int, body: ByteArray) {
        val out = output ?: error("Not connected")
        out.writeShort(typeId)
        out.writeInt(body.size)
        out.write(body)
        out.flush()
        touch()
    }

    /** Blocking read of the next frame. Throws on EOF / socket error. */
    fun readFrame(): Frame {
        val inp = input ?: error("Not connected")
        val type = inp.readUnsignedShort()
        val length = inp.readInt()
        require(length in 0..0x7FFFFF) { "Frame length out of range: $length" }
        val payload = ByteArray(length)
        inp.readFully(payload)
        touch()
        return Frame(type, payload)
    }

    fun close() {
        runCatching { socket?.close() }
        socket = null
        input = null
        output = null
    }
}
