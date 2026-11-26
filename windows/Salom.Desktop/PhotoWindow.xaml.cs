using System.IO;
using System.Windows;
using System.Windows.Input;
using System.Windows.Media.Imaging;
using Microsoft.Win32;

namespace Salom.Desktop;

public partial class PhotoWindow : Window
{
    private readonly PhotoCache _cache;
    private readonly string _scope;
    private readonly ChatMessage _message;
    private readonly CancellationTokenSource _lifetime = new();
    private double _zoom = 1;

    internal PhotoWindow(PhotoCache cache, string scope, ChatMessage message, BitmapSource thumbnail)
    {
        InitializeComponent();
        WindowAppearance.UseDarkCaption(this);
        _cache = cache;
        _scope = scope;
        _message = message;
        Picture.Source = thumbnail;
        Loaded += async (_, _) => await LoadImageAsync();
        Closed += (_, _) => _lifetime.Cancel();
    }

    private async Task LoadImageAsync()
    {
        UpdateSize();
        Status.Text = "Загружаю изображение…";
        try
        {
            var image = await _cache.LoadAsync(_scope, _message.AttachmentId!, PhotoCache.ViewerEdge,
                new Progress<int>(), _lifetime.Token);
            if (_lifetime.IsCancellationRequested) return;
            Picture.Source = image;
            Status.Text = "";
            UpdateSize();
        }
        catch (OperationCanceledException) when (_lifetime.IsCancellationRequested) { }
        catch (Exception) { Status.Text = "Полный размер недоступен. Здесь открыта миниатюра."; }
    }

    private void ChangeZoom(double value)
    {
        _zoom = Math.Clamp(value, 0.25, 4);
        UpdateSize();
    }

    private void UpdateSize()
    {
        if (Picture.Source is not BitmapSource image || Viewport.ActualWidth <= 0 || Viewport.ActualHeight <= 0) return;
        var fit = Math.Min((Viewport.ActualWidth - 20) / image.PixelWidth, (Viewport.ActualHeight - 20) / image.PixelHeight);
        var factor = Math.Max(.01, fit) * _zoom;
        Picture.Width = image.PixelWidth * factor;
        Picture.Height = image.PixelHeight * factor;
    }

    private void Viewport_SizeChanged(object sender, SizeChangedEventArgs e) => UpdateSize();
    private void ZoomOut_Click(object sender, RoutedEventArgs e) => ChangeZoom(_zoom / 1.25);
    private void ZoomIn_Click(object sender, RoutedEventArgs e) => ChangeZoom(_zoom * 1.25);
    private void Fit_Click(object sender, RoutedEventArgs e) => ChangeZoom(1);
    private void Viewer_KeyDown(object sender, System.Windows.Input.KeyEventArgs e)
    {
        if (e.Key == Key.Escape) Close();
    }

    private async void Save_Click(object sender, RoutedEventArgs e)
    {
        var dialog = new Microsoft.Win32.SaveFileDialog { FileName = Path.GetFileName(_message.AttachmentName ?? "salom-photo") };
        if (dialog.ShowDialog(this) != true) return;
        SaveButton.IsEnabled = false;
        try
        {
            await _cache.SaveAsync(_scope, _message.AttachmentId!, dialog.FileName, new Progress<int>(), _lifetime.Token);
            Status.Text = "Фото сохранено";
        }
        catch (OperationCanceledException) when (_lifetime.IsCancellationRequested) { }
        catch (Exception) { Status.Text = "Не удалось сохранить фото. Попробуй ещё раз."; }
        finally { SaveButton.IsEnabled = true; }
    }
}
