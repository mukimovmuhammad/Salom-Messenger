using System.Collections.ObjectModel;
using System.Windows;
using System.Windows.Input;
using System.Windows.Threading;
using Microsoft.VisualBasic;

namespace Salom.Desktop;

public partial class MainWindow : Window
{
    private readonly DesktopClient _client = new();
    private readonly LocalChatStore _store = new();
    private readonly ObservableCollection<ChatItem> _chats = new();
    private readonly ObservableCollection<ChatMessage> _messages = new();
    private readonly DispatcherTimer _syncTimer = new() { Interval = TimeSpan.FromSeconds(7) };
    private ChatItem? _activeChat;
    private bool _syncInProgress;
    private bool _searchPlaceholder = true;

    public MainWindow()
    {
        InitializeComponent();
        ChatsList.ItemsSource = _chats;
        MessagesList.ItemsSource = _messages;
        Loaded += Window_Loaded;
        _syncTimer.Tick += async (_, _) => await SyncSelectedChatAsync(false);
        _syncTimer.Start();
    }

    private async void Window_Loaded(object sender, RoutedEventArgs e)
    {
        if (!_client.HasSession) return;
        LoginStatus.Text = "Проверяю сохранённый вход…";
        try { await _client.ValidateSessionAsync(); await ShowWorkspaceAsync(); }
        catch (Exception error) { LoginStatus.Text = "Сессия истекла. Войди через Google ещё раз. " + error.Message; }
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

    private async Task ShowWorkspaceAsync()
    {
        LoginPane.Visibility = Visibility.Collapsed; Workspace.Visibility = Visibility.Visible;
        LoginStatus.Text = ""; ConnectionState.Text = "●  подключено";
        ConnectionState.Foreground = System.Windows.Media.Brushes.LightGreen;
        await LoadConversationsAsync();
    }

    private async Task LoadConversationsAsync()
    {
        try
        {
            var selectedId = _activeChat?.Id;
            var conversations = await _client.ConversationsAsync();
            _chats.Clear(); foreach (var chat in conversations) _chats.Add(chat);
            if (selectedId is not null) ChatsList.SelectedItem = _chats.FirstOrDefault(c => c.Id == selectedId);
            if (ChatsList.SelectedItem is null && _chats.Count > 0) ChatsList.SelectedIndex = 0;
        }
        catch (Exception error) { ShowToast("Не удалось загрузить список чатов: " + error.Message); }
    }

    private async void Search_Click(object sender, RoutedEventArgs e) => await SearchPeopleAsync();
    private async void Search_KeyDown(object sender, KeyEventArgs e) { if (e.Key == Key.Enter) { e.Handled = true; await SearchPeopleAsync(); } }

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
        if (ChatsList.SelectedItem is not ChatItem chat) return;
        _activeChat = chat; PeerName.Text = chat.Name; PeerUsername.Text = string.IsNullOrWhiteSpace(chat.Username) ? "" : "@" + chat.Username;
        _messages.Clear(); foreach (var message in _store.Load(chat.Id)) _messages.Add(message);
        await SyncSelectedChatAsync(true);
    }

    private async Task SyncSelectedChatAsync(bool reportErrors)
    {
        if (_syncInProgress || _activeChat is null || !IsActive || Workspace.Visibility != Visibility.Visible) return;
        _syncInProgress = true;
        try
        {
            var chat = _activeChat;
            var after = _messages.Count == 0 ? 0 : _messages.Max(message => message.Id);
            var incoming = await _client.MessagesAsync(chat.Id, after);
            if (_activeChat?.Id != chat.Id) return;
            foreach (var message in incoming) _messages.Add(message);
            if (incoming.Count > 0) _store.Save(chat.Id, _messages);
            if (incoming.Count > 0) MessagesScroll.ScrollToEnd();
            ConnectionState.Text = "●  синхронизировано"; ConnectionState.Foreground = System.Windows.Media.Brushes.LightGreen;
        }
        catch (Exception error)
        {
            ConnectionState.Text = "●  офлайн"; ConnectionState.Foreground = System.Windows.Media.Brushes.Orange;
            if (reportErrors) ShowToast("Показываю сохранённую историю. " + error.Message);
        }
        finally { _syncInProgress = false; }
    }

    private async void Send_Click(object sender, RoutedEventArgs e) => await SendMessageAsync();
    private async void MessageBox_KeyDown(object sender, KeyEventArgs e) { if (e.Key == Key.Enter && Keyboard.Modifiers != ModifierKeys.Shift) { e.Handled = true; await SendMessageAsync(); } }

    private async Task SendMessageAsync()
    {
        if (_activeChat is null) { ShowToast("Сначала выбери чат"); return; }
        var text = MessageBox.Text.Trim(); if (text.Length == 0) return;
        SendButton.IsEnabled = false;
        try
        {
            var chat = _activeChat; var sent = await _client.SendAsync(chat, text);
            if (_activeChat?.Id == chat.Id) { _messages.Add(sent); _store.Save(chat.Id, _messages); MessagesScroll.ScrollToEnd(); MessageBox.Clear(); }
            var index = _chats.IndexOf(chat); if (index >= 0) _chats[index] = chat with { Preview = text };
        }
        catch (Exception error) { ShowToast(error.Message); }
        finally { SendButton.IsEnabled = true; }
    }

    private void SignOut_Click(object sender, RoutedEventArgs e)
    {
        _client.SignOut(); _activeChat = null; _chats.Clear(); _messages.Clear();
        Workspace.Visibility = Visibility.Collapsed; LoginPane.Visibility = Visibility.Visible;
        ConnectionState.Text = "●  отключено"; LoginStatus.Text = "Ты вышел из аккаунта на этом компьютере.";
    }

    private async void ShowToast(string message)
    {
        Toast.Text = message; Toast.Visibility = Visibility.Visible;
        await Task.Delay(3500); Toast.Visibility = Visibility.Collapsed;
    }
}
