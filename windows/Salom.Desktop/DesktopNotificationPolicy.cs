namespace Salom.Desktop;

internal static class DesktopNotificationPolicy
{
    internal static bool ShouldNotify(bool ownMessage, bool sameChat, bool visible, bool active, bool minimized) =>
        !ownMessage && !(sameChat && visible && active && !minimized);
}

internal sealed class RecentNotificationIds
{
    private readonly HashSet<long> _ids = new();
    private readonly Queue<long> _order = new();

    internal bool Add(long id)
    {
        if (id <= 0 || !_ids.Add(id)) return false;
        _order.Enqueue(id);
        if (_order.Count > 512) _ids.Remove(_order.Dequeue());
        return true;
    }
}
