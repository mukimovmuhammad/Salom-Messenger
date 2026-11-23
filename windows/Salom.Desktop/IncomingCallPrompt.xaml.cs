using System.IO;
using System.Media;
using System.Windows;
using System.Windows.Threading;

namespace Salom.Desktop;

public partial class IncomingCallPrompt : Window
{
    private readonly DispatcherTimer _expiry;
    private SoundPlayer? _ringer;
    private Stream? _ringStream;
    internal bool Expired { get; private set; }

    internal IncomingCallPrompt(string name, bool video, TimeSpan remaining, bool ring)
    {
        InitializeComponent();
        WindowAppearance.UseDarkCaption(this);
        CallKind.Text = video ? "Входящий видеозвонок" : "Входящий аудиозвонок";
        CallName.Text = name;
        CallPermission.Text = video ? "При ответе включатся микрофон и камера." : "При ответе включится микрофон.";
        _expiry = new DispatcherTimer { Interval = remaining };
        _expiry.Tick += (_, _) => EndRemote();
        Loaded += (_, _) =>
        {
            _expiry.Start();
            if (ring) StartRinging();
        };
        Closed += (_, _) => { _expiry.Stop(); StopRinging(); };
    }

    private void StartRinging()
    {
        if (_ringer != null) return;
        try
        {
            var sound = System.Windows.Application.GetResourceStream(new Uri("pack://application:,,,/assets/incoming-call.wav"));
            if (sound == null) return;
            _ringStream = sound.Stream;
            _ringer = new SoundPlayer(_ringStream);
            _ringer.PlayLooping();
        }
        catch (Exception error)
        {
            System.Diagnostics.Trace.TraceWarning("SALOM ringtone failed: {0}", error.GetType().Name);
            StopRinging();
        }
    }

    private void StopRinging()
    {
        _ringer?.Stop();
        _ringer?.Dispose();
        _ringer = null;
        _ringStream?.Dispose();
        _ringStream = null;
    }

    internal void EndRemote()
    {
        Expired = true;
        StopRinging();
        Close();
    }

    private void Accept_Click(object sender, RoutedEventArgs e)
    {
        StopRinging();
        DialogResult = true;
    }
}
