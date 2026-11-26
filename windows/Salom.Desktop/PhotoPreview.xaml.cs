using System.Windows;
using System.Windows.Controls;

namespace Salom.Desktop;

public partial class PhotoPreview : System.Windows.Controls.UserControl
{
    private CancellationTokenSource? _load;
    public PhotoPreview()
    {
        InitializeComponent();
        IsVisibleChanged += (_, _) =>
        {
            if (IsVisible && Thumbnail.Source == null && _load == null) _ = LoadAsync();
        };
    }

    private void Preview_Loaded(object sender, RoutedEventArgs e) => _ = LoadAsync();

    private void Preview_Unloaded(object sender, RoutedEventArgs e)
    {
        _load?.Cancel();
        Thumbnail.Source = null;
    }

    private void Preview_DataContextChanged(object sender, DependencyPropertyChangedEventArgs e)
    {
        if (IsLoaded) _ = LoadAsync();
    }

    private async void Retry_Click(object sender, RoutedEventArgs e) => await LoadAsync();

    private async Task LoadAsync()
    {
        _load?.Cancel();
        if (!IsLoaded || !IsVisible || DataContext is not ChatMessage { HasPhoto: true, AttachmentId: { } id } ||
            Window.GetWindow(this) is not MainWindow window || window.PhotoCacheScope is not { } scope)
            return;
        using var load = new CancellationTokenSource();
        _load = load;
        Thumbnail.Source = null;
        OpenPhoto.Visibility = Visibility.Collapsed;
        Placeholder.Visibility = Loading.Visibility = Visibility.Visible;
        Recovery.Visibility = Visibility.Collapsed;
        StatusText.Text = "Загружаю фото…";
        Loading.IsIndeterminate = true;
        try
        {
            var progress = new Progress<int>(percent =>
            {
                if (_load != load || load.IsCancellationRequested) return;
                Loading.IsIndeterminate = percent == 100;
                Loading.Value = percent;
            });
            var photo = await window.Photos.LoadAsync(scope, id, PhotoCache.ThumbnailEdge, progress, load.Token);
            if (_load != load || load.IsCancellationRequested || window.PhotoCacheScope != scope) return;
            Thumbnail.Source = photo;
            Placeholder.Visibility = Visibility.Collapsed;
            OpenPhoto.Visibility = Visibility.Visible;
        }
        catch (OperationCanceledException) when (load.IsCancellationRequested) { }
        catch (Exception error)
        {
            if (_load != load || load.IsCancellationRequested) return;
            Loading.Visibility = Visibility.Collapsed;
            StatusText.Text = error is System.Net.Http.HttpRequestException { StatusCode: System.Net.HttpStatusCode.NotFound or System.Net.HttpStatusCode.Forbidden }
                ? "Фото недоступно" : "Не удалось открыть фото";
            Recovery.Visibility = Visibility.Visible;
        }
        finally { if (_load == load) _load = null; }
    }

    private void Open_Click(object sender, RoutedEventArgs e)
    {
        if (DataContext is ChatMessage message && Window.GetWindow(this) is MainWindow window &&
            window.PhotoCacheScope is { } scope && Thumbnail.Source is System.Windows.Media.Imaging.BitmapSource thumbnail)
            new PhotoWindow(window.Photos, scope, message, thumbnail) { Owner = window }.ShowDialog();
    }

    private void Save_Click(object sender, RoutedEventArgs e)
    {
        if (DataContext is ChatMessage message && Window.GetWindow(this) is MainWindow window)
            window.SaveAttachment(message);
    }
}
