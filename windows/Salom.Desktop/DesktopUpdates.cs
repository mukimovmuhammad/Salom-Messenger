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
        var partial = Path.Combine(folder, Guid.NewGuid().ToString("N") + ".part");
        var installer = Path.Combine(folder, "SALOM-update.exe");
        try
        {
            using var response = await http.GetAsync(release.DownloadUri, HttpCompletionOption.ResponseHeadersRead, cancellation);
            if (response.StatusCode != HttpStatusCode.OK) throw new HttpRequestException("Скачать обновление не удалось.");
            if (response.Content.Headers.ContentLength is { } size && size != release.Size)
                throw new InvalidDataException("Размер скачиваемого обновления не совпадает с указанным.");

            await using var input = await response.Content.ReadAsStreamAsync(cancellation);
            using var deadline = CancellationTokenSource.CreateLinkedTokenSource(cancellation);
            using var hash = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
            var buffer = new byte[64 * 1024];
            var lastProgress = -1;
            long total = 0;
            await using (var output = new FileStream(partial, FileMode.CreateNew, FileAccess.Write, FileShare.None, buffer.Length, useAsync: true))
            {
                while (true)
                {
                    deadline.CancelAfter(TimeSpan.FromSeconds(35));
                    var count = await input.ReadAsync(buffer, deadline.Token);
                    if (count == 0) break;
                    total += count;
                    if (total > release.Size) throw new InvalidDataException("Файл обновления больше указанного.");
                    hash.AppendData(buffer, 0, count);
                    await output.WriteAsync(buffer.AsMemory(0, count), cancellation);
                    var percent = (int)(total * 100 / release.Size);
                    if (percent != lastProgress) { progress.Report(percent); lastProgress = percent; }
                }
            }
            if (total != release.Size || !CryptographicOperations.FixedTimeEquals(hash.GetHashAndReset(), release.Sha256))
                throw new InvalidDataException("Проверка SHA-256 не прошла. Установщик не будет запущен.");
            cancellation.ThrowIfCancellationRequested();
            File.Move(partial, installer, overwrite: true);
            return installer;
        }
        finally
        {
            try { File.Delete(partial); }
            catch (IOException) { }
            catch (UnauthorizedAccessException) { }
        }
    }
}
