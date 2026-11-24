using System.IO.Compression;
using System.Net;
using System.Security.Cryptography;
using System.Text.Json;
using Salom.Desktop;
using Salom.Setup;

if (!ComposerInputPolicy.ShouldSend(enterPressed: true, shiftPressed: false)) throw new Exception("Enter must send");
if (ComposerInputPolicy.ShouldSend(enterPressed: true, shiftPressed: true)) throw new Exception("Shift+Enter must insert a newline");
if (ComposerInputPolicy.ShouldSend(enterPressed: false, shiftPressed: false)) throw new Exception("Other keys must not send");
Console.WriteLine("Composer keyboard policy: 3 checks passed");

if (DesktopNotificationPolicy.ShouldNotify(true, false, false, false, true)) throw new Exception("Own messages must stay silent");
if (DesktopNotificationPolicy.ShouldNotify(false, true, true, true, false)) throw new Exception("The visible conversation needs no notification");
if (!DesktopNotificationPolicy.ShouldNotify(false, true, true, true, true)) throw new Exception("Minimized windows need notifications");
if (!DesktopNotificationPolicy.ShouldNotify(false, true, false, false, false)) throw new Exception("The tray needs notifications");
if (!DesktopNotificationPolicy.ShouldNotify(false, true, true, false, false)) throw new Exception("An inactive window needs notifications");
Console.WriteLine("Desktop notification policy: 5 checks passed");

var recent = new RecentNotificationIds();
if (recent.Add(0) || !recent.Add(1) || recent.Add(1)) throw new Exception("Notification deduplication failed");
for (var i = 2; i <= 513; i++) recent.Add(i);
if (recent.Add(513) || !recent.Add(1)) throw new Exception("The deduplication cache must be bounded");
Console.WriteLine("Notification replay: malformed IDs and duplicates suppressed; cache bounded");

if (OperatingSystem.IsWindows())
{
    var installation = Path.Combine(Path.GetTempPath(), "SALOM-policy-" + Guid.NewGuid(), "Salom.Desktop.exe");
    using var backgroundReady = new ManualResetEventSlim();
    Exception? backgroundError = null;
    var background = new Thread(() =>
    {
        try
        {
            using var worker = DesktopInstance.Acquire(installation, foreground: false)
                ?? throw new Exception("Background must acquire an idle installation");
            backgroundReady.Set();
            if (!worker.StopBackground.WaitOne(TimeSpan.FromSeconds(5)))
                throw new Exception("Foreground launch must stop the background worker");
        }
        catch (Exception error) { backgroundError = error; }
    }) { IsBackground = true };
    background.Start();
    if (!backgroundReady.Wait(TimeSpan.FromSeconds(5))) throw new Exception("Background did not start", backgroundError);
    using (var foreground = DesktopInstance.Acquire(installation, foreground: true)
           ?? throw new Exception("Foreground must acquire the lease once background stops"))
    {
        if (!background.Join(TimeSpan.FromSeconds(2)) || backgroundError != null)
            throw new Exception("Background did not hand off its lease", backgroundError);
        var secondWorker = Task.Run(() =>
        {
            using var duplicate = DesktopInstance.Acquire(installation, foreground: false);
            return duplicate != null;
        });
        if (secondWorker.GetAwaiter().GetResult()) throw new Exception("Foreground and background must not overlap");
        foreground.StopBackground.Reset();
    }
    using var restarted = DesktopInstance.Acquire(installation, foreground: false);
    if (restarted is null || restarted.StopBackground.WaitOne(0)) throw new Exception("Background must restart after window close");
    Console.WriteLine("Windows notification lifecycle: background stop, exclusive ownership and restart passed");
}

var downloadRoot = Directory.CreateTempSubdirectory("salom-update-test-");
try
{
    var payload = new byte[1_000_001];
    payload[0] = 91;
    byte[] Manifest(string filename, string digest) => JsonSerializer.SerializeToUtf8Bytes(new
    {
        version = "0.99.0",
        windowsFile = filename,
        windowsSize = payload.Length,
        windowsSha256 = digest
    });
    const string file = "SALOM-0.16.2-Release-Setup.exe";
    var digest = Convert.ToHexString(SHA256.HashData(payload));
    var manifest = Manifest(file, digest);
    using var http = new HttpClient(new StubUpdateHttp(request =>
    {
        if (request.RequestUri == new Uri(DesktopUpdates.Origin, "version.json"))
            return new HttpResponseMessage(HttpStatusCode.OK) { Content = new ByteArrayContent(manifest) };
        if (request.RequestUri == new Uri(DesktopUpdates.Origin, file))
            return new HttpResponseMessage(HttpStatusCode.OK) { Content = new ByteArrayContent(payload) };
        throw new Exception("Unexpected request: " + request.RequestUri);
    }));
    var updates = new DesktopUpdates(http);
    var release = await updates.CheckAsync(CancellationToken.None);
    if (release.Version != new Version(0, 16, 2)) throw new Exception("Use Windows version independently of Android");
    var stable = new DesktopBuildVersion(release.Version, "0.16.2+commit");
    var preview = new DesktopBuildVersion(release.Version, "0.16.2-qa+commit");
    if (!stable.IsNewerThan(preview) || preview.IsNewerThan(stable) || stable.IsNewerThan(stable) ||
        stable.IsNewerThan(new DesktopBuildVersion(new Version(0, 16, 3), null)))
        throw new Exception("The stable release must upgrade QA without downgrading or reinstalling current");
    foreach (var badName in new[] { "../"+file, "https://example.com/"+file, file + "?ignored=1" })
    {
        try { DesktopUpdates.Parse(Manifest(badName, digest)); throw new Exception("An invalid installer filename was accepted"); }
        catch (InvalidDataException) { }
    }
    var progress = new TestProgress();
    var installer = await updates.DownloadAsync(release, downloadRoot.FullName, progress, CancellationToken.None);
    if (!File.ReadAllBytes(installer).SequenceEqual(payload) || progress.Value != 100) throw new Exception("Verified download did not finish");
    try
    {
        await updates.DownloadAsync(release with { Sha256 = new byte[32] }, downloadRoot.FullName, progress, CancellationToken.None);
        throw new Exception("Bad checksum was accepted");
    }
    catch (InvalidDataException) { }
    if (downloadRoot.EnumerateFiles("*.part").Any() || !File.ReadAllBytes(installer).SequenceEqual(payload))
        throw new Exception("A failed download replaced a verified installer or left a partial file");
    Console.WriteLine("Windows updater: Windows-version selection, safe URLs, download and SHA-256 passed");

    var installed = Directory.CreateDirectory(Path.Combine(downloadRoot.FullName, "application")).FullName;
    var executable = Path.Combine(installed, "Salom.Desktop.exe");
    var asset = Path.Combine(installed, "call.html");
    var profile = Path.Combine(downloadRoot.FullName, "session.bin");
    File.WriteAllText(executable, "previous");
    File.WriteAllText(asset, "previous");
    File.WriteAllText(profile, "keep");
    using var storage = new MemoryStream();
    using (var zip = new ZipArchive(storage, ZipArchiveMode.Create, leaveOpen: true))
    {
        foreach (var name in new[] { "call.html", "Salom.Desktop.exe" })
        {
            using var writer = new StreamWriter(zip.CreateEntry(name).Open());
            writer.Write("updated");
        }
    }
    storage.Position = 0;
    using var updateZip = new ZipArchive(storage, ZipArchiveMode.Read);
    if (OperatingSystem.IsWindows())
    {
        using (File.OpenRead(executable))
        {
            try { InstallPayload.Apply(updateZip, installed); throw new Exception("Setup overwrote a locked executable"); }
            catch (IOException) { }
        }
        if (File.ReadAllText(executable) != "previous" || File.ReadAllText(asset) != "previous")
            throw new Exception("Failed installation did not roll back the files");
    }
    InstallPayload.Apply(updateZip, installed);
    if (File.ReadAllText(executable) != "updated" || File.ReadAllText(asset) != "updated" || File.ReadAllText(profile) != "keep")
        throw new Exception("An update did not replace files in place or touched the separate profile");
    Console.WriteLine("Windows Setup: in-place replacement, locked-file rollback and retained profile passed");
}
finally { downloadRoot.Delete(recursive: true); }

sealed class TestProgress : IProgress<int>
{
    public int Value { get; private set; }
    public void Report(int value) => Value = value;
}

sealed class StubUpdateHttp(Func<HttpRequestMessage, HttpResponseMessage> respond) : HttpMessageHandler
{
    protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        => Task.FromResult(respond(request));
}
