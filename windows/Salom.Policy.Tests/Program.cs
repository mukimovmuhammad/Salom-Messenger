using Salom.Desktop;

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
