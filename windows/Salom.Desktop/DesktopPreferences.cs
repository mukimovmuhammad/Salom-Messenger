using System.IO;
using System.Text.Json;

namespace Salom.Desktop;

internal sealed class DesktopPreferences
{
    private static readonly string FilePath = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "SALOM", "desktop-preferences.json");

    public bool CloseToTray { get; set; }
    public bool IncomingRingtone { get; set; } = true;
    public bool PrivateNotificationPreview { get; set; }
    public int Microphone { get; set; } = -1;

    internal static DesktopPreferences Load()
    {
        try { return JsonSerializer.Deserialize<DesktopPreferences>(File.ReadAllText(FilePath)) ?? new(); }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException or JsonException) { return new(); }
    }

    internal bool TrySave()
    {
        var temporary = FilePath + "." + Guid.NewGuid().ToString("N");
        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(FilePath)!);
            File.WriteAllText(temporary, JsonSerializer.Serialize(this));
            File.Move(temporary, FilePath, overwrite: true);
            return true;
        }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException) { return false; }
        finally { try { File.Delete(temporary); } catch (IOException) { } catch (UnauthorizedAccessException) { } }
    }
}
