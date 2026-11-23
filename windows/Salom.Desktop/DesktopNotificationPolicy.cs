namespace Salom.Desktop;

internal static class DesktopNotificationPolicy
{
    internal static bool ShouldNotify(bool ownMessage, bool sameChat, bool visible, bool active, bool minimized) =>
        !ownMessage && !(sameChat && visible && active && !minimized);
}
