package com.mukimov.salom

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalChatDatabaseTest {
    private var database: LocalChatDatabase? = null

    @After
    fun closeDatabase() {
        database?.close()
        database = null
    }

    @Test
    fun syncQueueAndPerChatHistoryPersistInRoom() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, LocalChatDatabase::class.java).build()
        val dao = database!!.chatDao()
        val peer = "peer-a"
        val message = CachedMessage(peer, 10, "hello", true, "12:30", "sent", null, null, null, null, false, null)
        dao.applySync(peer, listOf(message), emptyList(), CachedChatState(peer, 10, 4, 0))
        val outgoing = CachedOutgoing.create("client-uuid", peer, "queued", 1234, null, 10, "Friend", "hello")
        dao.upsertQueuedMessage(outgoing)

        assertEquals(listOf(message), dao.messages(peer))
        assertEquals(10L, dao.chatState(peer)?.messageCursor)
        assertEquals(listOf(outgoing), dao.queuedMessages(peer))

        dao.applySync(peer, emptyList(), listOf(10), CachedChatState(peer, 10, 5, 10))
        dao.deleteQueuedMessage(outgoing.clientId)
        assertTrue(dao.messages(peer).isEmpty())
        assertNull(dao.chatState("another-peer"))
        assertTrue(dao.queuedMessages(peer).isEmpty())
    }

    @Test
    fun versionOneDatabaseMigratesQueueWithoutDroppingHistory() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fileName = "salom-room-migration-test.db"
        context.deleteDatabase(fileName)
        val file = context.getDatabasePath(fileName)
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            old.execSQL("CREATE TABLE cached_messages (peerId TEXT NOT NULL, remoteId INTEGER NOT NULL, text TEXT NOT NULL, mine INTEGER NOT NULL, time TEXT NOT NULL, receipt TEXT NOT NULL, attachmentJson TEXT, replyToId INTEGER, replyAuthor TEXT, replyPreview TEXT, edited INTEGER NOT NULL, locationId TEXT, PRIMARY KEY(peerId,remoteId))")
            old.execSQL("CREATE TABLE cached_conversations (peerId TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, username TEXT NOT NULL, avatarUrl TEXT NOT NULL, lastText TEXT NOT NULL, position INTEGER NOT NULL)")
            old.execSQL("CREATE TABLE cached_chat_state (peerId TEXT NOT NULL PRIMARY KEY, messageCursor INTEGER NOT NULL, eventCursor INTEGER NOT NULL, clearedThrough INTEGER NOT NULL)")
            old.execSQL("INSERT INTO cached_messages VALUES('peer-a',7,'keep this',1,'12:00','sent',NULL,NULL,NULL,NULL,0,NULL)")
            old.version = 1
        }

        database = Room.databaseBuilder(context, LocalChatDatabase::class.java, fileName)
            .addMigrations(LocalChatDatabase.MIGRATION_1_2)
            .build()

        val dao = database!!.chatDao()
        assertEquals("keep this", dao.messages("peer-a").single().text)
        assertTrue(dao.queuedMessages("peer-a").isEmpty())
        context.deleteDatabase(fileName)
    }
}
