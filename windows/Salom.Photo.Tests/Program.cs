using System.IO;
using System.Windows.Media.Imaging;
using Microsoft.Data.Sqlite;
using NAudio.Wave;
using Salom.Desktop;
using SkiaSharp;

static void Check(bool value, string detail)
{
    if (!value) throw new Exception(detail);
}

var temporary = Directory.CreateTempSubdirectory("salom-photo-test-");
try
{
    using var bitmap = new SKBitmap(120, 60);
    bitmap.Erase(new SKColor(220, 33, 17));
    foreach (var format in new[] { SKEncodedImageFormat.Jpeg, SKEncodedImageFormat.Png, SKEncodedImageFormat.Webp })
    {
        // Extension is deliberately unrelated to the content.
        var path = Path.Combine(temporary.FullName, "photo-" + format + ".cache");
        using var data = bitmap.Encode(format, 95);
        using (var output = File.Create(path)) data.SaveTo(output);
        var image = PhotoDecoder.Decode(path, 40);
        var pixels = new byte[image.PixelWidth * image.PixelHeight * 4];
        image.CopyPixels(pixels, image.PixelWidth * 4, 0);
        Check(image.IsFrozen && image.PixelWidth == 40 && image.PixelHeight == 20, $"Unexpected {format} size");
        Check(pixels[2] > 180 && pixels[0] < 60 && pixels[3] == 255, $"Incorrect {format} decoded pixels");
    }

    var gif = Path.Combine(temporary.FullName, "photo-gif.cache");
    File.WriteAllBytes(gif, Convert.FromBase64String("R0lGODlhAQABAIAAAP8AAAAAACH5BAAAAAAALAAAAAABAAEAAAICRAEAOw=="));
    Check(PhotoDecoder.Decode(gif, 40).PixelWidth == 1, "GIF first frame did not decode");
    Check(PhotoDecoder.IsPhoto(null, "photo.WebP") && PhotoDecoder.IsPhoto("image/png", "photo.bin")
        && !PhotoDecoder.IsPhoto("video/mp4", "movie.jpg") && !PhotoDecoder.IsPhoto(null, "document.pdf"),
        "Classification must prefer the server MIME and support legacy filenames");

    Exception? clipboardError = null;
    var clipboardThread = new Thread(() =>
    {
        try
        {
            var copied = new System.Windows.DataObject();
            copied.SetData("PNG", File.ReadAllBytes(Path.Combine(temporary.FullName, "photo-Png.cache")));
            var draft = ClipboardPhoto.Read(copied);
            Check(draft is { IsFrozen: true, PixelWidth: 120, PixelHeight: 60 }, "Clipboard PNG could not be previewed");
            var output = ClipboardPhoto.Save(draft!, temporary.FullName, Guid.NewGuid());
            Check(PhotoDecoder.Decode(output, 120).PixelHeight == 60, "Queued PNG could not be decoded as a photo");

            var screenshot = new System.Windows.DataObject();
            screenshot.SetData(System.Windows.DataFormats.Bitmap, draft);
            Check(ClipboardPhoto.Read(screenshot)?.PixelWidth == 120, "Clipboard bitmap could not be previewed");
            var file = new System.Windows.DataObject();
            file.SetData(System.Windows.DataFormats.FileDrop, new[] { output });
            Check(ClipboardPhoto.Read(file)?.PixelWidth == 120, "Copied photo file could not be previewed");
            var text = new System.Windows.DataObject();
            text.SetText("Обычный текст");
            Check(ClipboardPhoto.Read(text) == null, "Text must keep its normal paste behavior");

            var body = new MessageLinks
            {
                Body = "Смотри (https://example.org/page), www.example.com и javascript:alert(1)",
            };
            var links = body.Inlines.OfType<System.Windows.Documents.Hyperlink>().Select(link => link.NavigateUri.AbsoluteUri).ToArray();
            Check(links.SequenceEqual(new[] { "https://example.org/page", "https://www.example.com/" }),
                "Message links must preserve ordinary URLs, punctuation and allowed schemes");
        }
        catch (Exception failure) { clipboardError = failure; }
    });
    clipboardThread.SetApartmentState(ApartmentState.STA);
    clipboardThread.Start();
    clipboardThread.Join();
    if (clipboardError != null) throw new Exception("Clipboard checks failed", clipboardError);
    Console.WriteLine("Clipboard checks passed: PNG, bitmap, copied file, text and PNG export.");

    const string owner = "test-api\nowner";
    var calls = 0;
    var corruptFirst = false;
    var cache = new PhotoCache(Path.Combine(temporary.FullName, "cache"),
        (scope, id, path, _, _) =>
        {
            Interlocked.Increment(ref calls);
            if (scope != owner) throw new UnauthorizedAccessException();
            if (corruptFirst) { corruptFirst = false; File.WriteAllText(path, "broken"); }
            else File.Copy(Path.Combine(temporary.FullName, "photo-Webp.cache"), path);
            return Task.CompletedTask;
        });
    var attachment = Guid.NewGuid().ToString();
    var progress = new Progress<int>();
    var images = await Task.WhenAll(
        cache.LoadAsync(owner, attachment, 40, progress, default),
        cache.LoadAsync(owner, attachment, 120, progress, default));
    Check(calls == 1 && images[1].PixelWidth == 120, "Concurrent preview and viewer should reuse original bytes");
    var saved = Path.Combine(temporary.FullName, "saved.webp");
    await cache.SaveAsync(owner, attachment, saved, progress, default);
    Check(calls == 1 && File.ReadAllBytes(saved).SequenceEqual(File.ReadAllBytes(Path.Combine(temporary.FullName, "photo-Webp.cache"))),
        "Save should copy the original");

    try { await cache.LoadAsync("test-api\nanother-account", attachment, 40, progress, default); throw new Exception("Cross-account cache hit"); }
    catch (UnauthorizedAccessException) { }

    corruptFirst = true;
    var retry = Guid.NewGuid().ToString();
    try { await cache.LoadAsync(owner, retry, 40, progress, default); throw new Exception("Corrupt image was accepted"); }
    catch (InvalidDataException) { }
    Check((await cache.LoadAsync(owner, retry, 40, progress, default)).PixelWidth == 40 && calls == 4,
        "Retry should redownload a corrupt file");

    Console.WriteLine("Photo checks passed: JPEG, PNG, WebP, GIF, size and pixels, MIME fallback, cache reuse, account isolation, retry and original save.");

    var pcm = Path.Combine(temporary.FullName, "fixture.wav");
    var encoded = Path.Combine(temporary.FullName, "fixture.m4a");
    var samples = new float[44100 * 2];
    for (var index = 0; index < samples.Length; index++) samples[index] = .4f * (float)Math.Sin(index / 10.0);
    using (var writer = new WaveFileWriter(pcm, new WaveFormat(44100, 16, 1))) writer.WriteSamples(samples, 0, samples.Length);
    var clip = DesktopVoiceRecorder.Encode(pcm, encoded);
    Check(clip is { DurationMs: 2000, Waveform.Length: 32 } && clip.Waveform.Max() is > 30 and < 50, "Voice duration or waveform invalid");
    using (var reader = new MediaFoundationReader(encoded))
        Check(reader.TotalTime.TotalMilliseconds is > 1900 and < 2200, "Encoded M4A is not playable");

    var store = new LocalChatStore(temporary.FullName);
    var peer = new ChatItem(Guid.NewGuid().ToString(), "QA", "", "");
    var outgoing = new DesktopOutgoing(Guid.NewGuid(), peer, "", 17, DateTime.Now, encoded, clip);
    store.Enqueue(owner, outgoing);
    var reopened = new LocalChatStore(temporary.FullName);
    Check(reopened.Outbox("another-account").Count == 0 && reopened.Outbox(owner, "another-peer").Count == 0,
        "Outbox contents leaked across accounts or chats");
    var restored = reopened.Outbox(owner, peer.Id).Single();
    Check(restored.Id == outgoing.Id && restored.ReplyId == 17 && restored.AudioPath == encoded && restored.Voice?.DurationMs == 2000,
        "Outbox did not survive restart with the same message ID");
    reopened.RemoveOutgoing("another-account", outgoing.Id);
    Check(store.Outbox(owner).Count == 1, "Another account removed the queued item");
    reopened.RemoveOutgoing(owner, outgoing.Id);
    Check(store.Outbox(owner).Count == 0, "Confirmed outbox item not removed");

    var album = Enumerable.Range(0, 12).Select(i =>
        new AlbumPhoto(Guid.NewGuid().ToString(), $"{i}.png", "image/png")).ToArray();
    var albumMessage = new ChatMessage(28, "Альбом", false, DateTime.Now,
        album[0].Id, album[0].Name, album[0].Mime, album: album);
    reopened.Save(owner, peer.Id, new[] { albumMessage });
    var cachedAlbum = reopened.Load(owner, peer.Id).Single(message => message.Id == 28);
    Check(cachedAlbum.HasAlbum && !cachedAlbum.HasSinglePhoto && cachedAlbum.Album.SequenceEqual(album),
        "Offline album lost photos or their order");
    Check(cachedAlbum.AlbumTiles.Select(photo => photo.AttachmentId).SequenceEqual(album.Select(photo => photo.Id)),
        "Album must contain a separate preview for each photo");
    reopened.RemoveHistory(owner, peer.Id, 0, new[] { albumMessage.Id });
    var albumOutgoing = new DesktopOutgoing(Guid.NewGuid(), peer, "Подпись", 17, DateTime.Now,
        PhotoPaths: new[] { gif, gif + ".png" });
    reopened.Enqueue(owner, albumOutgoing);
    Check(reopened.Outbox(owner, peer.Id).Single().PhotoPaths!.SequenceEqual(albumOutgoing.PhotoPaths),
        "Album outbox lost photo paths across serialization");
    reopened.RemoveOutgoing(owner, albumOutgoing.Id);
    var photoOutgoing = new DesktopOutgoing(Guid.NewGuid(), peer, "Подпись", 17, DateTime.Now, PhotoPath: gif);
    store.Enqueue(owner, photoOutgoing);
    Check(reopened.Outbox(owner, peer.Id).Single() == photoOutgoing, "Photo outbox lost its photo, caption, reply or message ID");
    reopened.RemoveOutgoing(owner, photoOutgoing.Id);

    store.Save(owner, peer.Id, [new ChatMessage(22, "QA edited", true, DateTime.Now, replyText: "reply", replyId: 17, edited: true, myReaction: "👍", peerReaction: "👍")]);
    store.SaveCursor(owner, peer.Id, 22, 9, 5);
    var edited = reopened.Load(owner, peer.Id).Single();
    Check(reopened.Load("another-account", peer.Id).Count == 0 &&
          reopened.ReadCursor("another-account", peer.Id) == default &&
          reopened.ReadCursor(owner, peer.Id) == (22L, 9L, 5L), "Chat history and cursor must be scoped to the account");
    Check(edited.Edited && edited.ReplyId == 17, "Edit and quote metadata did not survive restart");
    Check(edited.ReactionLabel == "👍 2", "Reactions did not survive restart or aggregate");
    Console.WriteLine("Windows albums, voice AAC, durable outbox, account isolation, edit and quote checks passed.");
}
finally { SqliteConnection.ClearAllPools(); temporary.Delete(recursive: true); }
