using System.Diagnostics;
using Microsoft.Toolkit.Uwp.Notifications;

namespace Salom.Desktop;

internal static class DesktopUpdateToast
{
    internal static void Progress(Version version, int percentage) =>
        Show($"Обновление Салома {version}", $"Загружено {percentage}%", quiet: percentage > 0);

    internal static void Ready(Version version) =>
        Show($"Обновление Салома {version}", "Скачано и проверено. Нажми, чтобы установить.");

    internal static void Interrupted(Version version) =>
        Show($"Обновление Салома {version}", "Загрузка прервана. Нажми, чтобы продолжить.");

    internal static void Installed() => Show("Салом обновлён", "Установлена версия " +
        typeof(App).Assembly.GetName().Version!.ToString(3), installed: true);

    private static void Show(string title, string text, bool quiet = false, bool installed = false)
    {
        try
        {
            var toast = new ToastContentBuilder().AddText(title).AddText(text).AddAudio(new ToastAudio { Silent = true });
            if (!installed) toast.AddArgument("openUpdate", "1");
            toast.Show(notification =>
            {
                notification.Tag = "release";
                notification.Group = "updates";
                notification.SuppressPopup = quiet;
            });
        }
        catch (Exception error) { Trace.TraceWarning("SALOM update notification: {0}", error.GetType().Name); }
    }
}
