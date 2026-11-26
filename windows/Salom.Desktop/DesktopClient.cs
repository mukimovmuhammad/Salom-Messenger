using System.Diagnostics;
using System.Net;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Web;
using System.Net.Http;
using System.Net.Http.Json;
using System.Net.WebSockets;
using System.IO;
using System.Linq;
using System.Threading.Tasks;
using System.Net.Http.Headers;
using System.Net.Sockets;

namespace Salom.Desktop;

internal sealed class DesktopClient
{
    private readonly HttpClient _http = new() { Timeout = TimeSpan.FromSeconds(20) };
    private readonly Uri _apiBase;
    private readonly DesktopAuthSettings _authSettings;
    private string? _session;
    private string? _profileId;

    internal DesktopClient()
    {
        _authSettings = DesktopAuthSettings.Load();
        var raw = Environment.GetEnvironmentVariable("SALOM_API_BASE_URL") ?? "https://mukimov.site/salom/api/";
        _apiBase = new Uri(raw.EndsWith('/') ? raw : raw + "/", UriKind.Absolute);
        if (_apiBase.Scheme != Uri.UriSchemeHttps && !_apiBase.IsLoopback) throw new InvalidOperationException("API должен использовать HTTPS.");
        var saved = SessionVault.Read();
        _session = saved.Token;
        _profileId = saved.ProfileId;
    }

    internal bool HasSession => !string.IsNullOrWhiteSpace(_session);
    internal string? NotificationOwner => HasSession ? _profileId : null;
    internal string? PhotoCacheScope => HasSession && !string.IsNullOrWhiteSpace(_profileId)
        ? _apiBase.AbsoluteUri + "\n" + _profileId : null;
    internal async Task SignInAsync(string name, string username)
    {
        var clientId = _authSettings.GoogleClientId;
        var clientSecret = _authSettings.GoogleClientSecret;
        var firebaseKey = _authSettings.FirebaseWebApiKey;
        if (string.IsNullOrWhiteSpace(clientId) || string.IsNullOrWhiteSpace(clientSecret) || string.IsNullOrWhiteSpace(firebaseKey))
            throw new InvalidOperationException("Не найдена конфигурация Google Desktop/Firebase. Переустанови Салом из официального Setup.");

        var googleIdToken = await DesktopGoogleOAuth.GetIdTokenAsync(clientId, clientSecret);
        using var bridgeResponse = await _http.PostAsJsonAsync(new Uri(_apiBase, "auth/desktop/custom-token"), new { google_id_token = googleIdToken });
        using var bridgeJson = await ReadJsonAsync(bridgeResponse);
        var customToken = bridgeJson.RootElement.GetProperty("firebase_custom_token").GetString();
        if (string.IsNullOrWhiteSpace(customToken)) throw new InvalidOperationException("Сервер не вернул Firebase token.");
        var identityUrl = $"https://identitytoolkit.googleapis.com/v1/accounts:signInWithCustomToken?key={Uri.EscapeDataString(firebaseKey)}";
        using var firebaseResponse = await _http.PostAsJsonAsync(identityUrl, new { token = customToken, returnSecureToken = true });
        using var firebaseJson = await ReadJsonAsync(firebaseResponse);
        var firebaseToken = firebaseJson.RootElement.GetProperty("idToken").GetString()!;

        var result = await PostAsync("auth/identity", new { firebase_token = firebaseToken, name, username }, authenticated: false);
        if (result.RootElement.TryGetProperty("needs_username", out var needs) && needs.GetBoolean())
            throw new InvalidOperationException("Для аккаунта нужно указать свободный username.");
        _session = result.RootElement.GetProperty("session_token").GetString();
        _profileId = result.RootElement.GetProperty("profile").GetProperty("id").GetString();
        if (string.IsNullOrWhiteSpace(_session)) throw new InvalidOperationException("Сервер не вернул сессию.");
        SessionVault.Write(_session, _profileId ?? "");
    }

    internal Task<JsonDocument> EmailAsync(string path, object body) => PostAsync("auth/email/" + path, body, authenticated: false);
    internal void AcceptEmail(JsonDocument result) {
        _session = result.RootElement.GetProperty("session_token").GetString();
        _profileId = result.RootElement.GetProperty("profile").GetProperty("id").GetString();
        SessionVault.Write(_session!, _profileId!);
    }
    internal async Task ValidateSessionAsync()
    {
        if (!HasSession) return;
        try { using var profile = await GetAsync("auth/me"); }
        catch (HttpRequestException error) when (error.StatusCode == HttpStatusCode.Unauthorized)
        {
            SignOut();
            throw;
        }
    }

    internal void SignOut() { _session = null; _profileId = null; SessionVault.Delete(); }

    internal async Task<List<ChatItem>> ConversationsAsync()
    {
        using var json = await GetAsync("conversations");
        var result = new List<ChatItem>();
        foreach (var item in json.RootElement.GetProperty("conversations").EnumerateArray())
        {
            var profile = item.GetProperty("profile");
            result.Add(new ChatItem(profile.GetProperty("id").GetString()!, profile.GetProperty("name").GetString()!,
                profile.TryGetProperty("username", out var user) ? user.GetString() ?? "" : "", item.GetProperty("last_text").GetString() ?? "",
                item.TryGetProperty("unread_count", out var unread) ? unread.GetInt32() : 0));
        }
        return result;
    }

    internal async Task<List<ChatItem>> SearchAsync(string query)
    {
        using var json = await GetAsync("search?q=" + Uri.EscapeDataString(query.Trim().TrimStart('@')));
        return json.RootElement.GetProperty("profiles").EnumerateArray().Select(p => new ChatItem(
            p.GetProperty("id").GetString()!, p.GetProperty("name").GetString()!,
            p.TryGetProperty("username", out var user) ? user.GetString() ?? "" : "", "Новый чат")).ToList();
    }

    internal async Task<ChatHistory> MessagesAsync(string peerId, long after, long eventsAfter)
    {
        var result = new List<ChatMessage>();
        var events = new List<ChatHistoryEvent>();
        var cursor = after;
        long clearedThrough = 0;
        while (true)
        {
            using var json = await GetAsync("messages?peer_id=" + Uri.EscapeDataString(peerId) + "&after=" + cursor + "&events_after=" + eventsAfter);
            var root = json.RootElement;
            var batch = root.GetProperty("messages").EnumerateArray().Select(ParseMessage).ToList();
            result.AddRange(batch);
            if (batch.Count > 0) cursor = Math.Max(cursor, batch[^1].Id);
            if (root.TryGetProperty("cleared_through", out var cleared)) clearedThrough = Math.Max(clearedThrough, cleared.GetInt64());
            var hasMoreEvents = false;
            if (root.TryGetProperty("events", out var changes))
            {
                foreach (var change in changes.EnumerateArray())
                {
                    var sequence = change.GetProperty("id").GetInt64();
                    eventsAfter = Math.Max(eventsAfter, sequence);
                    events.Add(new ChatHistoryEvent(change.GetProperty("message_id").GetInt64(), change.GetProperty("kind").GetString(),
                        change.TryGetProperty("message", out var edited) ? ParseMessage(edited) : null));
                }
                hasMoreEvents = changes.GetArrayLength() >= 300;
            }
            if (!hasMoreEvents && (batch.Count == 0 || !root.TryGetProperty("has_more", out var more) || !more.GetBoolean())) break;
        }
        return new ChatHistory(result, events, clearedThrough, eventsAfter);
    }

    internal async Task<ChatMessage> SendTextAsync(string peerId, string text, Guid clientId, long? replyId, CancellationToken token)
    {
        using var json = await SendAsync(HttpMethod.Post, "messages",
            new { recipient_id = peerId, client_id = clientId, text, reply_to_id = replyId }, true, token);
        return ParseMessage(json.RootElement);
    }

    internal async Task MarkNotificationsReadAsync(string peerId, CancellationToken token)
    {
        using var response = await SendAsync(HttpMethod.Get,
            $"messages?peer_id={Uri.EscapeDataString(peerId)}&after={long.MaxValue}&receipts_after={long.MaxValue}&events_after={long.MaxValue}",
            null, true, token);
    }

    internal async Task DeleteMessageAsync(long messageId, bool forEveryone)
    {
        using var result = await SendAsync(HttpMethod.Delete, $"messages/{messageId}?scope={(forEveryone ? "everyone" : "me")}", null, true);
    }

    internal async Task<ChatMessage> EditMessageAsync(long messageId, string text)
    {
        using var result = await SendAsync(HttpMethod.Put, $"messages/{messageId}", new { text }, true);
        return ParseMessage(result.RootElement);
    }

    internal async Task SetReactionAsync(long messageId, string? emoji)
    {
        using var result = await SendAsync(HttpMethod.Put, $"messages/{messageId}/reaction", new { emoji }, true);
    }

    internal async Task<long> ClearChatAsync(string peerId)
    {
        using var result = await SendAsync(HttpMethod.Delete, "messages?peer_id=" + Uri.EscapeDataString(peerId), null, true);
        return result.RootElement.GetProperty("cleared_through").GetInt64();
    }

    internal const long MaxAttachmentBytes = 25L * 1024 * 1024;

    internal async Task<ChatMessage> SendAttachmentAsync(ChatItem peer, string path, string text,
        Guid transferId, IProgress<int> progress, CancellationToken cancellationToken, long? replyId = null, VoiceClip? voice = null)
    {
        var scope = PhotoCacheScope ?? throw new InvalidOperationException("Сначала войди в аккаунт.");
        var attachmentId = await UploadAttachmentAsync(peer, path, transferId, progress, cancellationToken, scope, voice);
        if (scope != PhotoCacheScope) throw new OperationCanceledException();
        using var sent = await SendAsync(HttpMethod.Post, "messages",
            new { recipient_id = peer.Id, client_id = transferId.ToString(), text, attachment_id = attachmentId, reply_to_id = replyId },
            authenticated: true, cancellationToken: cancellationToken);
        return ParseMessage(sent.RootElement);
    }

    internal async Task<ChatMessage> SendPhotoAlbumAsync(ChatItem peer, IReadOnlyList<string> paths, string text,
        Guid messageId, IProgress<int> progress, CancellationToken token, long? replyId)
    {
        if (paths.Count is < 2 or > 12) throw new ArgumentOutOfRangeException(nameof(paths));
        var scope = PhotoCacheScope ?? throw new InvalidOperationException("Сначала войди в аккаунт.");
        var ids = new List<string>(paths.Count);
        foreach (var path in paths)
        {
            var index = ids.Count;
            var uploadId = new Guid(SHA256.HashData(System.Text.Encoding.UTF8.GetBytes($"album:{messageId}:{index}")).AsSpan(0, 16));
            ids.Add(await UploadAttachmentAsync(peer, path, uploadId,
                new Progress<int>(percent => progress.Report((index * 100 + percent) / paths.Count)), token, scope));
        }
        if (scope != PhotoCacheScope) throw new OperationCanceledException();
        using var sent = await SendAsync(HttpMethod.Post, "messages",
            new { recipient_id = peer.Id, client_id = messageId.ToString(), text, album_ids = ids, reply_to_id = replyId },
            authenticated: true, cancellationToken: token);
        return ParseMessage(sent.RootElement);
    }

    private async Task<string> UploadAttachmentAsync(ChatItem peer, string path, Guid transferId,
        IProgress<int> progress, CancellationToken cancellationToken, string scope, VoiceClip? voice = null)
    {
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        timeout.CancelAfter(TimeSpan.FromMinutes(3));
        var token = timeout.Token;
        await using var file = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read, 65536, useAsync: true);
        if (file.Length is <= 0 or > MaxAttachmentBytes) throw new InvalidOperationException("Файл должен быть размером от 1 байта до 25 МБ.");
        var digest = await SHA256.HashDataAsync(file, token);
        file.Position = 0;
        var uploadPath = "attachments?peer_id=" + Uri.EscapeDataString(peer.Id) + "&name=" +
            Uri.EscapeDataString(Path.GetFileName(path)) + "&upload_id=" + transferId;
        if (voice != null)
            uploadPath += "&kind=voice&duration_ms=" + voice.DurationMs.ToString(System.Globalization.CultureInfo.InvariantCulture)
                + "&waveform=" + Uri.EscapeDataString(string.Join(',', voice.Waveform));
        token.ThrowIfCancellationRequested();
        if (scope != PhotoCacheScope) throw new OperationCanceledException();
        using var request = new HttpRequestMessage(HttpMethod.Post, new Uri(_apiBase, uploadPath));
        request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", _session);
        request.Headers.Add("X-Content-SHA256", Convert.ToHexString(digest).ToLowerInvariant());
        request.Content = new UploadContent(file, progress);
        request.Content.Headers.ContentType = new MediaTypeHeaderValue(voice == null ? "application/octet-stream" : "audio/mp4");
        using var response = await _http.SendAsync(request, token);
        using var metadata = await ReadJsonAsync(response);
        return metadata.RootElement.GetProperty("id").GetString() ?? throw new InvalidDataException("Вложение недоступно.");
    }

    private sealed class UploadContent(FileStream file, IProgress<int> progress) : HttpContent
    {
        protected override bool TryComputeLength(out long length) { length = file.Length; return true; }
        protected override Task SerializeToStreamAsync(Stream stream, TransportContext? context) =>
            SerializeToStreamAsync(stream, context, CancellationToken.None);

        protected override async Task SerializeToStreamAsync(Stream stream, TransportContext? context, CancellationToken cancellationToken)
        {
            var buffer = new byte[65536];
            var reported = -1;
            while (await file.ReadAsync(buffer, cancellationToken) is var count && count != 0)
            {
                await stream.WriteAsync(buffer.AsMemory(0, count), cancellationToken);
                var percent = (int)(100 * file.Position / file.Length);
                if (percent != reported) progress.Report(reported = percent);
            }
        }
    }

    internal async Task<JsonDocument> CreateCallAsync(string peerId, bool video) => video
        ? await PostAsync("calls", new { peer_id = peerId, mode = "video" })
        : await PostAsync("calls", new { peer_id = peerId });
    internal async Task<JsonDocument> UpdateCallAsync(string callId, string action) => await PostAsync("calls/" + Uri.EscapeDataString(callId) + "/state", new { action });
    internal async Task<JsonDocument> CallConfigAsync() => await GetAsync("calls/config");
    internal async Task SignalCallAsync(string callId, string kind, JsonElement payload) =>
        _ = await PostAsync("calls/" + Uri.EscapeDataString(callId) + "/signal", new { kind, payload });

    internal Task DownloadPhotoAsync(string scope, string attachmentId, string destination, IProgress<int> progress,
        CancellationToken cancellationToken) => DownloadAttachmentAsync(attachmentId, destination, progress, cancellationToken, scope);

    internal async Task DownloadAttachmentAsync(string attachmentId, string destination, IProgress<int> progress,
        CancellationToken cancellationToken, string? expectedPhotoScope = null)
    {
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        timeout.CancelAfter(TimeSpan.FromMinutes(3));
        var token = timeout.Token;
        using var request = new HttpRequestMessage(HttpMethod.Get, new Uri(_apiBase, "attachments/" + Uri.EscapeDataString(attachmentId)));
        var session = _session;
        if (string.IsNullOrWhiteSpace(session)) throw new InvalidOperationException("Сначала войдите в аккаунт.");
        if (expectedPhotoScope != null && (expectedPhotoScope != PhotoCacheScope || session != _session))
            throw new InvalidOperationException("Аккаунт изменился. Откройте чат снова.");
        request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", session);
        using var response = await _http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, token);
        response.EnsureSuccessStatusCode();
        const long maxFileBytes = 25L * 1024 * 1024;
        var expectedSize = response.Content.Headers.ContentLength;
        if (expectedSize is <= 0 or > maxFileBytes) throw new IOException("Неверный размер файла.");
        var expectedHash = response.Headers.TryGetValues("X-Content-SHA256", out var values) ? values.FirstOrDefault() : null;
        var temporary = destination + "." + Guid.NewGuid().ToString("N") + ".part";
        try
        {
            long total = 0;
            var reported = -1;
            using var digest = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
            await using (var input = await response.Content.ReadAsStreamAsync(token))
            await using (var output = new FileStream(temporary, FileMode.CreateNew, FileAccess.Write, FileShare.None, 65536, useAsync: true))
            {
                var buffer = new byte[65536];
                while (await input.ReadAsync(buffer, token) is var count && count != 0)
                {
                    total += count;
                    if (total > maxFileBytes || total > expectedSize) throw new IOException("Неверный размер файла.");
                    digest.AppendData(buffer, 0, count);
                    await output.WriteAsync(buffer.AsMemory(0, count), token);
                    if (expectedSize is long size)
                    {
                        var percent = (int)(100 * total / size);
                        if (percent != reported) progress.Report(reported = percent);
                    }
                }
            }
            if (total == 0 || (expectedSize.HasValue && total != expectedSize))
                throw new IOException("Файл скачан не полностью.");
            if (expectedHash != null && !Convert.ToHexString(digest.GetHashAndReset()).Equals(expectedHash, StringComparison.OrdinalIgnoreCase))
                throw new IOException("Контрольная сумма файла не совпала.");
            token.ThrowIfCancellationRequested();
            File.Move(temporary, destination, overwrite: true);
        }
        finally { File.Delete(temporary); }
    }

    internal async Task<ClientWebSocket> ConnectRealtimeAsync(string peerId, CancellationToken cancellationToken)
    {
        if (string.IsNullOrWhiteSpace(_session)) throw new InvalidOperationException("Сначала войдите в аккаунт.");
        var builder = new UriBuilder(new Uri(_apiBase, "ws"))
        {
            Scheme = _apiBase.Scheme == Uri.UriSchemeHttps ? "wss" : "ws",
            Port = _apiBase.IsDefaultPort ? -1 : _apiBase.Port
        };
        var socket = new ClientWebSocket();
        try
        {
            socket.Options.SetRequestHeader("Authorization", "Bearer " + _session);
            await socket.ConnectAsync(builder.Uri, cancellationToken);
            var subscribe = JsonSerializer.SerializeToUtf8Bytes(new { type = "subscribe", peer_id = "*" });
            await socket.SendAsync(subscribe, WebSocketMessageType.Text, true, cancellationToken);
            return socket;
        }
        catch { socket.Dispose(); throw; }
    }

    internal async Task HeartbeatAsync(CancellationToken cancellationToken = default)
    {
        using var request = new HttpRequestMessage(HttpMethod.Post, new Uri(_apiBase, "presence"));
        if (string.IsNullOrWhiteSpace(_session)) throw new InvalidOperationException("Сначала войдите в аккаунт.");
        request.Headers.Authorization = new System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", _session);
        using var response = await _http.SendAsync(request, cancellationToken);
        response.EnsureSuccessStatusCode();
    }

    internal async Task<JsonDocument?> ReceiveRealtimeEventAsync(ClientWebSocket socket, CancellationToken cancellationToken)
    {
        using var payload = new MemoryStream();
        var buffer = new byte[8192];
        WebSocketReceiveResult result;
        do
        {
            result = await socket.ReceiveAsync(buffer, cancellationToken);
            if (result.MessageType == WebSocketMessageType.Close) return null;
            if (result.MessageType != WebSocketMessageType.Text) continue;
            payload.Write(buffer, 0, result.Count);
            if (payload.Length > 65536) throw new InvalidDataException("Событие слишком большое.");
        } while (!result.EndOfMessage);
        return JsonDocument.Parse(payload.ToArray());
    }

    internal ChatMessage ParseRealtimeMessage(JsonElement message) => ParseMessage(message);

    private ChatMessage ParseMessage(JsonElement m)
    {
        var seconds = m.GetProperty("created").GetDouble();
        var attachment = m.TryGetProperty("attachment", out var value) && value.ValueKind == JsonValueKind.Object ? value : default;
        var voice = attachment.ValueKind == JsonValueKind.Object && attachment.TryGetProperty("kind", out var kind) && kind.GetString() == "voice"
            ? new VoiceClip(
                attachment.TryGetProperty("duration_ms", out var duration) && duration.TryGetInt32(out var ms) ? Math.Clamp(ms, 0, 300000) : 0,
                attachment.TryGetProperty("waveform", out var waveform) && waveform.ValueKind == JsonValueKind.Array
                    ? waveform.EnumerateArray().Take(32).Select(peak => peak.TryGetInt32(out var p) ? Math.Clamp(p, 0, 100) : 0).ToArray()
                    : Array.Empty<int>())
            : null;
        var reply = m.TryGetProperty("reply_to", out var quote) && quote.ValueKind == JsonValueKind.Object ? quote : default;
        var mine = m.GetProperty("sender_id").GetString() == _profileId;
        var reactions = m.TryGetProperty("reactions", out var reactionValue) && reactionValue.ValueKind == JsonValueKind.Object ? reactionValue : default;
        string? Reaction(string party) => reactions.ValueKind == JsonValueKind.Object &&
            reactions.TryGetProperty(party, out var emoji) && emoji.ValueKind == JsonValueKind.String ? emoji.GetString() : null;
        return new ChatMessage(m.GetProperty("id").GetInt64(), m.GetProperty("text").GetString() ?? "",
            mine, DateTimeOffset.FromUnixTimeMilliseconds((long)(seconds * 1000)).LocalDateTime,
            attachment.ValueKind == JsonValueKind.Object ? attachment.GetProperty("id").GetString() : null,
            attachment.ValueKind == JsonValueKind.Object ? attachment.GetProperty("name").GetString() : null,
            attachment.ValueKind == JsonValueKind.Object && attachment.TryGetProperty("mime", out var mime) ? mime.GetString() : null,
            reply.ValueKind == JsonValueKind.Object ? reply.GetProperty("text").GetString() : null,
            reply.ValueKind == JsonValueKind.Object && reply.GetProperty("sender_id").GetString() == _profileId,
            voice, m.TryGetProperty("client_id", out var clientId) && clientId.ValueKind == JsonValueKind.String &&
                clientId.TryGetGuid(out var guid) ? guid : null,
            reply.ValueKind == JsonValueKind.Object && reply.TryGetProperty("id", out var replyId) ? replyId.GetInt64() : null,
            m.TryGetProperty("edited_at", out var edited) && edited.ValueKind == JsonValueKind.Number,
            Reaction(mine ? "sender" : "recipient"), Reaction(mine ? "recipient" : "sender"),
            m.TryGetProperty("album", out var album) && album.ValueKind == JsonValueKind.Array
                ? album.EnumerateArray().Take(12).Select(photo => new AlbumPhoto(
                    photo.GetProperty("id").GetString()!, photo.GetProperty("name").GetString()!,
                    photo.GetProperty("mime").GetString()!)).ToArray() : null);
    }

    private Task<JsonDocument> GetAsync(string path) => SendAsync(HttpMethod.Get, path, null, true);
    private Task<JsonDocument> PostAsync(string path, object body, bool authenticated = true) => SendAsync(HttpMethod.Post, path, body, authenticated);
    private async Task<JsonDocument> SendAsync(HttpMethod method, string path, object? body, bool authenticated, CancellationToken cancellationToken = default)
    {
        using var request = new HttpRequestMessage(method, new Uri(_apiBase, path));
        request.Headers.Accept.ParseAdd("application/json");
        request.Headers.Add("X-Salom-Device", "Windows");
        if (authenticated)
        {
            if (string.IsNullOrWhiteSpace(_session)) throw new InvalidOperationException("Сначала войдите в аккаунт.");
            request.Headers.Authorization = new System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", _session);
        }
        if (body is not null) request.Content = JsonContent.Create(body);
        using var response = await _http.SendAsync(request, cancellationToken);
        var content = await response.Content.ReadAsStringAsync(cancellationToken);
        if (!response.IsSuccessStatusCode)
        {
            var detail = "Ошибка сервера: " + (int)response.StatusCode;
            try { detail = JsonDocument.Parse(content).RootElement.GetProperty("detail").GetString() ?? detail; } catch { }
            throw new HttpRequestException(detail, null, response.StatusCode);
        }
        return JsonDocument.Parse(content);
    }

    private static async Task<JsonDocument> ReadJsonAsync(HttpResponseMessage response)
    {
        var content = await response.Content.ReadAsStringAsync();
        if (!response.IsSuccessStatusCode)
        {
            var detail = "Ошибка сервера (" + (int)response.StatusCode + ")";
            try { detail = JsonDocument.Parse(content).RootElement.GetProperty("error").GetProperty("message").GetString() ?? detail; } catch { }
            throw new InvalidOperationException(detail);
        }
        return JsonDocument.Parse(content);
    }
}

internal sealed record ChatItem(string Id, string Name, string Username, string Preview, int UnreadCount = 0)
{
    public string Initial => string.IsNullOrWhiteSpace(Name) ? "С" : Name[..1].ToUpperInvariant();
    public string UnreadLabel => UnreadCount > 99 ? "99+" : UnreadCount.ToString();
    public System.Windows.Visibility UnreadVisibility => UnreadCount > 0 ? System.Windows.Visibility.Visible : System.Windows.Visibility.Collapsed;
}

internal sealed record ChatHistory(List<ChatMessage> Messages, List<ChatHistoryEvent> Events, long ClearedThrough, long EventCursor);
internal sealed record ChatHistoryEvent(long MessageId, string? Kind, ChatMessage? Edited);
internal sealed record VoiceClip(int DurationMs, int[] Waveform);

internal sealed record AlbumPhoto(string Id, string Name, string Mime);

internal sealed class ChatMessage
{
    public long Id { get; }
    public string Text { get; }
    public bool IsMine { get; }
    public DateTime Created { get; }
    public string TimeLabel => Created.ToString("HH:mm") + (PendingStatus == null ? (Edited ? " · изменено" : "") : " · " + PendingStatus);
    public bool CanEdit => Id > 0 && IsMine && HasText && !HasAttachment;
    public bool Edited { get; }
    public long? ReplyId { get; }
    public string? MyReaction { get; }
    public string? PeerReaction { get; }
    public bool HasReactions => MyReaction != null || PeerReaction != null;
    public string ReactionLabel => string.Join("   ", new[] { MyReaction, PeerReaction }
        .OfType<string>().GroupBy(emoji => emoji).Select(group => $"{group.Key} {group.Count()}"));
    public string? PendingStatus { get; init; }
    public Guid? ClientId { get; }
    public string Alignment => IsMine ? "Right" : "Left";
    public string BubbleBrush => IsMine ? "#4D3D05" : "#202B38";
    public string? AttachmentId { get; }
    public string? AttachmentName { get; }
    public string? AttachmentMime { get; }
    public bool HasAttachment => !string.IsNullOrWhiteSpace(AttachmentId);
    public bool HasPhoto => HasAttachment && PhotoDecoder.IsPhoto(AttachmentMime, AttachmentName);
    public IReadOnlyList<AlbumPhoto> Album { get; }
    public bool HasAlbum => Album.Count > 1;
    public bool HasSinglePhoto => HasPhoto && !HasAlbum;
    public IEnumerable<ChatMessage> AlbumTiles => Album.Select(photo =>
        new ChatMessage(Id, "", IsMine, Created, photo.Id, photo.Name, photo.Mime));
    public bool HasVoice => HasAttachment && (Voice != null ||
        (AttachmentName?.StartsWith("voice_", StringComparison.OrdinalIgnoreCase) == true &&
         Path.GetExtension(AttachmentName).Equals(".m4a", StringComparison.OrdinalIgnoreCase)));
    public bool HasFile => HasAttachment && !HasVoice && !HasPhoto;
    public VoiceClip? Voice { get; }
    public bool HasText => !string.IsNullOrWhiteSpace(Text);
    public string Preview => HasText ? Text : HasPhoto ? "Фото" : AttachmentName ?? "Сообщение";
    public string? ReplyText { get; }
    public bool ReplyIsMine { get; }
    public string ReplyAuthor => ReplyIsMine ? "Вы" : "Собеседник";
    public bool HasReply => ReplyText != null;
    internal ChatMessage(long id, string text, bool mine, DateTime created, string? attachmentId = null, string? attachmentName = null,
        string? attachmentMime = null, string? replyText = null, bool replyIsMine = false, VoiceClip? voice = null,
        Guid? clientId = null, long? replyId = null, bool edited = false, string? myReaction = null, string? peerReaction = null, AlbumPhoto[]? album = null)
    {
        Id = id; Text = text; IsMine = mine; Created = created;
        AttachmentId = attachmentId; AttachmentName = attachmentName; AttachmentMime = attachmentMime;
        ReplyText = replyText; ReplyIsMine = replyIsMine; Voice = voice; ClientId = clientId;
        ReplyId = replyId; Edited = edited;
        MyReaction = myReaction; PeerReaction = peerReaction;
        Album = album ?? Array.Empty<AlbumPhoto>();
    }
}

internal static class SessionVault
{
    private static readonly string PathName = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "SALOM", "session.bin");
    internal static (string? Token, string? ProfileId) Read()
    {
        try
        {
            using var doc = JsonDocument.Parse(ProtectedData.Unprotect(File.ReadAllBytes(PathName), null, DataProtectionScope.CurrentUser));
            return (doc.RootElement.GetProperty("token").GetString(), doc.RootElement.GetProperty("profileId").GetString());
        }
        catch { return (null, null); }
    }
    internal static void Write(string token, string profileId)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(PathName)!);
        var payload = JsonSerializer.SerializeToUtf8Bytes(new { token, profileId });
        var protectedBytes = ProtectedData.Protect(payload, null, DataProtectionScope.CurrentUser);
        var temp = PathName + ".tmp";
        File.WriteAllBytes(temp, protectedBytes);
        File.Move(temp, PathName, true);
    }
    internal static void Delete() { try { File.Delete(PathName); } catch { } }
}

internal sealed record DesktopAuthSettings(string? GoogleClientId, string? GoogleClientSecret, string? FirebaseWebApiKey)
{
    internal static DesktopAuthSettings Load()
    {
        try
        {
            using var document = JsonDocument.Parse(File.ReadAllText(Path.Combine(AppContext.BaseDirectory, "salom.oauth.json")));
            var root = document.RootElement;
            return new DesktopAuthSettings(
                Environment.GetEnvironmentVariable("SALOM_GOOGLE_CLIENT_ID") ?? Value(root, "googleClientId"),
                Environment.GetEnvironmentVariable("SALOM_GOOGLE_CLIENT_SECRET") ?? Value(root, "googleClientSecret"),
                Environment.GetEnvironmentVariable("SALOM_FIREBASE_WEB_API_KEY") ?? Value(root, "firebaseWebApiKey"));
        }
        catch (FileNotFoundException)
        {
            return new DesktopAuthSettings(
                Environment.GetEnvironmentVariable("SALOM_GOOGLE_CLIENT_ID"),
                Environment.GetEnvironmentVariable("SALOM_GOOGLE_CLIENT_SECRET"),
                Environment.GetEnvironmentVariable("SALOM_FIREBASE_WEB_API_KEY"));
        }
        catch (DirectoryNotFoundException)
        {
            return new DesktopAuthSettings(null, null, null);
        }
        catch (JsonException)
        {
            return new DesktopAuthSettings(null, null, null);
        }
    }

    private static string? Value(JsonElement root, string name) =>
        root.TryGetProperty(name, out var value) && value.ValueKind == JsonValueKind.String ? value.GetString() : null;
}

internal static class DesktopGoogleOAuth
{
    internal static async Task<string> GetIdTokenAsync(string clientId, string clientSecret)
    {
        var verifier = Base64Url(RandomNumberGenerator.GetBytes(32));
        var challenge = Base64Url(SHA256.HashData(Encoding.ASCII.GetBytes(verifier)));
        var state = Base64Url(RandomNumberGenerator.GetBytes(24));
        using var listener = new TcpListener(IPAddress.Loopback, 0);
        listener.Start();
        var port = ((IPEndPoint)listener.LocalEndpoint).Port;
        var redirect = $"http://127.0.0.1:{port}/";
        var query = HttpUtility.ParseQueryString(string.Empty);
        query["client_id"] = clientId; query["redirect_uri"] = redirect; query["response_type"] = "code";
        query["scope"] = "openid email profile"; query["code_challenge"] = challenge; query["code_challenge_method"] = "S256"; query["state"] = state;
        Process.Start(new ProcessStartInfo("https://accounts.google.com/o/oauth2/v2/auth?" + query) { UseShellExecute = true });
        using var timeout = new CancellationTokenSource(TimeSpan.FromMinutes(3));
        using var callback = await listener.AcceptTcpClientAsync(timeout.Token);
        await using var stream = callback.GetStream();
        using var reader = new StreamReader(stream, Encoding.ASCII, detectEncodingFromByteOrderMarks: false, bufferSize: 1024, leaveOpen: true);
        var requestLine = await reader.ReadLineAsync(timeout.Token);
        var headerBytes = 0;
        string? header;
        string? host = null;
        while (!string.IsNullOrEmpty(header = await reader.ReadLineAsync(timeout.Token)))
        {
            headerBytes += header.Length;
            if (headerBytes > 8192) throw new InvalidDataException("Слишком большой ответ OAuth.");
            if (header.StartsWith("Host:", StringComparison.OrdinalIgnoreCase)) host = header[5..].Trim();
        }
        var requestParts = requestLine?.Split(' ', 3, StringSplitOptions.RemoveEmptyEntries);
        if (requestParts is not { Length: 3 } || requestParts[0] != "GET" ||
            !string.Equals(host, $"127.0.0.1:{port}", StringComparison.OrdinalIgnoreCase) ||
            !requestParts[1].StartsWith('/'))
        {
            await ReplyAsync(stream, 400, "Неверный ответ OAuth. Закрой страницу и повтори вход.");
            throw new InvalidOperationException("Получен неверный ответ Google OAuth.");
        }
        var callbackUri = new Uri(new Uri(redirect), requestParts[1]);
        var callbackQuery = HttpUtility.ParseQueryString(callbackUri.Query);
        var callbackError = callbackQuery["error"];
        var code = callbackQuery["code"];
        var returnedState = callbackQuery["state"];
        if (!string.IsNullOrWhiteSpace(callbackError))
        {
            await ReplyAsync(stream, 400, "Вход Google отменён. Вернись в Салом и попробуй ещё раз.");
            throw new InvalidOperationException("Вход Google отменён или отклонён.");
        }
        var expectedState = Encoding.UTF8.GetBytes(state);
        var actualState = Encoding.UTF8.GetBytes(returnedState ?? "");
        if (!CryptographicOperations.FixedTimeEquals(expectedState, actualState) || string.IsNullOrWhiteSpace(code))
        {
            await ReplyAsync(stream, 400, "Вход не завершён. Закрой страницу и попробуй ещё раз.");
            throw new InvalidOperationException("Google OAuth state не совпал или код авторизации отсутствует.");
        }
        await ReplyAsync(stream, 200, "Готово. Вернись в приложение Салом.");
        using var http = new HttpClient();
        using var response = await http.PostAsync("https://oauth2.googleapis.com/token", new FormUrlEncodedContent(new Dictionary<string, string>
        {
            ["client_id"] = clientId, ["client_secret"] = clientSecret, ["code"] = code,
            ["code_verifier"] = verifier, ["redirect_uri"] = redirect, ["grant_type"] = "authorization_code"
        }));
        var jsonText = await response.Content.ReadAsStringAsync();
        if (!response.IsSuccessStatusCode) throw new InvalidOperationException("Google не выдал токен входа.");
        using var token = JsonDocument.Parse(jsonText);
        return token.RootElement.GetProperty("id_token").GetString()!;
    }

    private static async Task ReplyAsync(NetworkStream stream, int status, string message)
    {
        var bytes = Encoding.UTF8.GetBytes("<!doctype html><meta charset=utf-8><title>Салом</title><body style='font:18px sans-serif;background:#151c24;color:#fff;padding:3em'>" + WebUtility.HtmlEncode(message) + "</body>");
        var header = Encoding.ASCII.GetBytes($"HTTP/1.1 {status} {(status == 200 ? "OK" : "Bad Request")}\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: {bytes.Length}\r\nConnection: close\r\nX-Content-Type-Options: nosniff\r\n\r\n");
        await stream.WriteAsync(header);
        await stream.WriteAsync(bytes);
        await stream.FlushAsync();
    }

    private static string Base64Url(byte[] bytes) => Convert.ToBase64String(bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_');
}
