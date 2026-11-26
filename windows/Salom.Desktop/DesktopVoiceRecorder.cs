using System.IO;
using NAudio.Wave;

namespace Salom.Desktop;

internal sealed class DesktopVoiceRecorder
{
    private const int SampleRate = 44100;
    internal const int MaximumMs = 300000;
    private readonly string _wav;
    private readonly WaveFileWriter _writer;
    private readonly WaveInEvent _input;
    private readonly TaskCompletionSource _stopped = new(TaskCreationOptions.RunContinuationsAsynchronously);
    private readonly System.Diagnostics.Stopwatch _elapsed = new();
    private long _recordedBytes;
    private bool _finished;

    internal TimeSpan Elapsed => _elapsed.Elapsed;

    internal DesktopVoiceRecorder(string folder, int device)
    {
        if (WaveInEvent.DeviceCount == 0) throw new InvalidOperationException("Подключи микрофон и попробуй ещё раз.");
        Directory.CreateDirectory(folder);
        _wav = Path.Combine(folder, Guid.NewGuid().ToString("N") + ".wav");
        var format = new WaveFormat(SampleRate, 16, 1);
        _writer = new WaveFileWriter(_wav, format);
        _input = new WaveInEvent { DeviceNumber = device, WaveFormat = format, BufferMilliseconds = 50 };
        _input.DataAvailable += (_, args) =>
        {
            var maximumBytes = (long)MaximumMs * format.AverageBytesPerSecond / 1000;
            var length = (int)Math.Min(args.BytesRecorded, Math.Max(0, maximumBytes - _recordedBytes));
            if (length == 0) return;
            _writer.Write(args.Buffer, 0, length);
            _recordedBytes += length;
        };
        _input.RecordingStopped += (_, args) =>
        {
            if (args.Exception != null) _stopped.TrySetException(args.Exception);
            else _stopped.TrySetResult();
        };
        try { _input.StartRecording(); _elapsed.Start(); }
        catch { _input.Dispose(); _writer.Dispose(); File.Delete(_wav); throw; }
    }

    internal async Task<VoiceClip?> FinishAsync(string output, bool cancel)
    {
        if (_finished) return null;
        _finished = true;
        _elapsed.Stop();
        try
        {
            _input.StopRecording();
            await _stopped.Task.WaitAsync(TimeSpan.FromSeconds(5));
        }
        finally
        {
            _input.Dispose();
            _writer.Dispose();
            if (cancel || !_stopped.Task.IsCompletedSuccessfully) File.Delete(_wav);
        }
        if (cancel) return null;
        return await Task.Run(() =>
        {
            try { return Encode(_wav, output); }
            finally { File.Delete(_wav); }
        });
    }

    internal static VoiceClip? Encode(string input, string output)
    {
        using var reader = new WaveFileReader(input);
        var duration = Math.Min(MaximumMs, (int)reader.TotalTime.TotalMilliseconds);
        if (duration < 1000) return null;
        var waveform = ReadWaveform(reader);
        reader.Position = 0;
        MediaFoundationEncoder.EncodeToAac(reader, output, 64000);
        return new VoiceClip(duration, waveform);
    }

    private static int[] ReadWaveform(WaveFileReader reader)
    {
        const int buckets = 32;
        var peaks = new int[buckets];
        var buffer = new byte[3200];
        long offset = 0;
        for (int size; (size = reader.Read(buffer, 0, buffer.Length)) > 0; offset += size)
        {
            for (var index = 0; index + 1 < size; index += 2)
            {
                var bucket = (int)((offset + index) * buckets / reader.Length);
                var sample = BitConverter.ToInt16(buffer, index);
                peaks[bucket] = Math.Max(peaks[bucket], Math.Abs((int)sample) * 100 / 32768);
            }
        }
        return peaks;
    }
}
