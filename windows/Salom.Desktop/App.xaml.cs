using System.Diagnostics;
using System.Windows;
using Microsoft.Toolkit.Uwp.Notifications;

namespace Salom.Desktop;

public partial class App : System.Windows.Application
{
    private const string BackgroundSwitch = "--background-notifications";
    private const string PeerSwitch = "--open-peer";
    private DesktopInstance? _instance;
    private bool _background;
    private bool _continueInBackground;
    private bool _openingFromBackground;
    private bool _sessionEnding;
    private Guid? _pendingPeer;
    private TaskCompletionSource<Guid>? _coldActivation;

    protected override async void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);
        try
        {
            var executable = Environment.ProcessPath ?? throw new InvalidOperationException("Missing application path.");
            _background = e.Args.SequenceEqual(new[] { BackgroundSwitch });
            var activatedByToast = !_background && ToastNotificationManagerCompat.WasCurrentProcessToastActivated();
            if (activatedByToast) _coldActivation = new();
            try { ToastNotificationManagerCompat.OnActivated += ToastActivated; }
            catch (Exception error) { Trace.TraceWarning("SALOM toast registration: {0}", error.GetType().Name); }

            if (_background)
            {
                _instance = DesktopInstance.Acquire(executable, foreground: false);
                if (_instance != null) await BackgroundNotifications.RunAsync(_instance.StopBackground);
                Shutdown();
                return;
            }

            if (activatedByToast && _coldActivation is { } activation)
                _pendingPeer = await activation.Task.WaitAsync(TimeSpan.FromSeconds(5));
            if (e.Args.Length == 2 && e.Args[0] == PeerSwitch && Guid.TryParse(e.Args[1], out var peer))
                _pendingPeer = peer;

            _instance = DesktopInstance.Acquire(executable, foreground: true);
            if (_instance == null) { Shutdown(); return; }
            var window = new MainWindow();
            MainWindow = window;
            window.Closed += (_, _) =>
            {
                _continueInBackground = !_sessionEnding && window.ShouldContinueNotifications;
                Shutdown();
            };
            if (_pendingPeer is { } initialPeer) window.ActivateNotification(initialPeer);
            window.Show();
        }
        catch (Exception error)
        {
            Trace.TraceWarning("SALOM startup: {0}", error.GetType().Name);
            Shutdown(1);
        }
    }

    private void ToastActivated(ToastNotificationActivatedEventArgsCompat activation)
    {
        var args = ToastArguments.Parse(activation.Argument);
        if (!args.TryGetValue("peer", out var value) || !Guid.TryParse(value, out var peer)) return;
        _ = Dispatcher.InvokeAsync(() =>
        {
            if (_background)
            {
                if (_openingFromBackground || string.IsNullOrWhiteSpace(SessionVault.Read().Token)) return;
                _openingFromBackground = StartProcess(PeerSwitch, peer.ToString());
            }
            else if (MainWindow is MainWindow window) window.ActivateNotification(peer);
            else if (_coldActivation is { } cold) cold.TrySetResult(peer);
            else _pendingPeer = peer;
        });
    }

    protected override void OnSessionEnding(SessionEndingCancelEventArgs e)
    {
        _sessionEnding = true;
        base.OnSessionEnding(e);
    }

    protected override void OnExit(ExitEventArgs e)
    {
        ToastNotificationManagerCompat.OnActivated -= ToastActivated;
        if (_continueInBackground) _instance?.StopBackground.Reset();
        _instance?.Dispose();
        if (_continueInBackground) StartProcess(BackgroundSwitch);
        base.OnExit(e);
    }

    private static bool StartProcess(params string[] args)
    {
        try
        {
            var start = new ProcessStartInfo(Environment.ProcessPath!) { UseShellExecute = false, CreateNoWindow = true };
            foreach (var arg in args) start.ArgumentList.Add(arg);
            using var process = Process.Start(start);
            return process != null;
        }
        catch (Exception error) when (error is System.ComponentModel.Win32Exception or InvalidOperationException)
        {
            Trace.TraceWarning("SALOM background launch: {0}", error.GetType().Name);
            return false;
        }
    }
}
