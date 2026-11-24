namespace Salom.Desktop;

internal readonly record struct DesktopBuildVersion(Version Number, string? ProductVersion)
{
    internal string Display => ProductVersion?.Split('+', 2)[0] ?? Number.ToString();

    internal bool IsNewerThan(DesktopBuildVersion installed) =>
        Number > installed.Number || (Number == installed.Number && !Display.Contains('-') && installed.Display.Contains('-'));
}
