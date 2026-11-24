using System.Diagnostics;
using System.Net.WebSockets;
using Microsoft.Toolkit.Uwp.Notifications;

namespace Salom.Desktop;

internal static class BackgroundNotifications
{
    internal static async Task RunAsync(EventWaitHandle stop)
    {
        if (stop.WaitOne(0)) return;
        using var cancellation = new CancellationTokenSource();
        var wait = ThreadPool.RegisterWaitForSingleObject(stop, (_, _) =>
        {
            try { cancellation.Cancel(); }
            catch (ObjectDisposedException) { }
        }, null,
            Timeout.Infinite, executeOnlyOnce: true);
        try { await ReceiveAsync(cancellation.Token); }
        finally { wait.Unregister(null); }
    }

    private static async Task ReceiveAsync(CancellationToken cancellation)
    {
        var client = new DesktopClient();
        if (!client.HasSession) return;
        var seen = new RecentNotificationIds();
        var preferences = DesktopPreferences.Load();
        var delay = 1;
        while (!cancellation.IsCancellationRequested)
        {
            try
            {
                using var connectionTimeout = CancellationTokenSource.CreateLinkedTokenSource(cancellation);
                connectionTimeout.CancelAfter(TimeSpan.FromSeconds(15));
                using var socket = await client.ConnectRealtimeAsync("*", connectionTimeout.Token);
                connectionTimeout.CancelAfter(Timeout.Infinite);
                using var pingStop = CancellationTokenSource.CreateLinkedTokenSource(cancellation);
                var keepAlive = PingAsync(socket, pingStop.Token);
                try
                {
                    while (socket.State == WebSocketState.Open)
                    {
                        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellation);
                        timeout.CancelAfter(TimeSpan.FromSeconds(55));
                        using var packet = await client.ReceiveRealtimeEventAsync(socket, timeout.Token);
                        if (packet is null) break;
                        delay = 1;
                        var payload = packet.RootElement;
                        if (!payload.TryGetProperty("type", out var type) || type.GetString() != "message" ||
                            !payload.TryGetProperty("message", out var incoming)) continue;
                        var message = client.ParseRealtimeMessage(incoming);
                        if (message.IsMine || !Guid.TryParse(incoming.GetProperty("sender_id").GetString(), out var peer) ||
                            !seen.Add(message.Id)) continue;
                        var preview = preferences.PrivateNotificationPreview ? "Новое сообщение" : message.Preview;
                        DesktopMessageToast.TryShow(message.Id, peer.ToString(), "Новое сообщение", preview);
                    }
                }
                finally
                {
                    await pingStop.CancelAsync();
                    try { await keepAlive; }
                    catch (OperationCanceledException) when (pingStop.IsCancellationRequested) { }
                }
            }
            catch (OperationCanceledException) when (cancellation.IsCancellationRequested) { return; }
            catch (Exception error)
            {
                Trace.TraceWarning("SALOM background reconnect: {0}", error.GetType().Name);
            }
            try { await Task.Delay(TimeSpan.FromSeconds(delay), cancellation); }
            catch (OperationCanceledException) when (cancellation.IsCancellationRequested) { return; }
            delay = Math.Min(30, delay * 2);
        }
    }

    private static async Task PingAsync(ClientWebSocket socket, CancellationToken cancellation)
    {
        using var timer = new PeriodicTimer(TimeSpan.FromSeconds(20));
        while (await timer.WaitForNextTickAsync(cancellation) && socket.State == WebSocketState.Open)
            await socket.SendAsync("{\"type\":\"ping\"}"u8.ToArray(), WebSocketMessageType.Text, true, cancellation);
    }
}

internal static class DesktopMessageToast
{
    internal static bool TryShow(long messageId, string peerId, string title, string body)
    {
        try
        {
            new ToastContentBuilder().AddArgument("peer", peerId).AddText("Салом · " + title)
                .AddText(body.Length > 110 ? body[..110] + "…" : body)
                .Show(toast => { toast.Tag = messageId.ToString(); toast.Group = "messages"; });
            return true;
        }
        catch (Exception error)
        {
            Trace.TraceWarning("SALOM toast failed: {0}", error.GetType().Name);
            return false;
        }
    }
}
