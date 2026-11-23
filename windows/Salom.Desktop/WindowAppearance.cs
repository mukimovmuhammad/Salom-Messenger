using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Interop;

namespace Salom.Desktop;

internal static class WindowAppearance
{
    // The native frame retains Windows snap, resize, system menu and DPI behavior.
    internal static void UseDarkCaption(Window window)
    {
        window.SourceInitialized += (_, _) =>
        {
            if (!OperatingSystem.IsWindowsVersionAtLeast(10, 0, 22000)) return;
            var enabled = 1;
            DwmSetWindowAttribute(new WindowInteropHelper(window).Handle, 20, ref enabled, sizeof(int));
        };
    }

    [DllImport("dwmapi.dll", ExactSpelling = true)]
    private static extern int DwmSetWindowAttribute(nint hwnd, int attribute, ref int value, int size);
}
