using System.IO;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Threading;
using Rectangle = System.Windows.Shapes.Rectangle;

namespace Salom.Desktop;

public partial class VoiceBubble : System.Windows.Controls.UserControl
{
    private readonly DispatcherTimer _clock = new() { Interval = TimeSpan.FromMilliseconds(140) };
    private CancellationTokenSource? _load;
    private MediaPlayer? _player;
    private string? _file;
    private bool _ready;
    private bool _playing;
    private bool _updating;
    private TimeSpan _duration;
    private static readonly System.Windows.Media.Brush Muted = FrozenBrush(0x74, 0x83, 0x94);
    private static readonly System.Windows.Media.Brush Accent = FrozenBrush(0xff, 0xc4, 0);

    private static System.Windows.Media.Brush FrozenBrush(byte r, byte g, byte b)
    {
        var brush = new SolidColorBrush(System.Windows.Media.Color.FromRgb(r, g, b));
        brush.Freeze();
        return brush;
    }

    public VoiceBubble()
    {
        InitializeComponent();
        _clock.Tick += (_, _) => RefreshProgress();
    }

    private async void Play_Click(object sender, RoutedEventArgs e)
    {
        if (_load != null || DataContext is not ChatMessage { HasVoice: true, AttachmentId: { } id } message ||
            Window.GetWindow(this) is not MainWindow window || window.PhotoCacheScope is not { } scope) return;
        if (_ready && _player != null)
        {
            if (_playing) { _player.Pause(); SetPlaying(false); }
            else { window.ActivateVoice(this); _player.Play(); SetPlaying(true); }
            return;
        }

        window.ActivateVoice(this);
        using var load = new CancellationTokenSource();
        _load = load;
        VoiceTime.Text = "Загрузка…";
        PlayButton.IsEnabled = false;
        var folder = System.IO.Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "SALOM", "VoicePlayback");
        var suffix = message.AttachmentMime == "audio/aac" ? ".aac" : ".m4a";
        var path = System.IO.Path.Combine(folder, Guid.NewGuid().ToString("N") + suffix);
        try
        {
            Directory.CreateDirectory(folder);
            await window.DownloadVoiceAsync(scope, id, path, new Progress<int>(), load.Token);
            if (load.IsCancellationRequested || window.PhotoCacheScope != scope) return;
            _file = path;
            var player = new MediaPlayer();
            _player = player;
            player.MediaOpened += (_, _) =>
            {
                if (_player != player) return;
                _duration = player.NaturalDuration.HasTimeSpan ? player.NaturalDuration.TimeSpan : _duration;
                _ready = true;
                PlayButton.IsEnabled = true;
                Seek.IsEnabled = _duration > TimeSpan.Zero;
                player.Play();
                SetPlaying(true);
            };
            player.MediaEnded += (_, _) =>
            {
                if (_player != player) return;
                player.Pause();
                player.Position = TimeSpan.Zero;
                SetPlaying(false);
            };
            player.MediaFailed += (_, _) =>
            {
                if (_player == player) { Stop(); VoiceTime.Text = "Не удалось открыть · повторить"; }
            };
            player.Open(new Uri(path));
        }
        catch (OperationCanceledException) when (load.IsCancellationRequested) { }
        catch (Exception)
        {
            if (_load == load) { Stop(); VoiceTime.Text = "Ошибка · повторить"; }
        }
        finally
        {
            if (_load == load) _load = null;
            if (_file != path) DeleteFile(path);
            if (_player == null) PlayButton.IsEnabled = true;
        }
    }

    internal void Stop()
    {
        _load?.Cancel();
        _load = null;
        _clock.Stop();
        _player?.Close();
        _player = null;
        if (_file != null) { DeleteFile(_file); _file = null; }
        _ready = false;
        Seek.IsEnabled = false;
        PlayButton.IsEnabled = true;
        _duration = TimeSpan.FromMilliseconds((DataContext as ChatMessage)?.Voice?.DurationMs ?? 0);
        SetPlaying(false);
    }

    private static void DeleteFile(string path)
    {
        try { File.Delete(path); } catch (IOException) { } catch (UnauthorizedAccessException) { }
    }

    private void SetPlaying(bool playing)
    {
        _playing = playing;
        PlayIcon.Data = Geometry.Parse(playing ? "M2 1 H6 V15 H2 Z M10 1 H14 V15 H10 Z" : "M3 1 L15 8 L3 15 Z");
        if (playing) _clock.Start(); else _clock.Stop();
        RefreshProgress();
    }

    private void RefreshProgress()
    {
        var position = _player?.Position ?? TimeSpan.Zero;
        _updating = true;
        Seek.Value = _duration.TotalSeconds > 0 ? Math.Clamp(position.TotalSeconds / _duration.TotalSeconds, 0, 1) : 0;
        _updating = false;
        VoiceTime.Text = $"{Format(position)} / {Format(_duration)}";
        DrawWave();
    }

    private static string Format(TimeSpan time) => $"{(int)time.TotalMinutes}:{time.Seconds:00}";

    private void DrawWave()
    {
        if (Wave.ActualWidth <= 0) return;
        var samples = (DataContext as ChatMessage)?.Voice?.Waveform;
        var count = samples is { Length: > 0 } ? samples.Length : 24;
        var step = Wave.ActualWidth / count;
        if (Wave.Children.Count != count)
        {
            Wave.Children.Clear();
            for (var i = 0; i < count; i++) Wave.Children.Add(new Rectangle { RadiusX = 1.5, RadiusY = 1.5 });
        }
        for (var i = 0; i < count; i++)
        {
            var height = samples is { Length: > 0 } ? 4 + Math.Clamp(samples[i], 0, 100) * .18 : 7;
            var bar = (Rectangle)Wave.Children[i];
            bar.Width = step * .54;
            bar.Height = height;
            bar.Fill = (i + .5) / count <= Seek.Value ? Accent : Muted;
            Canvas.SetLeft(bar, i * step);
            Canvas.SetTop(bar, (24 - height) / 2);
        }
    }

    private void Seek_Changed(object sender, RoutedPropertyChangedEventArgs<double> e)
    {
        if (_updating || !_ready || _player == null) return;
        var target = TimeSpan.FromSeconds(_duration.TotalSeconds * e.NewValue);
        _player.Position = target;
        VoiceTime.Text = $"{Format(target)} / {Format(_duration)}";
        DrawWave();
    }

    private void Bubble_Loaded(object sender, RoutedEventArgs e)
    {
        if (_player == null)
        {
            _duration = TimeSpan.FromMilliseconds((DataContext as ChatMessage)?.Voice?.DurationMs ?? 0);
            RefreshProgress();
        }
    }
    private void Bubble_Unloaded(object sender, RoutedEventArgs e) => Stop();
    private void Bubble_Changed(object sender, DependencyPropertyChangedEventArgs e) { if (IsLoaded) Stop(); }
    private void Wave_SizeChanged(object sender, SizeChangedEventArgs e) => DrawWave();
    private void Save_Click(object sender, RoutedEventArgs e)
    {
        if (DataContext is ChatMessage message && Window.GetWindow(this) is MainWindow window) window.SaveAttachment(message);
    }
}
