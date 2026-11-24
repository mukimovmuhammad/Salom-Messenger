using Microsoft.Data.Sqlite;
using System.IO;
using System.Linq;
using System.Text.Json;

namespace Salom.Desktop;

internal sealed class LocalChatStore
{
    private readonly string _connectionString;

    internal LocalChatStore(string? folder = null)
    {
        folder ??= Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "SALOM");
        Directory.CreateDirectory(folder);
        _connectionString = new SqliteConnectionStringBuilder { DataSource = Path.Combine(folder, "chats.db"), Mode = SqliteOpenMode.ReadWriteCreate }.ToString();
        using var db = Open();
        using var command = db.CreateCommand();
        command.CommandText = "PRAGMA journal_mode=WAL; CREATE TABLE IF NOT EXISTS messages(peer_id TEXT NOT NULL, id INTEGER NOT NULL, text TEXT NOT NULL, mine INTEGER NOT NULL, created TEXT NOT NULL, attachment_id TEXT, attachment_name TEXT, PRIMARY KEY(peer_id,id)); CREATE INDEX IF NOT EXISTS messages_peer_created ON messages(peer_id,created,id);";
        command.ExecuteNonQuery();
        using var columns = db.CreateCommand();
        columns.CommandText = "PRAGMA table_info(messages)";
        using var reader = columns.ExecuteReader();
        var names = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        while (reader.Read()) names.Add(reader.GetString(1));
        reader.Close();
        using var migration = db.CreateCommand();
        if (!names.Contains("attachment_id")) { migration.CommandText = "ALTER TABLE messages ADD COLUMN attachment_id TEXT"; migration.ExecuteNonQuery(); }
        if (!names.Contains("attachment_name")) { migration.CommandText = "ALTER TABLE messages ADD COLUMN attachment_name TEXT"; migration.ExecuteNonQuery(); }
        if (!names.Contains("attachment_mime")) { migration.CommandText = "ALTER TABLE messages ADD COLUMN attachment_mime TEXT"; migration.ExecuteNonQuery(); }
        if (!names.Contains("reply_text")) { migration.CommandText = "ALTER TABLE messages ADD COLUMN reply_text TEXT"; migration.ExecuteNonQuery(); }
        if (!names.Contains("voice_json")) { migration.CommandText = "ALTER TABLE messages ADD COLUMN voice_json TEXT"; migration.ExecuteNonQuery(); }
        if (!names.Contains("reply_mine")) { migration.CommandText = "ALTER TABLE messages ADD COLUMN reply_mine INTEGER NOT NULL DEFAULT 0"; migration.ExecuteNonQuery(); }
        if (!names.Contains("reply_id")) { migration.CommandText = "ALTER TABLE messages ADD COLUMN reply_id INTEGER"; migration.ExecuteNonQuery(); }
        if (!names.Contains("edited")) { migration.CommandText = "ALTER TABLE messages ADD COLUMN edited INTEGER NOT NULL DEFAULT 0"; migration.ExecuteNonQuery(); }
        if (!names.Contains("my_reaction")) { migration.CommandText = "ALTER TABLE messages ADD COLUMN my_reaction TEXT"; migration.ExecuteNonQuery(); }
        if (!names.Contains("peer_reaction")) { migration.CommandText = "ALTER TABLE messages ADD COLUMN peer_reaction TEXT"; migration.ExecuteNonQuery(); }
        migration.CommandText = "CREATE TABLE IF NOT EXISTS outbox(scope TEXT NOT NULL,id TEXT NOT NULL,peer TEXT NOT NULL,created TEXT NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(scope,id)); CREATE TABLE IF NOT EXISTS history_cursor(peer TEXT PRIMARY KEY,message_cursor INTEGER NOT NULL,event_cursor INTEGER NOT NULL,cleared_through INTEGER NOT NULL)";
        migration.ExecuteNonQuery();
    }

    internal void Enqueue(string scope, DesktopOutgoing item)
    {
        using var db = Open();
        using var command = db.CreateCommand();
        command.CommandText = "INSERT INTO outbox(scope,id,peer,created,payload) VALUES($scope,$id,$peer,$created,$payload)";
        command.Parameters.AddWithValue("$scope", scope);
        command.Parameters.AddWithValue("$id", item.Id.ToString());
        command.Parameters.AddWithValue("$peer", item.Peer.Id);
        command.Parameters.AddWithValue("$created", item.Created.ToUniversalTime().ToString("O"));
        command.Parameters.AddWithValue("$payload", JsonSerializer.Serialize(item));
        command.ExecuteNonQuery();
    }

    internal List<DesktopOutgoing> Outbox(string scope, string? peer = null)
    {
        using var db = Open();
        using var command = db.CreateCommand();
        command.CommandText = "SELECT payload FROM outbox WHERE scope=$scope AND ($peer IS NULL OR peer=$peer) ORDER BY created,id";
        command.Parameters.AddWithValue("$scope", scope);
        command.Parameters.AddWithValue("$peer", (object?)peer ?? DBNull.Value);
        using var rows = command.ExecuteReader();
        var result = new List<DesktopOutgoing>();
        while (rows.Read()) result.Add(JsonSerializer.Deserialize<DesktopOutgoing>(rows.GetString(0))!);
        return result;
    }

    internal void RemoveOutgoing(string scope, Guid id)
    {
        using var db = Open();
        using var command = db.CreateCommand();
        command.CommandText = "DELETE FROM outbox WHERE scope=$scope AND id=$id";
        command.Parameters.AddWithValue("$scope", scope);
        command.Parameters.AddWithValue("$id", id.ToString());
        command.ExecuteNonQuery();
    }

    private static string ScopedPeer(string scope, string peerId) => scope + "\n" + peerId;

    internal (long Message, long Event, long Cleared) ReadCursor(string scope, string peerId)
    {
        using var db = Open();
        using var command = db.CreateCommand();
        command.CommandText = "SELECT message_cursor,event_cursor,cleared_through FROM history_cursor WHERE peer=$peer";
        command.Parameters.AddWithValue("$peer", ScopedPeer(scope, peerId));
        using var row = command.ExecuteReader();
        return row.Read() ? (row.GetInt64(0), row.GetInt64(1), row.GetInt64(2)) : default;
    }

    internal void SaveCursor(string scope, string peerId, long message, long events, long cleared)
    {
        using var db = Open();
        using var command = db.CreateCommand();
        command.CommandText = "INSERT INTO history_cursor VALUES($peer,$message,$events,$cleared) ON CONFLICT(peer) DO UPDATE SET message_cursor=excluded.message_cursor,event_cursor=excluded.event_cursor,cleared_through=excluded.cleared_through";
        command.Parameters.AddWithValue("$peer", ScopedPeer(scope, peerId));
        command.Parameters.AddWithValue("$message", message);
        command.Parameters.AddWithValue("$events", events);
        command.Parameters.AddWithValue("$cleared", cleared);
        command.ExecuteNonQuery();
    }

    internal List<ChatMessage> Load(string scope, string peerId)
    {
        using var db = Open();
        using var command = db.CreateCommand();
        command.CommandText = "SELECT id,text,mine,created,attachment_id,attachment_name,attachment_mime,reply_text,reply_mine,voice_json,reply_id,edited,my_reaction,peer_reaction FROM messages WHERE peer_id=$peer ORDER BY created,id";
        command.Parameters.AddWithValue("$peer", ScopedPeer(scope, peerId));
        using var reader = command.ExecuteReader();
        var result = new List<ChatMessage>();
        while (reader.Read()) result.Add(new ChatMessage(reader.GetInt64(0), reader.GetString(1), reader.GetInt64(2) == 1, DateTime.Parse(reader.GetString(3)).ToLocalTime(), reader.IsDBNull(4) ? null : reader.GetString(4), reader.IsDBNull(5) ? null : reader.GetString(5), reader.IsDBNull(6) ? null : reader.GetString(6), reader.IsDBNull(7) ? null : reader.GetString(7), reader.GetInt64(8) == 1, ReadVoice(reader.IsDBNull(9) ? null : reader.GetString(9)),
            replyId: reader.IsDBNull(10) ? null : reader.GetInt64(10), edited: reader.GetInt64(11) == 1,
            myReaction: reader.IsDBNull(12) ? null : reader.GetString(12), peerReaction: reader.IsDBNull(13) ? null : reader.GetString(13)));
        return result;
    }

    internal void Save(string scope, string peerId, IEnumerable<ChatMessage> messages)
    {
        using var db = Open();
        using var transaction = db.BeginTransaction();
        foreach (var message in messages)
        {
            using var command = db.CreateCommand();
            command.Transaction = transaction;
            command.CommandText = "INSERT INTO messages(peer_id,id,text,mine,created,attachment_id,attachment_name,attachment_mime,reply_text,reply_mine,voice_json,reply_id,edited,my_reaction,peer_reaction) VALUES($peer,$id,$text,$mine,$created,$attachment,$attachmentName,$attachmentMime,$replyText,$replyMine,$voice,$replyId,$edited,$myReaction,$peerReaction) ON CONFLICT(peer_id,id) DO UPDATE SET text=excluded.text,mine=excluded.mine,created=excluded.created,attachment_id=excluded.attachment_id,attachment_name=excluded.attachment_name,attachment_mime=excluded.attachment_mime,reply_text=excluded.reply_text,reply_mine=excluded.reply_mine,voice_json=excluded.voice_json,reply_id=excluded.reply_id,edited=excluded.edited,my_reaction=excluded.my_reaction,peer_reaction=excluded.peer_reaction";
            command.Parameters.AddWithValue("$peer", ScopedPeer(scope, peerId));
            command.Parameters.AddWithValue("$id", message.Id);
            command.Parameters.AddWithValue("$text", message.Text);
            command.Parameters.AddWithValue("$mine", message.IsMine ? 1 : 0);
            command.Parameters.AddWithValue("$created", message.Created.ToUniversalTime().ToString("O"));
            command.Parameters.AddWithValue("$attachment", (object?)message.AttachmentId ?? DBNull.Value);
            command.Parameters.AddWithValue("$attachmentName", (object?)message.AttachmentName ?? DBNull.Value);
            command.Parameters.AddWithValue("$attachmentMime", (object?)message.AttachmentMime ?? DBNull.Value);
            command.Parameters.AddWithValue("$replyText", (object?)message.ReplyText ?? DBNull.Value);
            command.Parameters.AddWithValue("$replyMine", message.ReplyIsMine ? 1 : 0);
            command.Parameters.AddWithValue("$voice", message.Voice == null ? DBNull.Value : JsonSerializer.Serialize(message.Voice));
            command.Parameters.AddWithValue("$replyId", (object?)message.ReplyId ?? DBNull.Value);
            command.Parameters.AddWithValue("$edited", message.Edited ? 1 : 0);
            command.Parameters.AddWithValue("$myReaction", (object?)message.MyReaction ?? DBNull.Value);
            command.Parameters.AddWithValue("$peerReaction", (object?)message.PeerReaction ?? DBNull.Value);
            command.ExecuteNonQuery();
        }
        transaction.Commit();
    }

    internal void RemoveHistory(string scope, string peerId, long clearedThrough, IEnumerable<long> removed)
    {
        using var db = Open();
        using var transaction = db.BeginTransaction();
        using var command = db.CreateCommand();
        command.Transaction = transaction;
        command.Parameters.AddWithValue("$peer", ScopedPeer(scope, peerId));
        var id = command.Parameters.AddWithValue("$id", clearedThrough);
        if (clearedThrough > 0)
        {
            command.CommandText = "DELETE FROM messages WHERE peer_id=$peer AND id<=$id";
            command.ExecuteNonQuery();
        }
        command.CommandText = "DELETE FROM messages WHERE peer_id=$peer AND id=$id";
        foreach (var messageId in removed)
        {
            id.Value = messageId;
            command.ExecuteNonQuery();
        }
        transaction.Commit();
    }

    private static VoiceClip? ReadVoice(string? raw)
    {
        try { return raw == null ? null : JsonSerializer.Deserialize<VoiceClip>(raw); }
        catch (JsonException) { return null; }
    }

    private SqliteConnection Open() { var db = new SqliteConnection(_connectionString); db.Open(); return db; }
}

internal sealed record DesktopOutgoing(Guid Id, ChatItem Peer, string Text, long? ReplyId, DateTime Created,
    string? AudioPath = null, VoiceClip? Voice = null);
