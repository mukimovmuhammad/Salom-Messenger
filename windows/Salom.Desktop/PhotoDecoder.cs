using System.IO;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using SkiaSharp;

namespace Salom.Desktop;

internal static class PhotoDecoder
{
    internal static bool IsPhoto(string? mime, string? name)
    {
        var type = mime?.Split(';', 2)[0].Trim().ToLowerInvariant();
        if (type is "image/jpeg" or "image/jpg" or "image/png" or "image/webp" or "image/gif") return true;
        if (type is not (null or "" or "application/octet-stream" or "binary/octet-stream")) return false;
        return Path.GetExtension(name)?.ToLowerInvariant() is ".jpg" or ".jpeg" or ".png" or ".webp" or ".gif";
    }

    internal static BitmapSource Decode(string path, int maxEdge)
    {
        using var codec = SKCodec.Create(path);
        if (codec == null || codec.EncodedFormat is not
            (SKEncodedImageFormat.Jpeg or SKEncodedImageFormat.Png or SKEncodedImageFormat.Webp or SKEncodedImageFormat.Gif))
            throw new InvalidDataException("Файл не является поддерживаемым изображением.");

        var dimensions = codec.Info;
        if (dimensions.Width < 1 || dimensions.Height < 1 || (long)dimensions.Width * dimensions.Height > 48_000_000)
            throw new InvalidDataException("Слишком большое изображение.");
        var scale = Math.Min(1f, maxEdge / (float)Math.Max(dimensions.Width, dimensions.Height));
        var nativeSize = codec.GetScaledDimensions(scale);
        var decodeInfo = new SKImageInfo(nativeSize.Width, nativeSize.Height, SKColorType.Bgra8888, SKAlphaType.Premul);
        using var bitmap = new SKBitmap(decodeInfo);
        if (codec.GetPixels(decodeInfo, bitmap.GetPixels()) != SKCodecResult.Success)
            throw new InvalidDataException("Изображение повреждено или загружено не полностью.");

        var targetInfo = new SKImageInfo(Math.Max(1, (int)Math.Round(dimensions.Width * scale)),
            Math.Max(1, (int)Math.Round(dimensions.Height * scale)), SKColorType.Bgra8888, SKAlphaType.Premul);
        using var scaled = bitmap.Width == targetInfo.Width && bitmap.Height == targetInfo.Height
            ? null : bitmap.Resize(targetInfo, new SKSamplingOptions(SKFilterMode.Linear));
        var pixels = scaled ?? bitmap;
        var image = BitmapSource.Create(pixels.Width, pixels.Height, 96, 96, PixelFormats.Pbgra32, null,
            pixels.GetPixels(), checked(pixels.RowBytes * pixels.Height), pixels.RowBytes);
        image.Freeze();
        return Orient(image, codec.EncodedOrigin);
    }

    private static BitmapSource Orient(BitmapSource image, SKEncodedOrigin orientation)
    {
        if (orientation == SKEncodedOrigin.TopLeft) return image;
        var transform = new TransformGroup();
        if (orientation is SKEncodedOrigin.TopRight or SKEncodedOrigin.LeftTop or SKEncodedOrigin.RightBottom)
            transform.Children.Add(new ScaleTransform(-1, 1));
        if (orientation == SKEncodedOrigin.BottomLeft) transform.Children.Add(new ScaleTransform(1, -1));
        var degrees = orientation switch
        {
            SKEncodedOrigin.BottomRight => 180,
            SKEncodedOrigin.RightTop or SKEncodedOrigin.RightBottom => 90,
            SKEncodedOrigin.LeftTop or SKEncodedOrigin.LeftBottom => -90,
            _ => 0
        };
        if (degrees != 0) transform.Children.Add(new RotateTransform(degrees));
        var result = new TransformedBitmap(image, transform);
        result.Freeze();
        return result;
    }
}
