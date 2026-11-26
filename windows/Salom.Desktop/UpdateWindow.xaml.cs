using System.IO;
using System.Net.Http;
using System.Reflection;
using System.Text.Json;
using System.Windows;

namespace Salom.Desktop;

public partial class UpdateWindow : Window
{
    private static readonly Assembly Assembly = typeof(UpdateWindow).Assembly;
    private static readonly DesktopBuildVersion InstalledVersion = new(
        new Version(Assembly.GetName().Version!.ToString(3)),
        Assembly.GetCustomAttribute<AssemblyInformationalVersionAttribute>()?.InformationalVersion);
    private DesktopRelease? _release;
    private CancellationTokenSource? _operation;
    private bool _finished;

    internal string? Installer { get; private set; }

    internal event Action<string>? InstallRequested;

    public UpdateWindow()
    {
        InitializeComponent();
        WindowAppearance.UseDarkCaption(this);
        Installed.Text = $"Установлена версия {InstalledVersion.Display}";
        Loaded += async (_, _) => await RunAsync();
        Closing += (_, _) => _operation?.Cancel();
    }

    private async void Action_Click(object sender, RoutedEventArgs e) => await RunAsync();

    private async Task RunAsync()
    {
        if (_operation != null) return;
        if (_finished && Installer is { } installer)
        {
            InstallRequested?.Invoke(installer);
            return;
        }
        using var cancellation = new CancellationTokenSource();
        _operation = cancellation;
        Action.IsEnabled = false;
        Progress.Visibility = Visibility.Visible;
        Progress.IsIndeterminate = _release is null;
        Progress.Value = 0;
        try
        {
            if (_release is not { } release)
            {
                Status.Text = "Проверяю обновления…";
                var available = await DesktopUpdates.Official.CheckAsync(cancellation.Token);
                if (cancellation.IsCancellationRequested || _finished) return;
                if (new DesktopBuildVersion(available.Version, null).IsNewerThan(InstalledVersion))
                {
                    _release = available;
                    Status.Text = $"Доступна версия {available.Version} · {available.Size / 1_000_000d:F1} МБ. Салом перезапустится, аккаунт и переписка сохранятся.";
                    Action.Content = "Скачать";
                }
                else
                {
                    Status.Text = available.Version == InstalledVersion.Number
                        ? "У тебя уже последняя версия ✓"
                        : $"На сайте сейчас версия {available.Version}. Установленная сборка новее.";
                    Action.Content = "Проверить ещё раз";
                }
                return;
            }

            Status.Text = "Скачиваю обновление…";
            var lastReported = -5;
            DesktopUpdateToast.Progress(release.Version, 0);
            var progress = new Progress<int>(value =>
            {
                if (cancellation.IsCancellationRequested || _finished || _operation != cancellation) return;
                Progress.Value = value;
                Status.Text = value < 100 ? $"Скачиваю обновление · {value}%" : "Проверяю файл…";
                if (value >= lastReported + 5)
                {
                    lastReported = value;
                    DesktopUpdateToast.Progress(release.Version, value);
                }
            });
            var folder = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "SALOM", "Updates");
            Installer = await DesktopUpdates.Official.DownloadAsync(release, folder, progress, cancellation.Token);
            if (!cancellation.IsCancellationRequested)
            {
                _finished = true;
                Status.Text = "Обновление скачано и проверено.";
                Action.Content = "Установить";
                DesktopUpdateToast.Ready(release.Version);
            }
        }
        catch (OperationCanceledException) when (cancellation.IsCancellationRequested) { }
        catch (InvalidDataException error) { Status.Text = error.Message; }
        catch (IOException) { Status.Text = "Не удалось сохранить установщик. Проверь свободное место и повтори попытку."; }
        catch (UnauthorizedAccessException) { Status.Text = "Windows не разрешила сохранить установщик."; }
        catch (Exception error) when (error is HttpRequestException or OperationCanceledException)
        {
            Status.Text = "Не удалось связаться с сервером обновлений. Проверь интернет и повтори попытку.";
        }
        catch (Exception error) when (error is JsonException or KeyNotFoundException or InvalidOperationException or FormatException)
        {
            Status.Text = "Сервер вернул неверные сведения об обновлении Windows.";
        }
        finally
        {
            _operation = null;
            Progress.Visibility = Visibility.Collapsed;
            Action.IsEnabled = true;
            if (_release != null && !_finished) Action.Content = "Продолжить скачивание";
            if (_release is { } remaining && !Progress.IsIndeterminate && !_finished)
                DesktopUpdateToast.Interrupted(remaining.Version);
        }
    }

    private void Hide_Click(object sender, RoutedEventArgs e) => Hide();
}
