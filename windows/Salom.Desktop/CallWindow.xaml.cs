using System.IO;
using System.Text.Json;
using System.Windows;
using Microsoft.Web.WebView2.Core;

namespace Salom.Desktop;

public partial class CallWindow : Window
{
    private readonly DesktopClient _client;
    private readonly string _callId;
    private readonly bool _isCaller;
    private readonly bool _video;
    private bool _ended;
    private bool _webReady;
    private bool _callAccepted;
    private bool _mediaConnected;
    private readonly System.Windows.Threading.DispatcherTimer _leaseTimer = new() { Interval = TimeSpan.FromSeconds(15) };
    private readonly System.Windows.Threading.DispatcherTimer _connectionTimer = new() { Interval = TimeSpan.FromSeconds(30) };
    private readonly List<JsonElement> _pendingEvents = new();
    private readonly TaskCompletionSource<bool> _mediaReady = new(TaskCreationOptions.RunContinuationsAsynchronously);
    internal static CallWindow? Active { get; private set; }

    internal CallWindow(DesktopClient client, string callId, string peerName, bool isCaller, bool video)
    {
        InitializeComponent();
        WindowAppearance.UseDarkCaption(this);
        _video = video;
        _client = client; _callId = callId; _isCaller = isCaller;
        Title = (isCaller ? "Звоним: " : "Звонок: ") + peerName;
        Active = this;
        _leaseTimer.Tick += async (_, _) => await RenewLeaseAsync();
        _connectionTimer.Tick += async (_, _) => await FailConnectionAsync();
        Loaded += InitializeCallAsync;
        Closed += (_, _) =>
        {
            if (Active == this) Active = null;
            _leaseTimer.Stop();
            _connectionTimer.Stop();
            if (!_ended) _ = EndCallAsync();
            _ended = true;
            _mediaReady.TrySetCanceled();
            CallBrowser.Dispose();
        };
    }

    internal async Task ReceiveEventAsync(JsonElement payload)
    {
        if (_ended || !payload.TryGetProperty("call_id", out var id) || id.GetString() != _callId) return;
        var type = payload.TryGetProperty("type", out var t) ? t.GetString() : "";
        if (!_webReady)
        {
            if (_pendingEvents.Count < 128) _pendingEvents.Add(payload.Clone());
            return;
        }
        await ProcessEventAsync(payload, type);
    }

    private async Task ProcessEventAsync(JsonElement payload, string? type)
    {
        if (type == "call_signal" && payload.TryGetProperty("kind", out var kind) && payload.TryGetProperty("payload", out var data))
        {
            var json = JsonSerializer.Serialize(new { kind = kind.GetString(), payload = data });
            await Dispatcher.InvokeAsync(async () => await CallBrowser.ExecuteScriptAsync($"window.salomReceive({JsonSerializer.Serialize(json)})"));
        }
        else if (type == "call_state" && payload.TryGetProperty("state", out var accepted) && accepted.GetString() == "accepted" && _isCaller && !_callAccepted)
        {
            _callAccepted = true;
            _connectionTimer.Start();
            await CallBrowser.ExecuteScriptAsync("window.salomCreateOffer()");
        }
        else if (type == "call_state" && payload.TryGetProperty("state", out var state) && state.GetString() is "declined" or "cancelled" or "ended" or "missed")
        {
            await Dispatcher.InvokeAsync(() => { CallStatus.Text = state.GetString() switch { "declined" => "Звонок отклонён", "missed" => "Нет ответа", _ => "Звонок завершён" }; });
            await Task.Delay(1600);
            _ended = true;
            await Dispatcher.InvokeAsync(Close);
        }
    }

    private async void InitializeCallAsync(object sender, RoutedEventArgs e)
    {
        try
        {
            var folder = Path.Combine(AppContext.BaseDirectory, "CallWeb");
            if (!Directory.Exists(folder)) throw new DirectoryNotFoundException("Не найдены файлы звонка.");
            await CallBrowser.EnsureCoreWebView2Async();
            CallBrowser.CoreWebView2.SetVirtualHostNameToFolderMapping("salom-call.local", folder, CoreWebView2HostResourceAccessKind.DenyCors);
            CallBrowser.CoreWebView2.PermissionRequested += (_, args) =>
            {
                var allowed = args.PermissionKind == CoreWebView2PermissionKind.Microphone ||
                    (_video && args.PermissionKind == CoreWebView2PermissionKind.Camera);
                args.State = allowed && args.Uri.StartsWith("https://salom-call.local/", StringComparison.Ordinal)
                    ? CoreWebView2PermissionState.Allow : CoreWebView2PermissionState.Deny;
            };
            CallBrowser.CoreWebView2.WebMessageReceived += OnWebMessage;
            var navigation = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
            void Completed(object? _, CoreWebView2NavigationCompletedEventArgs args) { CallBrowser.NavigationCompleted -= Completed; navigation.TrySetResult(args.IsSuccess); }
            CallBrowser.NavigationCompleted += Completed;
            CallBrowser.Source = new Uri("https://salom-call.local/call.html");
            if (!await navigation.Task.WaitAsync(TimeSpan.FromSeconds(15))) throw new InvalidOperationException("Не удалось открыть интерфейс звонка.");
            using var config = await _client.CallConfigAsync();
            var ice = config.RootElement.GetProperty("ice_servers").GetRawText();
            var script = $"window.salomStart({JsonSerializer.Serialize(ice)},{(_isCaller ? "true" : "false")},{(_video ? "true" : "false")})";
            await CallBrowser.ExecuteScriptAsync(script);
            await _mediaReady.Task.WaitAsync(TimeSpan.FromSeconds(25));
            _webReady = true;
            foreach (var pending in _pendingEvents.ToArray())
                await ProcessEventAsync(pending, pending.TryGetProperty("type", out var eventType) ? eventType.GetString() : null);
            _pendingEvents.Clear();
            CallStatus.Text = _isCaller ? "Звоним…" : "Соединяемся…";
            if (!_isCaller)
            {
                using var accepted = await _client.UpdateCallAsync(_callId, "accept_leased");
                _callAccepted = accepted.RootElement.GetProperty("state").GetString() == "accepted";
                if (!_callAccepted) Close();
                else if (!_mediaConnected) _connectionTimer.Start();
            }
        }
        catch (Exception ex)
        {
            if (_ended) return;
            CallStatus.Text = "Не удалось запустить звонок: " + ex.Message;
            await EndCallAsync();
            if (!_ended) { _ended = true; Close(); }
        }
    }

    private async void OnWebMessage(object? sender, CoreWebView2WebMessageReceivedEventArgs e)
    {
        try
        {
            using var message = JsonDocument.Parse(e.WebMessageAsJson);
            var root = message.RootElement;
            var type = root.GetProperty("type").GetString();
            if (type == "ready") _mediaReady.TrySetResult(true);
            else if (type == "signal")
                await _client.SignalCallAsync(_callId, root.GetProperty("kind").GetString()!, root.GetProperty("payload"));
            else if (type == "state")
            {
                var state = root.GetProperty("value").GetString();
                System.Diagnostics.Trace.TraceInformation("SalomCall call={0} media={1}", _callId, state);
                _mediaConnected = state == "connected";
                if (_mediaConnected) _leaseTimer.Start(); else _leaseTimer.Stop();
                if (_mediaConnected) _connectionTimer.Stop();
                else if (_callAccepted) _connectionTimer.Start();
                if (state == "failed" && _callAccepted) { await FailConnectionAsync(); return; }
                await Dispatcher.InvokeAsync(() => CallStatus.Text = state switch
                {
                    "connected" => "Звонок идёт",
                    "connecting" => "Соединяемся…",
                    "failed" => "Не удалось соединиться. Проверьте сеть и TURN.",
                    "disconnected" => "Восстанавливаем связь…",
                    _ => "Соединяемся…"
                });
            }
            else if (type == "hangup")
            {
                _ended = true;
                try { await _client.UpdateCallAsync(_callId, "end"); } catch { }
                await Dispatcher.InvokeAsync(Close);
            }
            else if (type == "error")
            {
                var error = root.GetProperty("value").GetString() ?? "WebRTC error";
                _mediaReady.TrySetException(new InvalidOperationException(error));
                await Dispatcher.InvokeAsync(() => CallStatus.Text = "Ошибка звонка: " + error);
            }
        }
        catch (Exception ex) { await Dispatcher.InvokeAsync(() => CallStatus.Text = "Ошибка сигнализации: " + ex.Message); }
    }

    private async Task FailConnectionAsync()
    {
        _connectionTimer.Stop();
        if (_ended || _mediaConnected) return;
        _ended = true;
        CallStatus.Text = "Не удалось соединиться. Проверь интернет и попробуй снова.";
        _ = EndCallAsync();
        await Task.Delay(TimeSpan.FromSeconds(3));
        if (IsLoaded) Close();
    }

    private async Task EndCallAsync()
    {
        try { await _client.UpdateCallAsync(_callId, _callAccepted ? "end" : _isCaller ? "cancel" : "decline"); }
        catch { }
    }

    private async Task RenewLeaseAsync()
    {
        _leaseTimer.Stop();
        if (_ended || !_mediaConnected) return;
        try
        {
            using var response = await _client.UpdateCallAsync(_callId, "heartbeat");
            if (response.RootElement.GetProperty("state").GetString() != "accepted")
            {
                _ended = true;
                Close();
            }
        }
        catch (Exception) { /* Offline clients cannot renew; the server expires the call. */ }
        finally { if (!_ended && _mediaConnected) _leaseTimer.Start(); }
    }

    internal static async Task<CallWindow?> StartOutgoingAsync(DesktopClient client, string peerId, string peerName, Window owner, bool video)
    {
        using var response = await client.CreateCallAsync(peerId, video);
        var id = response.RootElement.GetProperty("call_id").GetString()!;
        var window = new CallWindow(client, id, peerName, true, video) { Owner = owner };
        window.Show();
        return window;
    }

    internal static async Task<CallWindow?> AnswerIncomingAsync(DesktopClient client, string callId, string peerName, Window owner, bool video)
    {
        var window = new CallWindow(client, callId, peerName, false, video) { Owner = owner };
        window.Show();
        return window;
    }
}
