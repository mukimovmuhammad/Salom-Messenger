using System.IO;
using System.IO.Compression;

namespace Salom.Setup;

internal static class InstallPayload
{
    internal static void Apply(ZipArchive archive, string installPath)
    {
        var root = Path.TrimEndingDirectorySeparator(Path.GetFullPath(installPath));
        var parent = Directory.GetParent(root) ?? throw new InvalidDataException("Неверная папка установки.");
        var stage = Directory.CreateDirectory(Path.Combine(parent.FullName, ".salom-install-" + Guid.NewGuid().ToString("N"))).FullName;
        var payload = Path.Combine(stage, "payload");
        var applied = new List<(string Destination, string? Backup)>();
        var preserveBackup = false;
        try
        {
            var files = new List<string>();
            foreach (var entry in archive.Entries)
            {
                if (string.IsNullOrEmpty(entry.Name)) continue;
                var staged = Path.GetFullPath(Path.Combine(payload, entry.FullName));
                if (!staged.StartsWith(payload + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase) ||
                    entry.FullName.Contains(':'))
                    throw new InvalidDataException("Недопустимый путь внутри установочного архива.");
                Directory.CreateDirectory(Path.GetDirectoryName(staged)!);
                entry.ExtractToFile(staged);
                files.Add(staged);
            }
            if (!File.Exists(Path.Combine(payload, "Salom.Desktop.exe")))
                throw new InvalidDataException("В установочном архиве нет приложения.");

            foreach (var path in files.OrderBy(file => Path.GetFileName(file).Equals("Salom.Desktop.exe", StringComparison.OrdinalIgnoreCase)))
            {
                var destination = Path.Combine(root, Path.GetRelativePath(payload, path));
                Directory.CreateDirectory(Path.GetDirectoryName(destination)!);
                var backup = File.Exists(destination) ? Path.Combine(stage, $"{applied.Count}.bak") : null;
                RetrySharingViolation(() =>
                {
                    if (backup == null) File.Move(path, destination);
                    else File.Replace(path, destination, backup, ignoreMetadataErrors: true);
                });
                applied.Add((destination, backup));
            }
        }
        catch (Exception installError)
        {
            var rollbackErrors = new List<Exception>();
            foreach (var (destination, backup) in applied.AsEnumerable().Reverse())
            {
                try
                {
                    RetrySharingViolation(() =>
                    {
                        if (backup == null) File.Delete(destination);
                        else File.Move(backup, destination, overwrite: true);
                    });
                }
                catch (Exception error) when (error is IOException or UnauthorizedAccessException) { rollbackErrors.Add(error); }
            }
            if (rollbackErrors.Count == 0) throw;
            preserveBackup = true;
            rollbackErrors.Insert(0, installError);
            throw new AggregateException($"Не удалось полностью восстановить файлы. Резервная копия: {stage}", rollbackErrors);
        }
        finally
        {
            if (!preserveBackup && Directory.Exists(stage))
            {
                try { Directory.Delete(stage, recursive: true); }
                catch (IOException) { }
                catch (UnauthorizedAccessException) { }
            }
        }
    }

    private static void RetrySharingViolation(Action action)
    {
        for (var attempt = 0; ; attempt++)
        {
            try { action(); return; }
            catch (IOException error) when ((error.HResult & 0xffff) is 32 or 33 && attempt < 6)
            {
                Thread.Sleep(150);
            }
        }
    }
}
