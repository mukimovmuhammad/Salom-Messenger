using System.Diagnostics;
using System.IO;
using System.IO.Compression;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Windows;
using Salom.Desktop;

namespace Salom.Setup;

public partial class SetupWindow : Window
{
    private readonly string _installPath = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Programs", "SALOM");
    private readonly bool _update = Environment.GetCommandLineArgs().Skip(1).SequenceEqual(new[] { "--update" });
    private bool _installing;

    public SetupWindow()
    {
        InitializeComponent();
        LocationText.Text = _installPath;
        var version = Assembly.GetExecutingAssembly().GetCustomAttribute<AssemblyInformationalVersionAttribute>()?.InformationalVersion;
        VersionText.Text = "Версия " + version?.Split('+')[0];
        Closing += (_, e) => e.Cancel = _installing;
        if (_update)
        {
            Title = "Обновление Салома";
            DescriptionText.Text = "Обновляю Салом. После установки приложение откроется автоматически.";
            InstallButton.Content = "Повторить";
            Loaded += async (_, _) => await RunInstallAsync();
        }
    }
    private void Cancel_Click(object sender, RoutedEventArgs e) => Close();

    private async void Install_Click(object sender, RoutedEventArgs e) => await RunInstallAsync();

    private async Task RunInstallAsync()
    {
        if (_installing) return;
        _installing = true;
        InstallButton.IsEnabled = false; CancelButton.IsEnabled = false; InstallProgress.Visibility = Visibility.Visible;
        try
        {
            await Task.Run(InstallFiles);
            var exe = Path.Combine(_installPath, "Salom.Desktop.exe");
            using var app = Process.Start(new ProcessStartInfo(exe) { UseShellExecute = true, WorkingDirectory = _installPath });
            if (_update) return;
            // Keep shortcut paths ASCII: WScript.Shell can otherwise use the
            // system ANSI code page and replace Cyrillic characters with '?'.
            var shortcutWarning = "";
            var shortcutTask = Task.Run(() => {
                CreateShortcut(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.Programs), "SALOM"), "SALOM.lnk");
                CreateShortcut(Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory), "SALOM.lnk");
            });
            if (await Task.WhenAny(shortcutTask, Task.Delay(TimeSpan.FromSeconds(5))) != shortcutTask)
                shortcutWarning = "\n\nЯрлык не создан: Windows не ответила вовремя. Открой Салом через установленный EXE.";
            else try { await shortcutTask; }
                catch (Exception shortcutError) { shortcutWarning = "\n\nЯрлык не создан: " + shortcutError.Message + "\nОткрой Салом через установленный EXE."; }
            MessageBox.Show(this, "Салом установлен." + shortcutWarning, "Готово", MessageBoxButton.OK, MessageBoxImage.Information);
        }
        catch (Exception ex) { InstallProgress.Visibility = Visibility.Collapsed; InstallButton.IsEnabled = true; CancelButton.IsEnabled = true; MessageBox.Show(this, "Не удалось установить Салом:\n" + ex.Message, "Ошибка установки", MessageBoxButton.OK, MessageBoxImage.Error); }
        finally
        {
            _installing = false;
            if (!InstallButton.IsEnabled) Close();
        }
    }

    private void InstallFiles()
    {
        var executable = Path.Combine(_installPath, "Salom.Desktop.exe");
        using var installation = DesktopInstance.Acquire(executable, foreground: true)
            ?? throw new InvalidOperationException("Закрой окно Салома перед обновлением и повтори попытку.");
        if (_update)
        {
            if (!File.Exists(executable)) throw new InvalidOperationException("Салом не найден в папке установки.");
            var fileVersion = FileVersionInfo.GetVersionInfo(executable);
            var installed = new DesktopBuildVersion(
                new Version(fileVersion.FileMajorPart, fileVersion.FileMinorPart, fileVersion.FileBuildPart), fileVersion.ProductVersion);
            var assembly = Assembly.GetExecutingAssembly();
            var candidate = new DesktopBuildVersion(new Version(assembly.GetName().Version!.ToString(3)),
                assembly.GetCustomAttribute<AssemblyInformationalVersionAttribute>()?.InformationalVersion);
            if (!candidate.IsNewerThan(installed)) return;
        }
        using var archiveStream = Assembly.GetExecutingAssembly().GetManifestResourceStream("Salom.Setup.SalomDesktop.zip") ?? throw new InvalidOperationException("В установщик не включён пакет приложения.");
        using var archive = new ZipArchive(archiveStream, ZipArchiveMode.Read);
        InstallPayload.Apply(archive, _installPath);
    }

    private void CreateShortcut(string folder, string name)
    {
        Directory.CreateDirectory(folder);
        var shellType = Type.GetTypeFromProgID("WScript.Shell") ?? throw new InvalidOperationException("Windows Script Host недоступен.");
        dynamic shell = Activator.CreateInstance(shellType)!;
        dynamic shortcut = shell.CreateShortcut(Path.Combine(folder, name));
        shortcut.TargetPath = Path.Combine(_installPath, "Salom.Desktop.exe");
        shortcut.WorkingDirectory = _installPath;
        shortcut.Description = "Салом для Windows";
        shortcut.Save();
        Marshal.FinalReleaseComObject(shortcut); Marshal.FinalReleaseComObject(shell);
    }
}
