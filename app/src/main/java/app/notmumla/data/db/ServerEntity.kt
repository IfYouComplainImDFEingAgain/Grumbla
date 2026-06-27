package app.notmumla.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A saved Mumble server the user can reconnect to. */
@Entity(tableName = "servers")
data class ServerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String,
    val host: String,
    val port: Int = 64738,
    val username: String,
    val password: String? = null,
    /** Pinned server certificate SHA-256 fingerprint (trust-on-first-use). */
    val pinnedSha256: String? = null,
    val lastChannelId: Int? = null,
    val lastConnectedAt: Long = 0,
)
