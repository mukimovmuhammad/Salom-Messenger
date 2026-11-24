using System.IO;
using System.Security.Cryptography;
using System.Text;

namespace Salom.Desktop;

// The visible client, the notification process and Setup take turns using this installation.
internal sealed class DesktopInstance : IDisposable
{
    private readonly Mutex _lease;
    internal EventWaitHandle StopBackground { get; }

    private DesktopInstance(Mutex lease, EventWaitHandle stopBackground)
    {
        _lease = lease;
        StopBackground = stopBackground;
    }

    internal static DesktopInstance? Acquire(string executable, bool foreground)
    {
        var path = Path.GetFullPath(executable).ToUpperInvariant();
        var key = Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(path)));
        var stop = new EventWaitHandle(false, EventResetMode.ManualReset, @"Local\SALOM.BackgroundStop." + key);
        var lease = new Mutex(false, @"Local\SALOM.Installation." + key);
        var acquired = false;
        try
        {
            if (foreground) stop.Set();
            try { acquired = lease.WaitOne(foreground ? TimeSpan.FromSeconds(4) : TimeSpan.Zero); }
            catch (AbandonedMutexException) { acquired = true; }
            if (acquired) return new DesktopInstance(lease, stop);
            return null;
        }
        finally
        {
            if (!acquired) { lease.Dispose(); stop.Dispose(); }
        }
    }

    public void Dispose()
    {
        _lease.ReleaseMutex();
        _lease.Dispose();
        StopBackground.Dispose();
    }
}
