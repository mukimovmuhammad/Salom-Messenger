using System.Collections.ObjectModel;
using System.IO;
using System.Windows;
using System.Windows.Input;
using System.Windows.Threading;
using System.Net.WebSockets;
using System.Text.Json;
using Microsoft.Win32;
using Microsoft.VisualBasic;

namespace Salom.Desktop;

public partial class MainWindow : Window
{
    private readonly DesktopClient _client = new();
    internal PhotoCache Photos { get; }
    internal string? PhotoCacheScope => _client.PhotoCacheScope;
    private VoiceBubble? _activeVoice;
    internal void ActivateVoice(VoiceBubble voice)
    {
        if (_activeVoice != voice) _activeVoice?.Stop();
        _activeVoice = voice;
    }
    internal Task DownloadVoiceAsync(string scope, string id, string path, IProgress<int> progress, CancellationToken token) =>
        _client.DownloadPhotoAsync(scope, id, path, progress, token);
    private readonly LocalChatStore _store = new();
    private readonly ObservableCollection<ChatItem> _chats = new();
    private readonly ObservableCollection<ChatMessage> _messages = new();
    private readonly System.Windows.Forms.NotifyIcon _trayIcon;
    private ChatItem? _activeChat;
    private bool _syncInProgress;
    private bool _syncAgain;
    private long _historyRevision;
    private long _clearedThrough;
    private long _eventCursor;
    private long _messageCursor;
    private readonly HashSet<long> _removedMessages = new();
    private ChatMessage? _replyTo;
    private ChatMessage? _editingMessage;
    private bool _savingEdit;
    private bool _historyAction;
    private bool _searchPlaceholder = true;
    private bool _peerOnline;
    private string? _pendingCallInvite;
    private IncomingCallPrompt? _incomingPrompt;
    private readonly DesktopPreferences _preferences = DesktopPreferences.Load();
    private bool _exitRequested;
    private readonly HashSet<string> _seenInvites = new();
    private readonly Queue<string> _inviteOrder = new();
    private readonly RecentNotificationIds _notifiedMessages = new();
    private string? _notificationPeer;
    private Guid? _pendingNotificationPeer;
    private CancellationTokenSource? _realtimeCancellation;
    private CancellationTokenSource? _transfer;
    private bool _drainingOutbox;
    private readonly HashSet<Guid> _failedOutgoing = new();
    private DesktopVoiceRecorder? _recorder;
    private string? _recordingScope;
    private ChatItem? _recordingPeer;
    private ChatMessage? _recordingReply;
    private System.Windows.Point _micOrigin;
    private bool _finishingVoice;
    private readonly System.Windows.Threading.DispatcherTimer _voiceTimer = new() { Interval = TimeSpan.FromMilliseconds(100) };
    private static void TraceOutgoing(Guid id, string stage, long elapsed) =>
        System.Diagnostics.Trace.TraceInformation("SalomMessage id={0} stage={1} elapsed_ms={2}", id, stage, elapsed);
    private PendingUpload? _pendingUpload;
    private sealed record PendingUpload(ChatItem Peer, string Path, string Text, Guid Id, ChatMessage? Reply);

    public MainWindow()
    {
        Photos = new PhotoCache(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "SALOM", "PhotoCache"), _client.DownloadPhotoAsync);
        InitializeComponent();
        WindowAppearance.UseDarkCaption(this);
        _trayIcon = new System.Windows.Forms.NotifyIcon
        {
            Icon = LoadTrayIcon(),
            Text = "Салом",
            Visible = true,
            ContextMenuStrip = new System.Windows.Forms.ContextMenuStrip()
        };
        _trayIcon.ContextMenuStrip.Items.Add("Открыть Салом", null, (_, _) => Dispatcher.Invoke(RestoreFromTray));
        _trayIcon.DoubleClick += (_, _) => Dispatcher.Invoke(RestoreFromTray);
        var closeToTray = new System.Windows.Forms.ToolStripMenuItem("Оставлять Салом в трее") { CheckOnClick = true, Checked = _preferences.CloseToTray };
        closeToTray.CheckedChanged += (_, _) =>
        {
            _preferences.CloseToTray = closeToTray.Checked;
            if (!_preferences.TrySave()) ShowToast("Не удалось сохранить настройку");
        };
        _trayIcon.ContextMenuStrip.Items.Add(closeToTray);
        var ringtone = new System.Windows.Forms.ToolStripMenuItem("Звук входящего звонка") { CheckOnClick = true, Checked = _preferences.IncomingRingtone };
        ringtone.CheckedChanged += (_, _) =>
        {
            _preferences.IncomingRingtone = ringtone.Checked;
            if (!_preferences.TrySave()) ShowToast("Не удалось сохранить настройку");
        };
        _trayIcon.ContextMenuStrip.Items.Add(ringtone);
        var privatePreview = new System.Windows.Forms.ToolStripMenuItem("Скрывать текст в уведомлениях")
            { CheckOnClick = true, Checked = _preferences.PrivateNotificationPreview };
        privatePreview.CheckedChanged += (_, _) =>
        {
            _preferences.PrivateNotificationPreview = privatePreview.Checked;
            if (!_preferences.TrySave()) ShowToast("Не удалось сохранить настройку");
        };
        _trayIcon.ContextMenuStrip.Items.Add(privatePreview);
        _trayIcon.ContextMenuStrip.Items.Add("Настройки уведомлений Windows", null, (_, _) =>
            System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo("ms-settings:notifications") { UseShellExecute = true }));
        _trayIcon.ContextMenuStrip.Items.Add("Выйти из SALOM", null, (_, _) => Dispatcher.Invoke(() => { _exitRequested = true; Close(); }));
        _trayIcon.BalloonTipClicked += (_, _) => Dispatcher.InvokeAsync(async () =>
        {
            if (_notificationPeer is { } peerId) await OpenNotificationChatAsync(peerId);
        });
        ChatsList.ItemsSource = _chats;
        MessagesList.ItemsSource = _messages;
        Loaded += Window_Loaded;
        Activated += (_, _) => { if (_client.HasSession) _ = SyncSelectedChatAsync(false); };
        Deactivated += async (_, _) => await FinishRecordingAsync(cancel: true);
        _voiceTimer.Tick += (_, _) =>
        {
            if (_recorder is not { } recording) return;
            if (recording.Elapsed.TotalMilliseconds >= DesktopVoiceRecorder.MaximumMs)
            {
                VoiceRecordingStatus.Text = "●  05:00   ·   Отпусти для отправки";
                _voiceTimer.Stop();
                return;
            }
            VoiceRecordingStatus.Text = $"●  {recording.Elapsed:mm\\:ss}   ·   ← Отмена";
        };
    }

    private static System.Drawing.Icon LoadTrayIcon()
    {
        var resource = System.Windows.Application.GetResourceStream(new Uri("pack://application:,,,/assets/salom.ico", UriKind.Absolute))
            ?? throw new InvalidOperationException("Иконка Салом не включена в приложение.");
        using var stream = resource.Stream;
        return new System.Drawing.Icon(stream);
    }

    private async void Window_Loaded(object sender, RoutedEventArgs e)
    {
        if (!_client.HasSession) return;
        LoginStatus.Text = "Проверяю сохранённый вход…";
        try { await _client.ValidateSessionAsync(); await ShowWorkspaceAsync(); }
        catch (Exception) when (_client.HasSession)
        {
            await ShowWorkspaceAsync();
            SetConnection("●  офлайн", false);
        }
        catch (Exception) { LoginStatus.Text = "Сессия истекла. Войди через Google или email."; }
    }

    private async void GoogleSignIn_Click(object sender, RoutedEventArgs e)
    {
        var username = Interaction.InputBox("Username Салом (4–24 латинские буквы, цифры или _):", "Вход в Салом");
        if (string.IsNullOrWhiteSpace(username)) return;
        var displayName = Interaction.InputBox("Как показывать твоё имя в Салом?", "Профиль Салом", username.Trim());
        if (string.IsNullOrWhiteSpace(displayName)) return;
        GoogleButton.IsEnabled = false;
        LoginStatus.Text = "Открылся безопасный вход Google…";
        try { await _client.SignInAsync(displayName.Trim(), username.Trim().TrimStart('@')); await ShowWorkspaceAsync(); }
        catch (Exception error) { LoginStatus.Text = error.Message; }
        finally { GoogleButton.IsEnabled = true; }
    }

    private async void EmailSignIn_Click(object sender, RoutedEventArgs e) {
        var email = Interaction.InputBox("Email для входа:", "Салом · Email").Trim();
        if (email.Length == 0) return;
        try {
            using var sent = await _client.EmailAsync("code", new { email });
            var code = Interaction.InputBox("6 цифр из письма. Код действует 5 минут.", "Подтверждение Email").Trim();
            if (code.Length == 0) return;
            using var verified = await _client.EmailAsync("verify", new { email, code });
            if (verified.RootElement.TryGetProperty("needs_username", out var needs) && needs.GetBoolean()) {
                var username = Interaction.InputBox("Выбери username:", "Новый аккаунт");
                var name = Interaction.InputBox("Твоё имя:", "Новый аккаунт", username);
                using var registered = await _client.EmailAsync("register", new { email, ticket = verified.RootElement.GetProperty("ticket").GetString(), username, name });
                _client.AcceptEmail(registered);
            } else _client.AcceptEmail(verified);
            await ShowWorkspaceAsync();
        } catch (Exception error) { LoginStatus.Text = error.Message; }
    }
    private async Task ShowWorkspaceAsync()
    {
        LoginPane.Visibility = Visibility.Collapsed; Workspace.Visibility = Visibility.Visible;
        LoginStatus.Text = ""; ConnectionState.Text = "●  подключаюсь…";
        ConnectionState.Foreground = System.Windows.Media.Brushes.Orange;
        await LoadConversationsAsync();
        _realtimeCancellation?.Cancel();
        _realtimeCancellation?.Dispose();
        _realtimeCancellation = new CancellationTokenSource();
        _ = RealtimeLoopAsync(_realtimeCancellation.Token);
        _ = RetryOutboxAsync(_realtimeCancellation.Token);
        if (_pendingNotificationPeer is { } peer)
        {
            _pendingNotificationPeer = null;
            await OpenNotificationChatAsync(peer.ToString());
        }
    }

    private async Task RetryOutboxAsync(CancellationToken token)
    {
        using var timer = new PeriodicTimer(TimeSpan.FromSeconds(8));
        try
        {
            do { await DrainOutboxAsync(); } while (await timer.WaitForNextTickAsync(token));
        }
        catch (OperationCanceledException) when (token.IsCancellationRequested) { }
    }

    private async Task LoadConversationsAsync()
    {
        if (PhotoCacheScope is not { } scope) return;
        try
        {
            var conversations = await _client.ConversationsAsync();
            if (PhotoCacheScope != scope) return;
            var selected = _activeChat;
            _chats.Clear(); foreach (var chat in conversations) _chats.Add(chat);
            if (selected is not null)
            {
                var current = _chats.FirstOrDefault(c => c.Id == selected.Id);
                if (current == null)
                {
                    current = selected with { Preview = "", UnreadCount = 0 };
                    _chats.Insert(0, current);
                }
                ChatsList.SelectedItem = current;
            }
            if (ChatsList.SelectedItem is null && _chats.Count > 0) ChatsList.SelectedIndex = 0;
        }
        catch (Exception error) { ShowToast("Не удалось загрузить список чатов: " + error.Message); }
    }

    private async void Search_Click(object sender, RoutedEventArgs e) => await SearchPeopleAsync();
    private async void Search_KeyDown(object sender, System.Windows.Input.KeyEventArgs e) { if (e.Key == Key.Enter) { e.Handled = true; await SearchPeopleAsync(); } }

    private async Task SearchPeopleAsync()
    {
        var query = SearchBox.Text.Trim().TrimStart('@');
        if (_searchPlaceholder || query.Length < 1) { ShowToast("Введи имя или начало @username"); return; }
        SearchButton.IsEnabled = false;
        try
        {
            var found = await _client.SearchAsync(query);
            if (found.Count == 0) { ShowToast("Ничего не найдено"); return; }
            foreach (var person in found) if (_chats.All(c => c.Id != person.Id)) _chats.Insert(0, person);
            ChatsList.SelectedItem = _chats.FirstOrDefault(chat => found.Any(person => person.Id == chat.Id));
        }
        catch (Exception error) { ShowToast(error.Message); }
        finally { SearchButton.IsEnabled = true; }
    }

    private void Search_GotFocus(object sender, RoutedEventArgs e)
    {
        if (!_searchPlaceholder) return;
        SearchBox.Clear(); SearchBox.Foreground = System.Windows.Media.Brushes.White; _searchPlaceholder = false;
    }

    private void Search_LostFocus(object sender, RoutedEventArgs e)
    {
        if (!string.IsNullOrWhiteSpace(SearchBox.Text)) return;
        SearchBox.Text = "Поиск по @username"; SearchBox.Foreground = System.Windows.Media.Brushes.Gray; _searchPlaceholder = true;
    }

    private async void ChatsList_SelectionChanged(object sender, System.Windows.Controls.SelectionChangedEventArgs e)
    {
        if (ChatsList.SelectedItem is not ChatItem chat || PhotoCacheScope is not { } scope) return;
        if (_activeChat?.Id == chat.Id) { _activeChat = chat; return; }
        _historyRevision++;
        (_messageCursor, _eventCursor, _clearedThrough) = _store.ReadCursor(scope, chat.Id);
        _removedMessages.Clear();
        if (_editingMessage != null) MessageBox.Clear();
        _editingMessage = null;
        SetReply(null);
        _peerOnline = false;
        _activeChat = chat; PeerName.Text = chat.Name; PeerUsername.Text = string.IsNullOrWhiteSpace(chat.Username) ? "" : "@" + chat.Username;
        _messages.Clear(); foreach (var message in _store.Load(scope, chat.Id)) _messages.Add(message);
        foreach (var item in _store.Outbox(scope, chat.Id)) ShowOutgoing(item, "В очереди");
        ScrollToLatest();
        await SyncSelectedChatAsync(true);
    }

    private async Task RealtimeLoopAsync(CancellationToken cancellationToken)
    {
        var retryDelay = TimeSpan.FromSeconds(1);
        while (!cancellationToken.IsCancellationRequested && _client.HasSession)
        {
            try
            {
                using var socket = await _client.ConnectRealtimeAsync("*", cancellationToken);
                await Dispatcher.InvokeAsync(() => SetConnection("●  онлайн", true));
                await LoadConversationsAsync();
                await SyncSelectedChatAsync(false);
                using var pingCancellation = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
                var pingTask = SendRealtimePingsAsync(socket, pingCancellation.Token);
                retryDelay = TimeSpan.FromSeconds(1);
                try
                {
                    while (!cancellationToken.IsCancellationRequested && socket.State == WebSocketState.Open)
                    {
                        using var eventDocument = await _client.ReceiveRealtimeEventAsync(socket, cancellationToken);
                        if (eventDocument is null) break;
                        await Dispatcher.InvokeAsync(() => HandleRealtimeEvent(eventDocument.RootElement));
                    }
                }
                finally
                {
                    pingCancellation.Cancel();
                    try { await pingTask; } catch (OperationCanceledException) { }
                }
            }
            catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested) { break; }
            catch
            {
                await Dispatcher.InvokeAsync(() => SetConnection("●  переподключение…", false));
                await SyncSelectedChatAsync(false);
                try { await Task.Delay(retryDelay, cancellationToken); }
                catch (OperationCanceledException) { break; }
                retryDelay = TimeSpan.FromSeconds(Math.Min(30, retryDelay.TotalSeconds * 2));
            }
        }
    }

    private async Task SendRealtimePingsAsync(ClientWebSocket socket, CancellationToken cancellationToken)
    {
        while (!cancellationToken.IsCancellationRequested && socket.State == WebSocketState.Open)
        {
            await Task.Delay(TimeSpan.FromSeconds(20), cancellationToken);
            if (socket.State != WebSocketState.Open) return;
            var ping = System.Text.Encoding.UTF8.GetBytes("{\"type\":\"ping\"}");
            await socket.SendAsync(ping, WebSocketMessageType.Text, true, cancellationToken);
            try { await _client.HeartbeatAsync(cancellationToken); } catch { }
        }
    }

    private void HandleRealtimeEvent(JsonElement payload)
    {
        if (!payload.TryGetProperty("type", out var typeValue)) return;
        var type = typeValue.GetString();
        var eventPeerId = payload.TryGetProperty("peer_id", out var peerValue) ? peerValue.GetString() : null;
        var activeChat = _activeChat;
        if (type == "presence" && activeChat is not null && activeChat.Id == eventPeerId && payload.TryGetProperty("online", out var online))
        {
            _peerOnline = online.GetBoolean();
            PeerUsername.Text = (string.IsNullOrWhiteSpace(activeChat.Username) ? "" : "@" + activeChat.Username + "  ·  ") + (_peerOnline ? "в сети" : "не в сети");
            return;
        }
        if (type == "typing" && activeChat is not null && activeChat.Id == eventPeerId && payload.TryGetProperty("typing", out var typing))
        {
            PeerUsername.Text = typing.GetBoolean() ? "печатает…" : (string.IsNullOrWhiteSpace(activeChat.Username) ? "" : "@" + activeChat.Username + "  ·  ") + (_peerOnline ? "в сети" : "не в сети");
            return;
        }
        if (type == "message" && payload.TryGetProperty("message", out var incoming))
        {
            var message = _client.ParseRealtimeMessage(incoming);
            var peerId = message.IsMine ? incoming.GetProperty("recipient_id").GetString() : incoming.GetProperty("sender_id").GetString();
            var peer = _chats.FirstOrDefault(item => item.Id == peerId);
            var preview = string.IsNullOrWhiteSpace(message.Text) ? message.AttachmentName ?? "Вложение" : message.Text;
            if (peer is null)
            {
                if (!message.IsMine && peerId != null) NotifyIncoming(message.Id, peerId, "Новое сообщение", preview);
                _ = LoadConversationsAsync();
                return;
            }
            var chatIndex = _chats.IndexOf(peer);
            if (chatIndex >= 0) _chats[chatIndex] = peer with { Preview = preview };
            if (_activeChat?.Id == peerId)
            {
                if (AddVisibleMessage(message) && PhotoCacheScope is { } scope)
                {
                    _store.Save(scope, peer.Id, new[] { message });
                    ScrollToLatest();
                }
            }
            if (message.IsMine && message.ClientId is { } clientId && peerId != null)
                TraceOutgoing(clientId, "websocket", 0);
            if (DesktopNotificationPolicy.ShouldNotify(message.IsMine, _activeChat?.Id == peerId, IsVisible, IsActive, WindowState == WindowState.Minimized))
                NotifyIncoming(message.Id, peer.Id, peer.Name, preview);
            _ = LoadConversationsAsync();
            return;
        }
        if (type == "sync") { _ = LoadConversationsAsync(); if (activeChat?.Id == eventPeerId) _ = SyncSelectedChatAsync(false); }
        else if (type == "call_invite") _ = HandleIncomingCallAsync(payload.Clone());
        else if (type is "call_signal" or "call_state")
        {
            if (type == "call_state" && _incomingPrompt != null && _pendingCallInvite != null &&
                payload.TryGetProperty("call_id", out var call) && call.GetString() == _pendingCallInvite &&
                payload.TryGetProperty("state", out var state) && state.GetString() is "accepted" or "declined" or "cancelled" or "ended" or "missed")
                _incomingPrompt.EndRemote();
            _ = CallWindow.Active?.ReceiveEventAsync(payload.Clone());
        }
    }

    private async Task HandleIncomingCallAsync(JsonElement payload)
    {
        var callId = payload.TryGetProperty("call_id", out var id) ? id.GetString() : null;
        var callerId = payload.TryGetProperty("peer_id", out var peerId) ? peerId.GetString() : null;
        var callerName = payload.TryGetProperty("caller_name", out var name) ? name.GetString() ?? "Салом" : "Салом";
        if (string.IsNullOrWhiteSpace(callId) || _pendingCallInvite != null || CallWindow.Active != null || !_seenInvites.Add(callId)) return;
        _inviteOrder.Enqueue(callId);
        if (_inviteOrder.Count > 128) _seenInvites.Remove(_inviteOrder.Dequeue());
        var video = payload.TryGetProperty("mode", out var mode) && mode.GetString() == "video";
        _pendingCallInvite = callId;
        try
        {
            await Dispatcher.InvokeAsync(() => { }, DispatcherPriority.Background);
            if (!_client.HasSession || CallWindow.Active != null) return;
            var remaining = 45d;
            if (payload.TryGetProperty("expires_at", out var expires) && expires.TryGetDouble(out var timestamp))
                remaining = Math.Min(remaining, timestamp - DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() / 1000d);
            if (remaining <= 0) return;
            var prompt = new IncomingCallPrompt(callerName, video, TimeSpan.FromSeconds(remaining), _preferences.IncomingRingtone);
            _incomingPrompt = prompt;
            var accepted = prompt.ShowDialog() == true;
            _incomingPrompt = null;
            if (accepted)
            {
                _activeVoice?.Stop();
                RestoreFromTray();
                await CallWindow.AnswerIncomingAsync(_client, callId, callerName, this, video);
            }
            else if (!prompt.Expired && !string.IsNullOrWhiteSpace(callerId))
                await _client.UpdateCallAsync(callId, "decline");
        }
        catch (Exception error) { ShowToast("Не удалось открыть звонок: " + error.Message); }
        finally { _incomingPrompt = null; if (_pendingCallInvite == callId) _pendingCallInvite = null; }
    }

    private async void Call_Click(object sender, RoutedEventArgs e)
    {
        if (_activeChat is null) { ShowToast("Сначала выбери чат"); return; }
        if (CallWindow.Active != null || !CallButton.IsEnabled) return;
        CallButton.IsEnabled = VideoCallButton.IsEnabled = false;
        try { await CallWindow.StartOutgoingAsync(_client, _activeChat.Id, _activeChat.Name, this, ReferenceEquals(sender, VideoCallButton)); }
        catch (Exception error) { ShowToast("Не удалось начать звонок: " + error.Message); }
        finally { CallButton.IsEnabled = VideoCallButton.IsEnabled = true; }
    }

    private void NotifyIncoming(long messageId, string peerId, string title, string body)
    {
        if (!_notifiedMessages.Add(messageId)) return;
        if (_preferences.PrivateNotificationPreview) { title = "Салом"; body = "Новое сообщение"; }
        if (IsVisible && IsActive && WindowState != WindowState.Minimized)
        {
            ShowToast(title + ": " + (body.Length > 110 ? body[..110] + "…" : body));
            return;
        }
        var preview = body.Length > 110 ? body[..110] + "…" : body;
        if (!DesktopMessageToast.TryShow(messageId, peerId, title, preview))
        {
            _notificationPeer = peerId;
            _trayIcon.ShowBalloonTip(4500, "Салом · " + title, preview, System.Windows.Forms.ToolTipIcon.Info);
        }
    }

    private void RestoreFromTray()
    {
        if (!IsVisible) Show();
        WindowState = WindowState.Maximized;
        Activate();
    }

    internal void ActivateNotification(Guid peer)
    {
        if (Workspace.Visibility != Visibility.Visible || !IsLoaded) _pendingNotificationPeer = peer;
        else _ = OpenNotificationChatAsync(peer.ToString());
    }

    private async Task OpenNotificationChatAsync(string peerId)
    {
        if (!_client.HasSession) return;
        RestoreFromTray();
        await LoadConversationsAsync();
        if (_chats.FirstOrDefault(chat => chat.Id == peerId) is { } chat) ChatsList.SelectedItem = chat;
    }

    private void SetConnection(string label, bool online)
    {
        ConnectionState.Text = label;
        ConnectionState.Foreground = online ? System.Windows.Media.Brushes.LightGreen : System.Windows.Media.Brushes.Orange;
    }

    private async Task SyncSelectedChatAsync(bool reportErrors)
    {
        if (_activeChat is null || PhotoCacheScope is not { } scope || !IsActive || Workspace.Visibility != Visibility.Visible) return;
        if (_syncInProgress) { _syncAgain = true; return; }
        _syncInProgress = true;
        try
        {
            var chat = _activeChat;
            var revision = _historyRevision;
            var history = await _client.MessagesAsync(chat.Id, _messageCursor, _eventCursor);
            if (revision != _historyRevision || PhotoCacheScope != scope) { _syncAgain = true; return; }
            var removed = history.Events.Where(change => change.Kind is "hide" or "delete").Select(change => change.MessageId).ToArray();
            ApplyRemoval(scope, chat.Id, history.ClearedThrough, removed);
            var incoming = history.Messages.Where(message => message.Id > _clearedThrough && !_removedMessages.Contains(message.Id)).ToList();
            if (incoming.Count > 0) _store.Save(scope, chat.Id, incoming);
            var added = incoming.Count(AddVisibleMessage);
            foreach (var change in history.Events)
            {
                if (change.Edited is not { } edited || _removedMessages.Contains(edited.Id) || edited.Id <= _clearedThrough) continue;
                var previous = _messages.FirstOrDefault(message => message.Id == edited.Id);
                if (previous == null) continue;
                _store.Save(scope, chat.Id, new[] { edited });
                _messages[_messages.IndexOf(previous)] = edited;
            }
            var nextMessage = Math.Max(_messageCursor, Math.Max(history.ClearedThrough, history.Messages.LastOrDefault()?.Id ?? 0));
            _store.SaveCursor(scope, chat.Id, nextMessage, history.EventCursor, _clearedThrough);
            _eventCursor = history.EventCursor;
            _messageCursor = nextMessage;
            if (added > 0) ScrollToLatest();
            if (chat.UnreadCount > 0 || incoming.Count > 0 || removed.Length > 0) await LoadConversationsAsync();
            ConnectionState.Text = "●  синхронизировано"; ConnectionState.Foreground = System.Windows.Media.Brushes.LightGreen;
        }
        catch (Exception error)
        {
            ConnectionState.Text = "●  офлайн"; ConnectionState.Foreground = System.Windows.Media.Brushes.Orange;
            if (reportErrors) ShowToast("Показываю сохранённую историю. " + error.Message);
        }
        finally
        {
            _syncInProgress = false;
            if (_syncAgain)
            {
                _syncAgain = false;
                _ = SyncSelectedChatAsync(false);
            }
        }
    }

    private async void Send_Click(object sender, RoutedEventArgs e) => await SendMessageAsync();
    private async void MessageBox_KeyDown(object sender, System.Windows.Input.KeyEventArgs e)
    {
        if (e.Key != Key.Enter) return;
        e.Handled = true;
        if (ComposerInputPolicy.ShouldSend(true, Keyboard.Modifiers.HasFlag(ModifierKeys.Shift)))
        {
            if (!e.IsRepeat) await SendMessageAsync();
        }
        else
        {
            var next = MessageBox.SelectionStart + Environment.NewLine.Length;
            MessageBox.SelectedText = Environment.NewLine;
            MessageBox.Select(next, 0);
        }
    }

    private async Task SendMessageAsync()
    {
        if (_activeChat is not { } chat || PhotoCacheScope is not { } scope) { ShowToast("Сначала выбери чат"); return; }
        var text = MessageBox.Text.Trim();
        if (text.Length == 0) return;
        if (_editingMessage is { } edit)
        {
            if (_savingEdit) return;
            _savingEdit = true;
            try
            {
                var changed = await _client.EditMessageAsync(edit.Id, text);
                _store.Save(scope, chat.Id, new[] { changed });
                if (_editingMessage != edit || _activeChat?.Id != chat.Id) return;
                if (_messages.FirstOrDefault(message => message.Id == edit.Id) is { } old)
                    _messages[_messages.IndexOf(old)] = changed;
                CancelComposerAction();
            }
            catch (Exception error) { ShowToast(error.Message); }
            finally { _savingEdit = false; }
            return;
        }
        try
        {
            QueueOutgoing(new(Guid.NewGuid(), chat, text, _replyTo?.Id, DateTime.Now));
            MessageBox.Clear();
            SetReply(null);
            await DrainOutboxAsync();
        }
        catch (Exception) { ShowToast("Не удалось сохранить сообщение в очереди. Попробуй ещё раз."); }
    }

    private void QueueOutgoing(DesktopOutgoing item)
    {
        var scope = PhotoCacheScope ?? throw new InvalidOperationException();
        var elapsed = System.Diagnostics.Stopwatch.StartNew();
        TraceOutgoing(item.Id, "created", 0);
        _store.Enqueue(scope, item);
        TraceOutgoing(item.Id, "queued", elapsed.ElapsedMilliseconds);
        ShowOutgoing(item, "Отправляется");
        if (_activeChat?.Id == item.Peer.Id) ScrollToLatest();
        TraceOutgoing(item.Id, "visible", elapsed.ElapsedMilliseconds);
    }

    private void ShowOutgoing(DesktopOutgoing item, string status)
    {
        if (_activeChat?.Id != item.Peer.Id) return;
        if (_messages.Any(message => message.Id > 0 && message.IsMine && message.ClientId == item.Id)) return;
        var previous = _messages.FirstOrDefault(message => message.Id == 0 && message.ClientId == item.Id);
        var pending = new ChatMessage(0, item.Voice == null ? item.Text : "Голосовое сообщение", true, item.Created,
            clientId: item.Id) { PendingStatus = status };
        if (previous == null) _messages.Add(pending);
        else _messages[_messages.IndexOf(previous)] = pending;
    }

    private async Task DrainOutboxAsync()
    {
        if (_drainingOutbox || PhotoCacheScope is not { } scope || _realtimeCancellation is not { } session) return;
        var token = session.Token;
        _drainingOutbox = true;
        try
        {
            foreach (var item in _store.Outbox(scope))
            {
                if (token.IsCancellationRequested || PhotoCacheScope != scope) break;
                if (_failedOutgoing.Contains(item.Id)) continue;
                ShowOutgoing(item, "Отправляется");
                var elapsed = System.Diagnostics.Stopwatch.StartNew();
                TraceOutgoing(item.Id, "http_start", 0);
                try
                {
                    var sent = item.AudioPath == null
                        ? await _client.SendTextAsync(item.Peer.Id, item.Text, item.Id, item.ReplyId, token)
                        : await _client.SendAttachmentAsync(item.Peer, item.AudioPath, item.Text, item.Id,
                            new Progress<int>(), token, item.ReplyId, item.Voice);
                    TraceOutgoing(item.Id, "http_ack", elapsed.ElapsedMilliseconds);
                    _store.Save(scope, item.Peer.Id, new[] { sent });
                    _store.RemoveOutgoing(scope, item.Id);
                    if (item.AudioPath != null) TryDeleteRecording(item.AudioPath);
                    if (PhotoCacheScope == scope && _activeChat?.Id == item.Peer.Id)
                    {
                        AddVisibleMessage(sent);
                        ScrollToLatest();
                    }
                }
                catch (OperationCanceledException) when (token.IsCancellationRequested) { break; }
                catch (System.Net.Http.HttpRequestException error) when ((int?)error.StatusCode is 400 or 403 or 404 or 409 or 413 or 422)
                {
                    _failedOutgoing.Add(item.Id);
                    ShowOutgoing(item, "Не отправлено · повторить через меню");
                }
                catch (Exception)
                {
                    ShowOutgoing(item, "В очереди");
                    break;
                }
            }
        }
        finally { _drainingOutbox = false; }
    }

    private async void RetryOutgoing_Click(object sender, RoutedEventArgs e)
    {
        if (sender is not System.Windows.Controls.MenuItem { DataContext: ChatMessage { ClientId: { } id } }) return;
        _failedOutgoing.Remove(id);
        await DrainOutboxAsync();
    }

    private void MicrophoneMenu_Opened(object sender, RoutedEventArgs e)
    {
        if (sender is not System.Windows.Controls.ContextMenu menu) return;
        menu.Items.Clear();
        try
        {
            AddMicrophoneOption(menu, -1, "Микрофон Windows по умолчанию");
            for (var index = 0; index < NAudio.Wave.WaveInEvent.DeviceCount; index++)
                AddMicrophoneOption(menu, index, NAudio.Wave.WaveInEvent.GetCapabilities(index).ProductName);
        }
        catch (Exception) { ShowToast("Не удалось получить список микрофонов."); }
    }

    private void AddMicrophoneOption(System.Windows.Controls.ContextMenu menu, int id, string name)
    {
        var item = new System.Windows.Controls.MenuItem { Header = name, IsCheckable = true, IsChecked = _preferences.Microphone == id };
        item.Click += (_, _) =>
        {
            _preferences.Microphone = id;
            if (!_preferences.TrySave()) ShowToast("Не удалось сохранить настройку");
        };
        menu.Items.Add(item);
    }

    private void Mic_Down(object sender, MouseButtonEventArgs e)
    {
        e.Handled = true;
        if (_recorder != null || _finishingVoice) return;
        if (_activeChat is not { } chat || PhotoCacheScope is not { } scope) { ShowToast("Сначала выбери чат"); return; }
        if (!MicButton.CaptureMouse()) return;
        try
        {
            _micOrigin = e.GetPosition(this);
            _recordingPeer = chat;
            _recordingReply = _replyTo;
            _recordingScope = scope;
            _recorder = new DesktopVoiceRecorder(Path.GetTempPath(), _preferences.Microphone);
            VoiceRecordingStatus.Text = "●  00:00   ·   ← Отмена";
            VoiceRecordingStatus.Visibility = Visibility.Visible;
            _voiceTimer.Start();
        }
        catch (Exception)
        {
            MicButton.ReleaseMouseCapture();
            ShowToast("Микрофон недоступен. Проверь устройство и разрешение в настройках Windows.");
        }
    }

    private async void Mic_Move(object sender, System.Windows.Input.MouseEventArgs e)
    {
        if (_recorder != null && _micOrigin.X - e.GetPosition(this).X >= 64) await FinishRecordingAsync(cancel: true);
    }

    private async void Mic_Up(object sender, MouseButtonEventArgs e)
    {
        e.Handled = true;
        await FinishRecordingAsync(cancel: _micOrigin.X - e.GetPosition(this).X >= 64);
    }

    private async void Mic_CaptureLost(object sender, System.Windows.Input.MouseEventArgs e) => await FinishRecordingAsync(cancel: true);

    private async Task FinishRecordingAsync(bool cancel)
    {
        if (_recorder is not { } recorder || _recordingPeer is not { } peer) return;
        _recorder = null;
        _finishingVoice = true;
        _voiceTimer.Stop();
        MicButton.ReleaseMouseCapture();
        VoiceRecordingStatus.Visibility = Visibility.Collapsed;
        var reply = _recordingReply;
        var scope = _recordingScope;
        string? path = null;
        try
        {
            var id = Guid.NewGuid();
            var folder = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "SALOM", "VoiceOutbox");
            if (!cancel) Directory.CreateDirectory(folder);
            path = Path.Combine(folder, $"voice_{id:N}.m4a");
            var voice = await recorder.FinishAsync(path, cancel);
            if (cancel || PhotoCacheScope != scope) return;
            if (voice == null) { ShowToast("Запиши хотя бы одну секунду."); return; }
            QueueOutgoing(new(id, peer, "", reply?.Id, DateTime.Now, path, voice));
            path = null;
            if (_activeChat?.Id == peer.Id && _replyTo == reply) SetReply(null);
            _ = DrainOutboxAsync();
        }
        catch (Exception)
        {
            ShowToast("Не удалось записать голосовое. Проверь микрофон и его разрешения.");
        }
        finally
        {
            if (path != null) TryDeleteRecording(path);
            _finishingVoice = false;
        }
    }

    private static void TryDeleteRecording(string path)
    {
        try { File.Delete(path); } catch (IOException) { } catch (UnauthorizedAccessException) { }
    }

    private async void File_Click(object sender, RoutedEventArgs e)
    {
        if (_transfer is { } active) { active.Cancel(); return; }
        if (_activeChat is not { } chat || PhotoCacheScope is not { } uploadScope) { ShowToast("Сначала выбери чат"); return; }
        var upload = _pendingUpload;
        if (upload?.Peer.Id == chat.Id)
        {
            var choice = System.Windows.MessageBox.Show(this, "Повторить отправку? «Нет» откроет выбор файла.",
                "Передача файла", MessageBoxButton.YesNoCancel);
            if (choice == MessageBoxResult.Cancel) return;
            if (choice == MessageBoxResult.No) upload = null;
        }
        else upload = null;
        if (upload is null)
        {
            var dialog = new Microsoft.Win32.OpenFileDialog { Title = "Выбрать файл", CheckFileExists = true };
            if (dialog.ShowDialog(this) != true) return;
            upload = new(chat, dialog.FileName, MessageBox.Text.Trim(), Guid.NewGuid(), _replyTo);
        }
        _pendingUpload = upload;
        await TransferAsync(async (progress, token) =>
        {
            var sent = await _client.SendAttachmentAsync(upload.Peer, upload.Path, upload.Text, upload.Id, progress, token, upload.Reply?.Id);
            _pendingUpload = null;
            if (PhotoCacheScope == uploadScope && _activeChat?.Id == upload.Peer.Id)
            {
                if (AddVisibleMessage(sent)) _store.Save(uploadScope, upload.Peer.Id, new[] { sent });
                ScrollToLatest();
                if (MessageBox.Text.Trim() == upload.Text) MessageBox.Clear();
                if (_replyTo == upload.Reply) SetReply(null);
            }
            ShowToast("Файл отправлен");
        });
    }

    private void ScrollToLatest()
    {
        if (_messages.Count > 0) MessagesList.ScrollIntoView(_messages[^1]);
    }

    private bool AddVisibleMessage(ChatMessage message)
    {
        if (message.IsMine && message.ClientId is { } clientId &&
            _messages.FirstOrDefault(candidate => candidate.Id == 0 && candidate.ClientId == clientId) is { } pending)
            _messages.Remove(pending);
        if (message.Id <= _clearedThrough || _removedMessages.Contains(message.Id)) return false;
        var index = _messages.TakeWhile(candidate => candidate.Id != 0).Count();
        while (index > 0 && _messages[index - 1].Id > message.Id) index--;
        if (index > 0 && _messages[index - 1].Id == message.Id) return false;
        _messages.Insert(index, message);
        return true;
    }

    private void ApplyRemoval(string scope, string peerId, long clearedThrough, long[] removed)
    {
        if (clearedThrough <= _clearedThrough && removed.Length == 0) return;
        _store.RemoveHistory(scope, peerId, clearedThrough, removed);
        _clearedThrough = Math.Max(clearedThrough, _clearedThrough);
        _removedMessages.UnionWith(removed);
        for (var index = _messages.Count - 1; index >= 0; index--)
            if (_messages[index].Id > 0 && (_messages[index].Id <= _clearedThrough || _removedMessages.Contains(_messages[index].Id)))
                _messages.RemoveAt(index);
        if (_replyTo is { } reply && (reply.Id <= _clearedThrough || _removedMessages.Contains(reply.Id))) SetReply(null);
    }

    private void SetReply(ChatMessage? message)
    {
        _replyTo = message;
        ReplyBanner.Visibility = message == null ? Visibility.Collapsed : Visibility.Visible;
        ReplyLabel.Text = message == null ? "" : "Ответ · " + (message.IsMine ? "Вы" : _activeChat?.Name);
        ReplyText.Text = message?.Preview ?? "";
    }

    private void CancelReply_Click(object sender, RoutedEventArgs e) => CancelComposerAction();

    private void CancelComposerAction()
    {
        if (_editingMessage != null) MessageBox.Clear();
        _editingMessage = null;
        SetReply(null);
    }

    private void EditMessage_Click(object sender, RoutedEventArgs e)
    {
        if (sender is not System.Windows.Controls.MenuItem { DataContext: ChatMessage { CanEdit: true } message }
            || !_messages.Contains(message)) return;
        SetReply(null);
        _editingMessage = message;
        ReplyBanner.Visibility = Visibility.Visible;
        ReplyLabel.Text = "Редактирование";
        ReplyText.Text = message.Text;
        MessageBox.Text = message.Text;
        MessageBox.CaretIndex = MessageBox.Text.Length;
        MessageBox.Focus();
    }

    private void JumpToQuote_Click(object sender, RoutedEventArgs e)
    {
        if (sender is not System.Windows.Controls.MenuItem { DataContext: ChatMessage { ReplyId: { } id } }) return;
        if (_messages.FirstOrDefault(message => message.Id == id) is { } original) MessagesList.ScrollIntoView(original);
        else ShowToast("Исходное сообщение недоступно в загруженной истории.");
    }

    private void Reply_Click(object sender, RoutedEventArgs e)
    {
        if (sender is System.Windows.Controls.MenuItem { DataContext: ChatMessage message } && _messages.Contains(message))
        {
            CancelComposerAction();
            SetReply(message);
            MessageBox.Focus();
        }
    }

    private async void Reaction_Click(object sender, RoutedEventArgs e)
    {
        if (sender is not System.Windows.Controls.MenuItem { DataContext: ChatMessage { Id: > 0 } target } item || !_messages.Contains(target)) return;
        try
        {
            var emoji = item.Tag as string;
            await _client.SetReactionAsync(target.Id, emoji == target.MyReaction ? null : emoji);
            await SyncSelectedChatAsync(false);
        }
        catch (Exception) { ShowToast("Не удалось изменить реакцию. Попробуй ещё раз."); }
    }

    private void CopyMessage_Click(object sender, RoutedEventArgs e)
    {
        if (sender is not System.Windows.Controls.MenuItem { DataContext: ChatMessage message } item) return;
        var selected = System.Windows.Controls.ItemsControl.ItemsControlFromItemContainer(item) is
            System.Windows.Controls.ContextMenu { PlacementTarget: System.Windows.Controls.TextBox editor }
            ? editor.SelectedText : "";
        var text = selected.Length > 0 ? selected : message.HasText ? message.Text : message.AttachmentName ?? message.Preview;
        try { System.Windows.Clipboard.SetText(text); }
        catch (System.Runtime.InteropServices.ExternalException) { ShowToast("Буфер обмена занят. Попробуй ещё раз."); }
    }

    private async void CopyPhoto_Click(object sender, RoutedEventArgs e)
    {
        if (sender is not System.Windows.Controls.MenuItem { DataContext: ChatMessage { HasPhoto: true, AttachmentId: { } id } }
            || PhotoCacheScope is not { } scope) return;
        using var timeout = new CancellationTokenSource(TimeSpan.FromMinutes(2));
        try
        {
            var bitmap = await Photos.LoadAsync(scope, id, PhotoCache.ViewerEdge, new Progress<int>(), timeout.Token);
            if (PhotoCacheScope != scope) return;
            System.Windows.Clipboard.SetImage(bitmap);
            ShowToast("Изображение скопировано");
        }
        catch (System.Runtime.InteropServices.ExternalException) { ShowToast("Буфер обмена занят. Попробуй ещё раз."); }
        catch (Exception) { ShowToast("Не удалось скопировать изображение. Попробуй ещё раз."); }
    }

    private void CopyAttachmentName_Click(object sender, RoutedEventArgs e)
    {
        if (sender is not System.Windows.Controls.MenuItem { DataContext: ChatMessage { AttachmentName: { Length: > 0 } name } }) return;
        try { System.Windows.Clipboard.SetText(name); }
        catch (System.Runtime.InteropServices.ExternalException) { ShowToast("Буфер обмена занят. Попробуй ещё раз."); }
    }

    private void SaveMessagePhoto_Click(object sender, RoutedEventArgs e)
    {
        if (sender is System.Windows.Controls.MenuItem { DataContext: ChatMessage { HasPhoto: true } message }) SaveAttachment(message);
    }

    private async void DeleteMessage_Click(object sender, RoutedEventArgs e)
    {
        if (_historyAction || _activeChat is not { } chat || PhotoCacheScope is not { } account ||
            sender is not System.Windows.Controls.MenuItem { DataContext: ChatMessage message, Tag: string scope } ||
            !_messages.Contains(message)) return;
        var everyone = scope == "everyone";
        if (everyone && !message.IsMine) return;
        var prompt = everyone ? "Удалить сообщение у обоих участников?" : "Удалить сообщение только у себя?";
        if (System.Windows.MessageBox.Show(this, prompt, "Удаление сообщения", MessageBoxButton.YesNo,
            MessageBoxImage.Question, MessageBoxResult.No) != MessageBoxResult.Yes) return;
        _historyAction = true;
        try
        {
            await _client.DeleteMessageAsync(message.Id, everyone);
            if (PhotoCacheScope != account) return;
            if (_activeChat?.Id == chat.Id) { _historyRevision++; ApplyRemoval(account, chat.Id, 0, new[] { message.Id }); }
            else _store.RemoveHistory(account, chat.Id, 0, new[] { message.Id });
            await LoadConversationsAsync();
        }
        catch (Exception error) { ShowToast(error.Message); }
        finally { _historyAction = false; }
    }

    private void ChatMenu_Click(object sender, RoutedEventArgs e)
    {
        if (_activeChat == null || sender is not System.Windows.Controls.Button button || button.ContextMenu == null) return;
        button.ContextMenu.PlacementTarget = button;
        button.ContextMenu.IsOpen = true;
    }

    private async void ClearChat_Click(object sender, RoutedEventArgs e)
    {
        if (_historyAction || _activeChat is not { } chat || PhotoCacheScope is not { } account) return;
        if (System.Windows.MessageBox.Show(this, $"Очистить историю чата с {chat.Name} только у себя?",
            "Очистка диалога", MessageBoxButton.YesNo, MessageBoxImage.Question, MessageBoxResult.No) != MessageBoxResult.Yes) return;
        _historyAction = true;
        try
        {
            var cutoff = await _client.ClearChatAsync(chat.Id);
            if (PhotoCacheScope != account) return;
            if (_activeChat?.Id == chat.Id) { _historyRevision++; ApplyRemoval(account, chat.Id, cutoff, Array.Empty<long>()); }
            else _store.RemoveHistory(account, chat.Id, cutoff, Array.Empty<long>());
            await LoadConversationsAsync();
        }
        catch (Exception error) { ShowToast(error.Message); }
        finally { _historyAction = false; }
    }

    private void DownloadAttachment_Click(object sender, RoutedEventArgs e)
    {
        if (sender is System.Windows.Controls.Button { Tag: ChatMessage message }) SaveAttachment(message);
    }

    internal async void SaveAttachment(ChatMessage message)
    {
        if (_transfer != null) { ShowToast("Дождись окончания передачи или нажми «Отмена»"); return; }
        if (string.IsNullOrWhiteSpace(message.AttachmentId)) return;
        var dialog = new Microsoft.Win32.SaveFileDialog { FileName = Path.GetFileName(message.AttachmentName ?? "salom-file") };
        if (dialog.ShowDialog(this) != true) return;
        await TransferAsync(async (progress, token) =>
        {
            await _client.DownloadAttachmentAsync(message.AttachmentId, dialog.FileName, progress, token);
            ShowToast("Файл сохранён");
        });
    }

    private async Task TransferAsync(Func<IProgress<int>, CancellationToken, Task> transfer)
    {
        using var cancellation = new CancellationTokenSource();
        _transfer = cancellation;
        FileButton.Content = "Отмена";
        var progress = new Progress<int>(percent =>
        {
            if (ReferenceEquals(_transfer, cancellation) && !cancellation.IsCancellationRequested)
                FileButton.Content = $"Отмена · {percent}%";
        });
        try { await transfer(progress, cancellation.Token); }
        catch (OperationCanceledException) when (cancellation.IsCancellationRequested) { ShowToast("Передача отменена"); }
        catch (OperationCanceledException) { ShowToast("Передача прервалась по тайм-ауту. Попробуй снова."); }
        catch (Exception error) { ShowToast(error.Message); }
        finally
        {
            _transfer = null;
            FileButton.Content = _pendingUpload != null && _pendingUpload.Peer.Id == _activeChat?.Id ? "Повторить" : "Файл";
        }
    }

    private void AccountMenu_Click(object sender, RoutedEventArgs e)
    {
        if (sender is not System.Windows.Controls.Button { ContextMenu: { } menu } button) return;
        menu.PlacementTarget = button;
        menu.IsOpen = true;
    }

    private void Update_Click(object sender, RoutedEventArgs e)
    {
        if (CallWindow.Active != null || _transfer != null || _recorder != null)
        {
            ShowToast("Заверши звонок, запись или передачу файла перед обновлением.");
            return;
        }
        var check = new UpdateWindow { Owner = this };
        if (check.ShowDialog() != true || check.Installer is not { } installer) return;
        try
        {
            using var setup = System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo(installer, "--update")
                { UseShellExecute = true });
            if (setup is null) throw new InvalidOperationException();
            _exitRequested = true;
            Close();
        }
        catch (Exception error) when (error is System.ComponentModel.Win32Exception or InvalidOperationException or IOException)
        {
            ShowToast("Не удалось открыть установщик. Попробуй обновить ещё раз.");
        }
    }

    private void SignOut_Click(object sender, RoutedEventArgs e)
    {
        if (System.Windows.MessageBox.Show(this, "Выйти из аккаунта на этом компьютере?", "Аккаунт",
            MessageBoxButton.YesNo, MessageBoxImage.Question, MessageBoxResult.No) != MessageBoxResult.Yes) return;
        _realtimeCancellation?.Cancel();
        _transfer?.Cancel(); _pendingUpload = null;
        _historyRevision++;
        CancelComposerAction();
        _client.SignOut(); _notificationPeer = null; _pendingNotificationPeer = null; _activeChat = null; _chats.Clear(); _messages.Clear();
        _realtimeCancellation?.Dispose(); _realtimeCancellation = null;
        Workspace.Visibility = Visibility.Collapsed; LoginPane.Visibility = Visibility.Visible;
        ConnectionState.Text = "●  отключено"; LoginStatus.Text = "Ты вышел из аккаунта на этом компьютере.";
    }

    protected override void OnClosing(System.ComponentModel.CancelEventArgs e)
    {
        if (!_exitRequested && _preferences.CloseToTray && _client.HasSession)
        {
            e.Cancel = true;
            Hide();
        }
        base.OnClosing(e);
    }

    internal bool ShouldContinueNotifications => !_exitRequested && _client.HasSession;

    protected override void OnClosed(EventArgs e)
    {
        _incomingPrompt?.EndRemote();
        _activeVoice?.Stop();
        _transfer?.Cancel();
        _realtimeCancellation?.Cancel();
        _realtimeCancellation?.Dispose();
        _trayIcon.Visible = false;
        _trayIcon.Dispose();
        base.OnClosed(e);
    }

    private async void ShowToast(string message)
    {
        Toast.Text = message; Toast.Visibility = Visibility.Visible;
        await Task.Delay(3500); Toast.Visibility = Visibility.Collapsed;
    }
}
