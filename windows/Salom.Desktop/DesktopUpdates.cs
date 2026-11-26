using System.IO;
using System.Net;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Security.Cryptography;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace Salom.Desktop;

internal sealed record DesktopRelease(Version Version, string FileName, long Size, byte[] Sha256)
{
    internal Uri DownloadUri => new(DesktopUpdates.Origin, FileName);
}

internal sealed class DesktopUpdates(HttpClient http)
{
    internal static readonly Uri Origin = new("https://mukimov.site/salom/");
    private static readonly HttpClient OfficialHttp = new(new HttpClientHandler
    {
        AllowAutoRedirect = false,
        UseCookies = false
    }) { Timeout = TimeSpan.FromSeconds(30) };
    internal static readonly DesktopUpdates Official = new(OfficialHttp);

    internal async Task<DesktopRelease> CheckAsync(CancellationToken cancellation)
    {
        using var request = new HttpRequestMessage(HttpMethod.Get, new Uri(Origin, "version.json"));
        request.Headers.CacheControl = new CacheControlHeaderValue { NoCache = true, NoStore = true };
        using var response = await http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, cancellation);
        if (response.StatusCode != HttpStatusCode.OK) throw new HttpRequestException("Сервер обновлений недоступен.");
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(cancellation);
        deadline.CancelAfter(TimeSpan.FromSeconds(20));
        using var input = await response.Content.ReadAsStreamAsync(deadline.Token);
        var buffer = new byte[32 * 1024 + 1];
        var length = await input.ReadAtLeastAsync(buffer.AsMemory(), buffer.Length, false, deadline.Token);
        if (length == buffer.Length) throw new InvalidDataException("Манифест обновлений слишком большой.");
        return Parse(buffer.AsMemory(0, length));
    }

    internal static DesktopRelease Parse(ReadOnlyMemory<byte> json)
    {
        using var document = JsonDocument.Parse(json);
        var root = document.RootElement;
        var filename = root.GetProperty("windowsFile").GetString() ?? "";
        var match = Regex.Match(filename, @"\ASALOM-(\d{1,5}\.\d{1,5}\.\d{1,5})-(?:Release|CallFix|AllowCookie)-Setup\.exe\z",
            RegexOptions.CultureInvariant | RegexOptions.NonBacktracking);
        var hash = root.GetProperty("windowsSha256").GetString() ?? "";
        var size = root.GetProperty("windowsSize").GetInt64();
        if (!match.Success || !Version.TryParse(match.Groups[1].Value, out var version) ||
            size is < 1_000_000 or > 512_000_000 || hash.Length != 64 || !hash.All(Uri.IsHexDigit))
            throw new InvalidDataException("Сервер вернул неверные сведения об обновлении Windows.");
        return new DesktopRelease(version, filename, size, Convert.FromHexString(hash));
    }

    internal async Task<string> DownloadAsync(DesktopRelease release, string folder, IProgress<int> progress, CancellationToken cancellation)
    {
        Directory.CreateDirectory(folder);
        var key = Convert.ToHexString(release.Sha256);
        var installer = Path.Combine(folder, $"SALOM-update-{key}.exe");
        var partial = installer + ".part";
        if (File.Exists(installer) && await MatchesAsync(installer, release, cancellation))
        {
            progress.Report(100);
            return installer;
        }

        var offset = File.Exists(partial) ? new FileInfo(partial).Length : 0;
        if (offset > release.Size)
        {
            File.Delete(partial);
            offset = 0;
        }
        if (offset < release.Size)
        {
            using var request = new HttpRequestMessage(HttpMethod.Get, release.DownloadUri);
            request.Headers.AcceptEncoding.ParseAdd("identity");
            if (offset > 0) request.Headers.Range = new RangeHeaderValue(offset, null);
            using var response = await http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, cancellation);
            switch (response.StatusCode)
            {
                case HttpStatusCode.OK:
                    offset = 0;
                    break;
                case HttpStatusCode.PartialContent:
                    var range = response.Content.Headers.ContentRange;
                    if (range?.Unit != "bytes" || range.From != offset || range.To != release.Size - 1 || range.Length != release.Size)
                        throw new InvalidDataException("Сервер вернул неверный диапазон обновления.");
                    break;
                default:
                    throw new HttpRequestException("Скачать обновление не удалось.");
            }
            if (response.Content.Headers.ContentEncoding.Any(value => !value.Equals("identity", StringComparison.OrdinalIgnoreCase))
                || response.Content.Headers.ContentLength is { } size && size != release.Size - offset)
                throw new InvalidDataException("Размер скачиваемого обновления не совпадает с указанным.");

            await using var input = await response.Content.ReadAsStreamAsync(cancellation);
            using var deadline = CancellationTokenSource.CreateLinkedTokenSource(cancellation);
            var buffer = new byte[64 * 1024];
            var lastProgress = (int)(offset * 100 / release.Size);
            progress.Report(lastProgress);
            await using var output = new FileStream(partial, offset > 0 ? FileMode.Append : FileMode.Create,
                FileAccess.Write, FileShare.None, buffer.Length, useAsync: true);
            while (offset < release.Size)
            {
                deadline.CancelAfter(TimeSpan.FromSeconds(35));
                var count = await input.ReadAsync(buffer.AsMemory(0, (int)Math.Min(buffer.Length, release.Size - offset)), deadline.Token);
                if (count == 0) throw new HttpRequestException("Скачивание прервалось. Повтори, чтобы продолжить.");
                await output.WriteAsync(buffer.AsMemory(0, count), cancellation);
                offset += count;
                var percent = (int)(offset * 100 / release.Size);
                if (percent != lastProgress) { progress.Report(percent); lastProgress = percent; }
            }
        }
        if (!await MatchesAsync(partial, release, cancellation))
        {
            File.Delete(partial);
            throw new InvalidDataException("Проверка SHA-256 не прошла. Скачай обновление заново.");
        }
        cancellation.ThrowIfCancellationRequested();
        File.Move(partial, installer, overwrite: true);
        progress.Report(100);
        foreach (var old in Directory.EnumerateFiles(folder, "SALOM-update*"))
            if (!old.Equals(installer, StringComparison.OrdinalIgnoreCase))
                try { File.Delete(old); } catch (IOException) { } catch (UnauthorizedAccessException) { }
        return installer;
    }

    private static async Task<bool> MatchesAsync(string path, DesktopRelease release, CancellationToken cancellation)
    {
        await using var input = File.OpenRead(path);
        return input.Length == release.Size && CryptographicOperations.FixedTimeEquals(
            await SHA256.HashDataAsync(input, cancellation), release.Sha256);
    }
}