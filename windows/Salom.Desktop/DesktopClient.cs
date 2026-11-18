using System.Diagnostics;
using System.Net;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Web;
using System.Net.Http;
using System.Net.Http.Json;
using System.IO;
using System.Linq;
using System.Threading.Tasks;

namespace Salom.Desktop;

internal sealed class DesktopClient
{
    private readonly HttpClient _http = new() { Timeout = TimeSpan.FromSeconds(20) };
    private readonly Uri _apiBase;
    private string? _session;
    private string? _profileId;

    internal DesktopClient()
    {
        var raw = Environment.GetEnvironmentVariable("SALOM_API_BASE_URL") ?? "https://mukimov.site/salom/api/";
        _apiBase = new Uri(raw.EndsWith('/') ? raw : raw + "/", UriKind.Absolute);
        if (_apiBase.Scheme != Uri.UriSchemeHttps && !_apiBase.IsLoopback) throw new InvalidOperationException("API должен использовать HTTPS.");
        var saved = SessionVault.Read();
        _session = saved.Token;
        _profileId = saved.ProfileId;
    }

    internal bool HasSession => !string.IsNullOrWhiteSpace(_session);
    internal async Task SignInAsync(string name, string username)
    {
        var clientId = Environment.GetEnvironmentVariable("SALOM_GOOGLE_CLIENT_ID");
        var clientSecret = Environment.GetEnvironmentVariable("SALOM_GOOGLE_CLIENT_SECRET");
        var firebaseKey = Environment.GetEnvironmentVariable("SALOM_FIREBASE_WEB_API_KEY");
        if (string.IsNullOrWhiteSpace(clientId) || string.IsNullOrWhiteSpace(clientSecret) || string.IsNullOrWhiteSpace(firebaseKey))
            throw new InvalidOperationException("Настрой SALOM_GOOGLE_CLIENT_ID, SALOM_GOOGLE_CLIENT_SECRET и SALOM_FIREBASE_WEB_API_KEY. Для Firebase нужен OAuth Google Desktop-клиент.");

        var googleIdToken = await DesktopGoogleOAuth.GetIdTokenAsync(clientId, clientSecret);
        var identityUrl = $"https://identitytoolkit.googleapis.com/v1/accounts:signInWithIdp?key={Uri.EscapeDataString(firebaseKey)}";
        var postBody = "id_token=" + Uri.EscapeDataString(googleIdToken) + "&providerId=google.com";
        using var firebaseResponse = await _http.PostAsJsonAsync(identityUrl, new
        {
            postBody,
            requestUri = "http://localhost",
            returnIdpCredential = true,
            returnSecureToken = true
        });
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

    internal async Task ValidateSessionAsync()
    {
        if (!HasSession) return;
        try { _ = await GetAsync("auth/me"); }
        catch { SignOut(); throw; }
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
                profile.TryGetProperty("username", out var user) ? user.GetString() ?? "" : "", item.GetProperty("last_text").GetString() ?? ""));
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

    internal async Task<List<ChatMessage>> MessagesAsync(string peerId, long after = 0)
    {
        var result = new List<ChatMessage>();
        var cursor = after;
        while (true)
        {
            using var json = await GetAsync("messages?peer_id=" + Uri.EscapeDataString(peerId) + "&after=" + cursor);
            var root = json.RootElement;
            var batch = root.GetProperty("messages").EnumerateArray().Select(ParseMessage).ToList();
            result.AddRange(batch);
            if (batch.Count > 0) cursor = Math.Max(cursor, batch[^1].Id);
            if (!root.TryGetProperty("has_more", out var more) || !more.GetBoolean() || batch.Count == 0) break;
        }
        return result;
    }

    internal async Task<ChatMessage> SendAsync(ChatItem peer, string text)
    {
        using var json = await PostAsync("messages", new { recipient_id = peer.Id, client_id = Guid.NewGuid().ToString(), text });
        return ParseMessage(json.RootElement);
    }

    private ChatMessage ParseMessage(JsonElement m)
    {
        var seconds = m.GetProperty("created").GetDouble();
        return new ChatMessage(m.GetProperty("id").GetInt64(), m.GetProperty("text").GetString() ?? "",
            m.GetProperty("sender_id").GetString() == _profileId, DateTimeOffset.FromUnixTimeMilliseconds((long)(seconds * 1000)).LocalDateTime);
    }

    private Task<JsonDocument> GetAsync(string path) => SendAsync(HttpMethod.Get, path, null, true);
    private Task<JsonDocument> PostAsync(string path, object body, bool authenticated = true) => SendAsync(HttpMethod.Post, path, body, authenticated);
    private async Task<JsonDocument> SendAsync(HttpMethod method, string path, object? body, bool authenticated)
    {
        using var request = new HttpRequestMessage(method, new Uri(_apiBase, path));
        request.Headers.Accept.ParseAdd("application/json");
        if (authenticated)
        {
            if (string.IsNullOrWhiteSpace(_session)) throw new InvalidOperationException("Сначала войдите в аккаунт.");
            request.Headers.Authorization = new System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", _session);
        }
        if (body is not null) request.Content = JsonContent.Create(body);
        using var response = await _http.SendAsync(request);
        var content = await response.Content.ReadAsStringAsync();
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
            var detail = "Google/Firebase sign-in failed (" + (int)response.StatusCode + ")";
            try { detail = JsonDocument.Parse(content).RootElement.GetProperty("error").GetProperty("message").GetString() ?? detail; } catch { }
            throw new InvalidOperationException(detail);
        }
        return JsonDocument.Parse(content);
    }
}

internal sealed record ChatItem(string Id, string Name, string Username, string Preview)
{
    public string Initial => string.IsNullOrWhiteSpace(Name) ? "С" : Name[..1].ToUpperInvariant();
}

internal sealed class ChatMessage
{
    public long Id { get; }
    public string Text { get; }
    public bool IsMine { get; }
    public DateTime Created { get; }
    public string TimeLabel => Created.ToString("HH:mm");
    public string Alignment => IsMine ? "Right" : "Left";
    public string BubbleBrush => IsMine ? "#4D3D05" : "#202B38";
    internal ChatMessage(long id, string text, bool mine, DateTime created) { Id = id; Text = text; IsMine = mine; Created = created; }
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

internal static class DesktopGoogleOAuth
{
    internal static async Task<string> GetIdTokenAsync(string clientId, string clientSecret)
    {
        const string redirect = "http://127.0.0.1:51824/";
        var verifier = Base64Url(RandomNumberGenerator.GetBytes(32));
        var challenge = Base64Url(SHA256.HashData(Encoding.ASCII.GetBytes(verifier)));
        var state = Base64Url(RandomNumberGenerator.GetBytes(24));
        using var listener = new HttpListener();
        listener.Prefixes.Add(redirect);
        listener.Start();
        var query = HttpUtility.ParseQueryString(string.Empty);
        query["client_id"] = clientId; query["redirect_uri"] = redirect; query["response_type"] = "code";
        query["scope"] = "openid email profile"; query["code_challenge"] = challenge; query["code_challenge_method"] = "S256"; query["state"] = state;
        Process.Start(new ProcessStartInfo("https://accounts.google.com/o/oauth2/v2/auth?" + query) { UseShellExecute = true });
        using var timeout = new CancellationTokenSource(TimeSpan.FromMinutes(3));
        var context = await listener.GetContextAsync().WaitAsync(timeout.Token);
        var code = context.Request.QueryString["code"];
        if (context.Request.QueryString["state"] != state || string.IsNullOrWhiteSpace(code))
        {
            context.Response.StatusCode = 400;
            await ReplyAsync(context, "Вход не завершён. Можно закрыть окно и попробовать ещё раз.");
            throw new InvalidOperationException("Google-вход не завершён или state не совпал.");
        }
        await ReplyAsync(context, "Готово. Вернитесь в приложение Салом.");
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

    private static async Task ReplyAsync(HttpListenerContext context, string message)
    {
        var bytes = Encoding.UTF8.GetBytes("<!doctype html><meta charset=utf-8><title>Салом</title><body style='font:18px sans-serif;background:#151c24;color:#fff;padding:3em'>" + WebUtility.HtmlEncode(message) + "</body>");
        context.Response.ContentType = "text/html; charset=utf-8"; context.Response.ContentLength64 = bytes.Length;
        await context.Response.OutputStream.WriteAsync(bytes); context.Response.Close();
    }

    private static string Base64Url(byte[] bytes) => Convert.ToBase64String(bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_');
}
