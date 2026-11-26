using System.IO;
using System.Windows.Media.Imaging;
using DataFormats = System.Windows.DataFormats;

namespace Salom.Desktop;

internal static class ClipboardPhoto
{
    private const int MaxEdge = 4096;
    private const long MaxPixels = 48_000_000;

    internal static BitmapSource? Read(System.Windows.IDataObject data)
    {
        if (data.GetDataPresent("PNG", false))
        {
            using var png = data.GetData("PNG", false) switch
            {
                Stream stream => stream,
                byte[] bytes => new MemoryStream(bytes, writable: false),
                _ => null,
            };
            if (png is { CanSeek: true, Length: > 0 and <= DesktopClient.MaxAttachmentBytes })
            {
                png.Position = 0;
                var decoder = new PngBitmapDecoder(png, BitmapCreateOptions.PreservePixelFormat, BitmapCacheOption.OnDemand);
                return Prepare(decoder.Frames[0]);
            }
        }

        if (data.GetData(DataFormats.Bitmap, true) is BitmapSource bitmap)
            return Prepare(bitmap);
        if (data.GetData(DataFormats.FileDrop, false) is string[] { Length: 1 } files &&
            PhotoDecoder.IsPhoto(null, files[0]))
        {
            var file = new FileInfo(files[0]);
            if (file.Length > DesktopClient.MaxAttachmentBytes)
                throw new InvalidDataException("Файл фото должен быть размером до 25 МБ.");
            return PhotoDecoder.Decode(file.FullName, MaxEdge);
        }
        return null;
    }

    private static BitmapSource Prepare(BitmapSource bitmap)
    {
        if ((long)bitmap.PixelWidth * bitmap.PixelHeight > MaxPixels)
            throw new InvalidDataException("Слишком большое фото. Уменьши его и попробуй снова.");
        var scale = Math.Min(1d, MaxEdge / (double)Math.Max(bitmap.PixelWidth, bitmap.PixelHeight));
        BitmapSource source = scale == 1d ? bitmap : new TransformedBitmap(bitmap, new System.Windows.Media.ScaleTransform(scale, scale));
        // Detach from the clipboard stream before it closes and before sending on a worker thread.
        var frame = new WriteableBitmap(source);
        frame.Freeze();
        return frame;
    }

    internal static string Save(BitmapSource bitmap, string directory, Guid id)
    {
        Directory.CreateDirectory(directory);
        var path = Path.Combine(directory, $"photo_{id:N}.png");
        var output = new FileStream(path, FileMode.CreateNew, FileAccess.Write);
        try
        {
            using (output)
            {
                var png = new PngBitmapEncoder();
                png.Frames.Add(BitmapFrame.Create(bitmap));
                png.Save(output);
                if (output.Length > DesktopClient.MaxAttachmentBytes)
                    throw new InvalidDataException("Фото весит больше 25 МБ. Уменьши его и попробуй снова.");
            }
            return path;
        }
        catch
        {
            if (File.Exists(path)) File.Delete(path);
            throw;
        }
    }
}
