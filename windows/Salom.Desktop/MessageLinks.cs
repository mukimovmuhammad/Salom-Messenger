using System.Diagnostics;
using System.Text.RegularExpressions;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Documents;
using System.Windows.Media;

namespace Salom.Desktop;

public sealed class MessageLinks : TextBlock
{
    private static readonly Regex Links = new("""(?<![\p{L}\p{N}_@])(?:https?://|www\.)[^\s<>\[\]{}"«»]+""",
        RegexOptions.IgnoreCase | RegexOptions.CultureInvariant, TimeSpan.FromMilliseconds(100));

    public static readonly DependencyProperty BodyProperty = DependencyProperty.Register(
        nameof(Body), typeof(string), typeof(MessageLinks), new PropertyMetadata("", (sender, args) =>
            ((MessageLinks)sender).Render(args.NewValue as string ?? "")));

    public string Body
    {
        get => (string)GetValue(BodyProperty);
        set => SetValue(BodyProperty, value);
    }

    private void Render(string text)
    {
        Inlines.Clear();
        var end = 0;
        foreach (Match match in Links.Matches(text))
        {
            var label = match.Value.TrimEnd('.', ',', '!', '?', ';', ':', '…');
            var extraClosings = label.Count(c => c == ')') - label.Count(c => c == '(');
            while (extraClosings-- > 0 && label.EndsWith(')')) label = label[..^1];
            var url = label.StartsWith("www.", StringComparison.OrdinalIgnoreCase) ? "https://" + label : label;
            if (!Uri.TryCreate(url, UriKind.Absolute, out var uri) || uri.Scheme is not ("http" or "https")
                || string.IsNullOrEmpty(uri.Host) || uri.UserInfo.Length > 0) continue;
            Inlines.Add(new Run(text[end..match.Index]));
            var link = new Hyperlink(new Run(label)) { Foreground = System.Windows.Media.Brushes.LightSkyBlue, NavigateUri = uri, ToolTip = uri.AbsoluteUri };
            link.RequestNavigate += (_, args) =>
            {
                args.Handled = true;
                try { Process.Start(new ProcessStartInfo(uri.AbsoluteUri) { UseShellExecute = true })?.Dispose(); }
                catch (System.ComponentModel.Win32Exception)
                {
                    System.Windows.MessageBox.Show("Не удалось открыть ссылку в браузере.", "Салом");
                }
            };
            Inlines.Add(link);
            end = match.Index + label.Length;
        }
        Inlines.Add(new Run(text[end..]));
    }
}
