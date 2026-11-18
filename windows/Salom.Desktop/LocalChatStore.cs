using Microsoft.Data.Sqlite;
using System.IO;
using System.Linq;

namespace Salom.Desktop;

internal sealed class LocalChatStore
{
    private readonly string _connectionString;

    internal LocalChatStore()
    {
        var folder = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "SALOM");
        Directory.CreateDirectory(folder);
        _connectionString = new SqliteConnectionStringBuilder { DataSource = Path.Combine(folder, "chats.db"), Mode = SqliteOpenMode.ReadWriteCreate }.ToString();
        using var db = Open();
        using var command = db.CreateCommand();
        command.CommandText = "PRAGMA journal_mode=WAL; CREATE TABLE IF NOT EXISTS messages(peer_id TEXT NOT NULL, id INTEGER NOT NULL, text TEXT NOT NULL, mine INTEGER NOT NULL, created TEXT NOT NULL, PRIMARY KEY(peer_id,id)); CREATE INDEX IF NOT EXISTS messages_peer_created ON messages(peer_id,created,id);";
        command.ExecuteNonQuery();
    }

    internal List<ChatMessage> Load(string peerId)
    {
        using var db = Open();
        using var command = db.CreateCommand();
        command.CommandText = "SELECT id,text,mine,created FROM messages WHERE peer_id=$peer ORDER BY created,id";
        command.Parameters.AddWithValue("$peer", peerId);
        using var reader = command.ExecuteReader();
        var result = new List<ChatMessage>();
        while (reader.Read()) result.Add(new ChatMessage(reader.GetInt64(0), reader.GetString(1), reader.GetInt64(2) == 1, DateTime.Parse(reader.GetString(3)).ToLocalTime()));
        return result;
    }

    internal void Save(string peerId, IEnumerable<ChatMessage> messages)
    {
        using var db = Open();
        using var transaction = db.BeginTransaction();
        foreach (var message in messages)
        {
            using var command = db.CreateCommand();
            command.Transaction = transaction;
            command.CommandText = "INSERT INTO messages(peer_id,id,text,mine,created) VALUES($peer,$id,$text,$mine,$created) ON CONFLICT(peer_id,id) DO UPDATE SET text=excluded.text,mine=excluded.mine,created=excluded.created";
            command.Parameters.AddWithValue("$peer", peerId);
            command.Parameters.AddWithValue("$id", message.Id);
            command.Parameters.AddWithValue("$text", message.Text);
            command.Parameters.AddWithValue("$mine", message.IsMine ? 1 : 0);
            command.Parameters.AddWithValue("$created", message.Created.ToUniversalTime().ToString("O"));
            command.ExecuteNonQuery();
        }
        transaction.Commit();
    }

    private SqliteConnection Open() { var db = new SqliteConnection(_connectionString); db.Open(); return db; }
}
