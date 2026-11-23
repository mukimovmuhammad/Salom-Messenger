using System.IO;
using System.Security.Cryptography;
using System.Text;
using System.Windows.Media.Imaging;

namespace Salom.Desktop;

internal sealed class PhotoCache(string root,
    Func<string, string, string, IProgress<int>, CancellationToken, Task> download)
{
    private readonly SemaphoreSlim _transfers = new(3);
    private readonly SemaphoreSlim _decodes = new(2);
    private readonly SemaphoreSlim[] _files = Enumerable.Range(0, 32).Select(_ => new SemaphoreSlim(1)).ToArray();
    private readonly object _pruneGate = new();

    internal const int ThumbnailEdge = 460;
    internal const int ViewerEdge = 2560;

    internal Task<BitmapSource> LoadAsync(string scope, string id, int edge, IProgress<int> progress, CancellationToken token) =>
        UseAsync(scope, id, progress, token, async path =>
        {
            await _decodes.WaitAsync(token).ConfigureAwait(false);
            try { return await Task.Run(() => PhotoDecoder.Decode(path, edge), token).ConfigureAwait(false); }
            finally { _decodes.Release(); }
        });

    internal Task SaveAsync(string scope, string id, string destination, IProgress<int> progress, CancellationToken token) =>
        UseAsync(scope, id, progress, token, async path =>
        {
            var temporary = destination + "." + Guid.NewGuid().ToString("N") + ".part";
            try
            {
                await using (var input = File.OpenRead(path))
                await using (var output = new FileStream(temporary, FileMode.CreateNew, FileAccess.Write, FileShare.None, 65536, true))
                    await input.CopyToAsync(output, token).ConfigureAwait(false);
                token.ThrowIfCancellationRequested();
                File.Move(temporary, destination, overwrite: true);
            }
            finally { File.Delete(temporary); }
            return true;
        });

    private async Task<T> UseAsync<T>(string scope, string id, IProgress<int> progress, CancellationToken token, Func<string, Task<T>> use)
    {
        if (string.IsNullOrWhiteSpace(scope) || !Guid.TryParse(id, out var guid))
            throw new InvalidOperationException("Вложение недоступно.");
        var folder = Path.Combine(root, Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(scope))));
        var path = Path.Combine(folder, guid.ToString("N"));
        var gate = Gate(path);
        await gate.WaitAsync(token).ConfigureAwait(false);
        try
        {
            await Task.Run(() => Directory.CreateDirectory(folder), token).ConfigureAwait(false);
            if (!File.Exists(path))
            {
                await _transfers.WaitAsync(token).ConfigureAwait(false);
                try { await download(scope, id, path, progress, token).ConfigureAwait(false); }
                finally { _transfers.Release(); }
                await Task.Run(() => Prune(path), token).ConfigureAwait(false);
            }
            try
            {
                var result = await use(path).ConfigureAwait(false);
                try { File.SetLastWriteTimeUtc(path, DateTime.UtcNow); } catch (IOException) { }
                return result;
            }
            catch (InvalidDataException)
            {
                File.Delete(path);
                throw;
            }
        }
        finally { gate.Release(); }
    }

    private SemaphoreSlim Gate(string path) => _files[(StringComparer.Ordinal.GetHashCode(path) & int.MaxValue) % _files.Length];

    private void Prune(string keep)
    {
        lock (_pruneGate)
        {
            try
            {
                var oldest = new DirectoryInfo(root).EnumerateFiles("*", SearchOption.AllDirectories)
                    .Where(file => Guid.TryParseExact(file.Name, "N", out _))
                    .OrderBy(file => file.LastWriteTimeUtc).ToArray();
                var total = oldest.Sum(file => file.Exists ? file.Length : 0);
                var count = oldest.Length;
                foreach (var file in oldest)
                {
                    if (total <= 128L * 1024 * 1024 && count <= 400) break;
                    var gate = Gate(file.FullName);
                    if (file.FullName == keep || !gate.Wait(0)) continue;
                    try
                    {
                        if (!file.Exists) continue;
                        var length = file.Length;
                        file.Delete();
                        total -= length;
                        count--;
                    }
                    catch (IOException) { }
                    finally { gate.Release(); }
                }
            }
            catch (IOException) { }
            catch (UnauthorizedAccessException) { }
        }
    }
}
