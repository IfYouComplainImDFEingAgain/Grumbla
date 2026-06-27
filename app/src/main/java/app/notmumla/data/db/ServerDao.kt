package app.notmumla.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ServerDao {
    @Query("SELECT * FROM servers ORDER BY lastConnectedAt DESC")
    fun observeAll(): Flow<List<ServerEntity>>

    @Query("SELECT * FROM servers WHERE id = :id")
    suspend fun get(id: Long): ServerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(server: ServerEntity): Long

    @Update
    suspend fun update(server: ServerEntity)

    @Delete
    suspend fun delete(server: ServerEntity)

    @Query("UPDATE servers SET pinnedSha256 = :sha WHERE id = :id")
    suspend fun setPin(id: Long, sha: String)

    @Query("UPDATE servers SET lastConnectedAt = :ts, lastChannelId = :channelId WHERE id = :id")
    suspend fun markConnected(id: Long, ts: Long, channelId: Int?)
}
