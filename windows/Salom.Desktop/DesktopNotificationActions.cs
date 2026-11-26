using Microsoft.Toolkit.Uwp.Notifications;
using Microsoft.Win32;

namespace Salom.Desktop;

internal static class DesktopNotificationActions
{
    private const string Mutes = @"Software\SALOM\NotificationMutes\";

    internal static bool Muted(Guid owner, Guid peer)
    {
        using var key = Registry.CurrentUser.OpenSubKey(Mutes + owner.ToString("N"));
        return key?.GetValue(peer.ToString("N")) is long until && until > DateTimeOffset.UtcNow.ToUnixTimeSeconds();
    }

    internal static async Task HandleAsync(ToastArguments args, ToastNotificationActivatedEventArgsCompat activation, Guid peer, string action)
    {
        if (!args.TryGetValue("owner", out var rawOwner) || !Guid.TryParse(rawOwner, out var owner)
            || !args.TryGetValue("message", out var rawMessage) || !long.TryParse(rawMessage, out var message) || message <= 0)
            return;
        var client = new DesktopClient();
        if (!Guid.TryParse(client.NotificationOwner, out var active) || active != owner) return;
        using var deadline = new CancellationTokenSource(TimeSpan.FromSeconds(18));
        switch (action)
        {
            case "mute":
                using (var key = Registry.CurrentUser.CreateSubKey(Mutes + owner.ToString("N")))
                    key.SetValue(peer.ToString("N"), Muted(owner, peer) ? 0L : DateTimeOffset.UtcNow.AddHours(1).ToUnixTimeSeconds(), RegistryValueKind.QWord);
                break;
            case "reply":
                if (!args.TryGetValue("request", out var rawRequest) || !Guid.TryParse(rawRequest, out var request)
                    || !activation.UserInput.TryGetValue("reply", out var rawReply) || rawReply is not string reply
                    || reply.Trim().Length is < 1 or > 4000) return;
                await client.SendTextAsync(peer.ToString(), reply.Trim(), request, message, deadline.Token);
                await client.MarkNotificationsReadAsync(peer.ToString(), deadline.Token);
                break;
            case "read":
                await client.MarkNotificationsReadAsync(peer.ToString(), deadline.Token);
                break;
            default:
                return;
        }
        ToastNotificationManagerCompat.History.Remove(message.ToString(), "messages");
    }

    internal static void ShowError(Guid peer)
    {
        new ToastContentBuilder().AddArgument("peer", peer.ToString()).AddText("Салом")
            .AddText("Действие не выполнено. Открой чат, чтобы попробовать снова.").Show();
    }
}
