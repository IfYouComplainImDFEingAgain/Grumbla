package app.notmumla.protocol.proto

import com.squareup.wire.Message
import com.squareup.wire.ProtoAdapter
import MumbleProto.ACL
import MumbleProto.Authenticate
import MumbleProto.BanList
import MumbleProto.ChannelRemove
import MumbleProto.ChannelState
import MumbleProto.CodecVersion
import MumbleProto.ContextAction
import MumbleProto.ContextActionModify
import MumbleProto.CryptSetup
import MumbleProto.PermissionDenied
import MumbleProto.PermissionQuery
import MumbleProto.Ping
import MumbleProto.QueryUsers
import MumbleProto.Reject
import MumbleProto.RequestBlob
import MumbleProto.ServerConfig
import MumbleProto.ServerSync
import MumbleProto.SuggestConfig
import MumbleProto.TextMessage
import MumbleProto.UDPTunnel
import MumbleProto.UserList
import MumbleProto.UserRemove
import MumbleProto.UserState
import MumbleProto.UserStats
import MumbleProto.Version
import MumbleProto.VoiceTarget

/**
 * Mumble TCP control-channel message types. The 2-byte big-endian type id prefixes every framed
 * message (see reference src/MumbleProtocol.h `MUMBLE_ALL_TCP_MESSAGES`).
 */
enum class MessageType(val id: Int, val adapter: ProtoAdapter<out Message<*, *>>) {
    VERSION(0, Version.ADAPTER),
    UDP_TUNNEL(1, UDPTunnel.ADAPTER),
    AUTHENTICATE(2, Authenticate.ADAPTER),
    PING(3, Ping.ADAPTER),
    REJECT(4, Reject.ADAPTER),
    SERVER_SYNC(5, ServerSync.ADAPTER),
    CHANNEL_REMOVE(6, ChannelRemove.ADAPTER),
    CHANNEL_STATE(7, ChannelState.ADAPTER),
    USER_REMOVE(8, UserRemove.ADAPTER),
    USER_STATE(9, UserState.ADAPTER),
    BAN_LIST(10, BanList.ADAPTER),
    TEXT_MESSAGE(11, TextMessage.ADAPTER),
    PERMISSION_DENIED(12, PermissionDenied.ADAPTER),
    ACL_MSG(13, ACL.ADAPTER),
    QUERY_USERS(14, QueryUsers.ADAPTER),
    CRYPT_SETUP(15, CryptSetup.ADAPTER),
    CONTEXT_ACTION_MODIFY(16, ContextActionModify.ADAPTER),
    CONTEXT_ACTION(17, ContextAction.ADAPTER),
    USER_LIST(18, UserList.ADAPTER),
    VOICE_TARGET(19, VoiceTarget.ADAPTER),
    PERMISSION_QUERY(20, PermissionQuery.ADAPTER),
    CODEC_VERSION(21, CodecVersion.ADAPTER),
    USER_STATS(22, UserStats.ADAPTER),
    REQUEST_BLOB(23, RequestBlob.ADAPTER),
    SERVER_CONFIG(24, ServerConfig.ADAPTER),
    SUGGEST_CONFIG(25, SuggestConfig.ADAPTER);

    companion object {
        private val byId = entries.associateBy { it.id }
        fun fromId(id: Int): MessageType? = byId[id]

        /** The type id for a concrete message instance (used when sending). */
        fun idFor(message: Message<*, *>): Int = when (message) {
            is Version -> VERSION.id
            is Authenticate -> AUTHENTICATE.id
            is Ping -> PING.id
            is UserState -> USER_STATE.id
            is ChannelState -> CHANNEL_STATE.id
            is TextMessage -> TEXT_MESSAGE.id
            is UDPTunnel -> UDP_TUNNEL.id
            is CryptSetup -> CRYPT_SETUP.id
            is VoiceTarget -> VOICE_TARGET.id
            is PermissionQuery -> PERMISSION_QUERY.id
            is RequestBlob -> REQUEST_BLOB.id
            else -> error("No outbound type id mapped for ${message::class.simpleName}")
        }
    }
}
