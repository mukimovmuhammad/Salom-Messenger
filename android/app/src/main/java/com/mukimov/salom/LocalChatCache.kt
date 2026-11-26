package com.mukimov.salom

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.Index
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Entity(tableName = "cached_messages", primaryKeys = ["peerId", "remoteId"])
internal data class CachedMessage(
    val peerId: String,
    val remoteId: Long,
    val text: String,
    val mine: Boolean,
    val time: String,
    val receipt: String,
    val attachmentJson: String?,
    val replyToId: Long?,
    val replyAuthor: String?,
    val replyPreview: String?,
    val edited: Boolean,
    val locationId: String?,
    val myReaction: String? = null,
    val peerReaction: String? = null
) {
    fun toMessage() = Message(
        text = text,
        mine = mine,
        time = time,
        remoteId = remoteId,
        attachment = attachmentJson?.let { runCatching { Attachment.from(JSONObject(it)) }.getOrNull() },
        receipt = receipt,
        replyToId = replyToId,
        replyAuthor = replyAuthor,
        replyPreview = replyPreview,
        edited = edited,
        locationId = locationId, myReaction = myReaction, peerReaction = peerReaction
    )

    companion object {
        fun from(peerId: String, message: Message) = CachedMessage(
            peerId = peerId,
            remoteId = message.remoteId,
            text = message.text,
            mine = message.mine,
            time = message.time,
            receipt = message.receipt,
            attachmentJson = message.attachment?.json()?.toString(),
            replyToId = message.replyToId,
            replyAuthor = message.replyAuthor,
            replyPreview = message.replyPreview,
            edited = message.edited,
            locationId = message.locationId, myReaction = message.myReaction, peerReaction = message.peerReaction
        )
    }
}

@Entity(tableName = "cached_conversations")
internal data class CachedConversation(
    @PrimaryKey val peerId: String,
    val name: String,
    val username: String,
    val avatarUrl: String,
    val lastText: String,
    val position: Int
) {
    fun toPreview() = ConversationPreview(SalomAccount(peerId, name, username, "", true, avatarUrl), lastText)

    companion object {
        fun from(position: Int, preview: ConversationPreview) = CachedConversation(
            preview.account.id, preview.account.name, preview.account.username,
            preview.account.avatarUrl, preview.lastText, position
        )
    }
}

@Entity(tableName = "cached_chat_state")
internal data class CachedChatState(
    @PrimaryKey val peerId: String,
    val messageCursor: Long,
    val eventCursor: Long,
    val clearedThrough: Long
)

@Entity(tableName = "queued_messages", indices = [Index(value = ["peerId", "createdAt"])])
internal data class CachedOutgoing(
    @PrimaryKey val clientId: String,
    val peerId: String,
    val text: String,
    val createdAt: Long,
    val attachmentJson: String?,
    val replyToId: Long?,
    val replyAuthor: String?,
    val replyPreview: String?
) {
    fun toMessage(status: String = "queued") = Message(
        text = text, mine = true,
        time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(createdAt)),
        attachment = attachmentJson?.let { runCatching { Attachment.from(JSONObject(it)) }.getOrNull() },
        receipt = status, replyToId = replyToId, replyAuthor = replyAuthor,
        replyPreview = replyPreview
    )

    fun attachment() = attachmentJson?.let { runCatching { Attachment.from(JSONObject(it)) }.getOrNull() }

    companion object {
        fun create(clientId: String, peerId: String, text: String, createdAt: Long, attachment: Attachment?, replyToId: Long?, replyAuthor: String?, replyPreview: String?) =
            CachedOutgoing(clientId, peerId, text, createdAt, attachment?.json()?.toString(), replyToId, replyAuthor, replyPreview)
    }
}

@Dao
internal interface LocalChatDao {
    @Transaction
    suspend fun snapshot(peerId: String): Triple<List<CachedMessage>, CachedChatState?, List<CachedOutgoing>> =
        Triple(messages(peerId), chatState(peerId), queuedMessages(peerId))

    @Query("SELECT * FROM cached_messages WHERE peerId = :peerId ORDER BY remoteId")
    suspend fun messages(peerId: String): List<CachedMessage>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessages(messages: List<CachedMessage>)

    @Query("DELETE FROM cached_messages WHERE peerId = :peerId AND remoteId IN (:remoteIds)")
    suspend fun deleteMessages(peerId: String, remoteIds: List<Long>)

    @Query("DELETE FROM cached_messages WHERE peerId = :peerId AND remoteId <= :through")
    suspend fun deleteThrough(peerId: String, through: Long)

    @Query("SELECT * FROM cached_chat_state WHERE peerId = :peerId")
    suspend fun chatState(peerId: String): CachedChatState?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertChatState(state: CachedChatState)

    @Query("SELECT * FROM queued_messages WHERE peerId = :peerId ORDER BY createdAt")
    suspend fun queuedMessages(peerId: String): List<CachedOutgoing>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertQueuedMessage(message: CachedOutgoing)

    @Query("DELETE FROM queued_messages WHERE clientId = :clientId")
    suspend fun deleteQueuedMessage(clientId: String)

    @Query("SELECT * FROM queued_messages WHERE peerId = :peerId AND clientId IN (:clientIds)")
    suspend fun queuedById(peerId: String, clientIds: List<String>): List<CachedOutgoing>

    @Query("DELETE FROM queued_messages WHERE peerId = :peerId AND clientId IN (:clientIds)")
    suspend fun acknowledge(peerId: String, clientIds: List<String>)

    @Transaction
    suspend fun confirmOutgoing(peerId: String, message: CachedMessage, clientId: String) {
        upsertMessages(listOf(message))
        acknowledge(peerId, listOf(clientId))
    }

    @Query("DELETE FROM queued_messages WHERE peerId = :peerId")
    suspend fun clearQueuedMessages(peerId: String)

    @Transaction
    suspend fun applySync(peerId: String, messages: List<CachedMessage>, deletedIds: List<Long>, state: CachedChatState,
        confirmedIds: List<String>): List<CachedOutgoing> {
        val confirmed = confirmedIds.chunked(100).flatMap { ids ->
            queuedById(peerId, ids).also { acknowledge(peerId, ids) }
        }
        if (state.clearedThrough > 0) deleteThrough(peerId, state.clearedThrough)
        if (deletedIds.isNotEmpty()) deleteMessages(peerId, deletedIds)
        if (messages.isNotEmpty()) upsertMessages(messages)
        upsertChatState(state)
        return confirmed
    }

    @Query("SELECT * FROM cached_conversations ORDER BY position")
    suspend fun conversations(): List<CachedConversation>

    @Query("DELETE FROM cached_conversations")
    suspend fun clearConversations()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertConversations(conversations: List<CachedConversation>)

    @Transaction
    suspend fun replaceConversations(conversations: List<CachedConversation>) {
        clearConversations()
        if (conversations.isNotEmpty()) upsertConversations(conversations)
    }
}

@Database(entities = [CachedMessage::class, CachedConversation::class, CachedChatState::class, CachedOutgoing::class], version = 3, exportSchema = false)
internal abstract class LocalChatDatabase : RoomDatabase() {
    abstract fun chatDao(): LocalChatDao

    companion object {
        private val accounts = mutableMapOf<String, LocalChatDatabase>()

        fun get(context: Context, accountId: String): LocalChatDatabase = synchronized(this) {
            val owner = java.util.UUID.fromString(accountId).toString()
            accounts.getOrPut(owner) { Room.databaseBuilder(
                context.applicationContext,
                LocalChatDatabase::class.java,
                "salom-local-chat-$owner.db"
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build() }
        }

        internal val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE cached_messages ADD COLUMN myReaction TEXT")
                db.execSQL("ALTER TABLE cached_messages ADD COLUMN peerReaction TEXT")
            }
        }

        internal val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("CREATE TABLE IF NOT EXISTS queued_messages (clientId TEXT NOT NULL PRIMARY KEY, peerId TEXT NOT NULL, text TEXT NOT NULL, createdAt INTEGER NOT NULL, attachmentJson TEXT, replyToId INTEGER, replyAuthor TEXT, replyPreview TEXT)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_queued_messages_peerId_createdAt ON queued_messages(peerId, createdAt)")
            }
        }
    }
}

internal class LocalChatCache(context: Context, accountId: String) {
    private val dao = LocalChatDatabase.get(context, accountId).chatDao()

    suspend fun snapshot(peerId: String) = withContext(Dispatchers.IO) {
        val (messages, state, queue) = dao.snapshot(peerId)
        Triple(messages.map(CachedMessage::toMessage), state, queue)
    }

    suspend fun messages(peerId: String): List<Message> = withContext(Dispatchers.IO) {
        dao.messages(peerId).map(CachedMessage::toMessage)
    }

    suspend fun upsertMessages(peerId: String, messages: List<Message>) = withContext(Dispatchers.IO) {
        dao.upsertMessages(messages.filter { it.remoteId > 0 }.map { CachedMessage.from(peerId, it) })
    }

    suspend fun deleteMessages(peerId: String, remoteIds: List<Long>) = withContext(Dispatchers.IO) {
        if (remoteIds.isNotEmpty()) dao.deleteMessages(peerId, remoteIds)
    }

    suspend fun deleteThrough(peerId: String, remoteId: Long) = withContext(Dispatchers.IO) {
        dao.deleteThrough(peerId, remoteId)
    }

    suspend fun chatState(peerId: String): CachedChatState? = withContext(Dispatchers.IO) {
        dao.chatState(peerId)
    }

    suspend fun queuedMessages(peerId: String): List<CachedOutgoing> = withContext(Dispatchers.IO) {
        dao.queuedMessages(peerId)
    }

    suspend fun enqueue(message: CachedOutgoing) = withContext(Dispatchers.IO) {
        dao.upsertQueuedMessage(message)
    }

    suspend fun confirmOutgoing(peerId: String, message: Message, clientId: String) = withContext(Dispatchers.IO) {
        dao.confirmOutgoing(peerId, CachedMessage.from(peerId, message), clientId)
    }

    suspend fun hasQueued(peerId: String, clientId: String): Boolean = withContext(Dispatchers.IO) {
        dao.queuedById(peerId, listOf(clientId)).isNotEmpty()
    }

    suspend fun clearQueued(peerId: String) = withContext(Dispatchers.IO) {
        dao.clearQueuedMessages(peerId)
    }

    suspend fun applySync(peerId: String, messages: List<Message>, deletedIds: List<Long>, messageCursor: Long, eventCursor: Long,
        clearedThrough: Long, confirmedIds: List<String> = emptyList()) = withContext(Dispatchers.IO) {
        dao.applySync(
            peerId,
            messages.filter { it.remoteId > 0 }.map { CachedMessage.from(peerId, it) },
            deletedIds,
            CachedChatState(peerId, messageCursor, eventCursor, clearedThrough),
            confirmedIds
        )
    }

    suspend fun conversations(): List<ConversationPreview> = withContext(Dispatchers.IO) {
        dao.conversations().map(CachedConversation::toPreview)
    }

    suspend fun replaceConversations(conversations: List<ConversationPreview>) = withContext(Dispatchers.IO) {
        dao.replaceConversations(conversations.mapIndexed { position, preview -> CachedConversation.from(position, preview) })
    }
}
