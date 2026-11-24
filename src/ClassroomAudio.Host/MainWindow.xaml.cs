using System.Collections.ObjectModel;
using System.Windows;
using System.Windows.Threading;
using ClassroomAudio.Host.Core;
using ClassroomAudio.Host.Models;

namespace ClassroomAudio.Host;

public partial class MainWindow : Window
{
    private readonly SessionHost _host = new();
    private readonly ObservableCollection<ParticipantRow> _participants = new();
    private readonly ObservableCollection<string> _logLines = new();
    private readonly DispatcherTimer _refreshTimer;

    public MainWindow()
    {
        InitializeComponent();
        ParticipantGrid.ItemsSource = _participants;
        LogList.ItemsSource = _logLines;

        _host.LogLine += line => Dispatcher.BeginInvoke(new Action(() => AddLog(line)));
        _refreshTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(2) };
        _refreshTimer.Tick += async (_, _) => await RefreshDashboardAsync();
        Closed += async (_, _) =>
        {
            _refreshTimer.Stop();
            try { await _host.DisposeAsync(); }
            catch (Exception ex) { AddLog($"Fermeture : {ex.Message}"); }
        };

        AddLog("Application prête. Aucun service Cloud ni token externe n'est utilisé.");
    }

    private async void StartButton_Click(object sender, RoutedEventArgs e)
    {
        StartButton.IsEnabled = false;
        StatusText.Text = "Démarrage…";
        StatusText.Foreground = System.Windows.Media.Brushes.DarkOrange;
        SummaryText.Text = "Lancement du relais local et de l'API de découverte…";

        try
        {
            await _host.StartAsync();
            UpdateSessionDetails();
            StatusText.Text = "En cours";
            StatusText.Foreground = System.Windows.Media.Brushes.DarkGreen;
            StopButton.IsEnabled = true;
            CopyButton.IsEnabled = true;
            _refreshTimer.Start();
            await RefreshDashboardAsync();
        }
        catch (Exception ex)
        {
            StatusText.Text = "Erreur de démarrage";
            StatusText.Foreground = System.Windows.Media.Brushes.Firebrick;
            SummaryText.Text = ex.Message;
            AddLog($"Échec : {ex.Message}");
            StartButton.IsEnabled = true;
            StopButton.IsEnabled = false;
            CopyButton.IsEnabled = false;
        }
    }

    private async void StopButton_Click(object sender, RoutedEventArgs e)
    {
        StopButton.IsEnabled = false;
        CopyButton.IsEnabled = false;
        _refreshTimer.Stop();
        try
        {
            await _host.StopAsync();
        }
        catch (Exception ex)
        {
            AddLog($"Arrêt incomplet : {ex.Message}");
        }
        finally
        {
            ResetSessionDetails();
            StatusText.Text = "Arrêté";
            StatusText.Foreground = System.Windows.Media.Brushes.DimGray;
            SummaryText.Text = "Démarrez le serveur avant le cours. Les codes sont générés localement pour chaque session.";
            StartButton.IsEnabled = true;
            _participants.Clear();
            ParticipantsText.Text = "0";
            ActiveSpeakerText.Text = "Aucun";
        }
    }

    private void CopyButton_Click(object sender, RoutedEventArgs e)
    {
        var session = _host.CurrentSession;
        if (session is null) return;

        var text = string.Join(Environment.NewLine,
            "ClasseAudio — connexion manuelle",
            $"Adresse du relais : ws://{session.PrimaryHost}:{SessionHost.LiveKitPort}",
            $"Adresse de découverte/API : http://{session.PrimaryHost}:{SessionHost.ApiPort}",
            $"Code de classe : {session.JoinCode}",
            "Découverte mDNS : _classeaudio._tcp.local",
            "Le PIN professeur est affiché séparément sur le PC; ne le partagez pas avec les élèves.");
        Clipboard.SetText(text);
        AddLog("Informations de connexion copiées dans le presse-papiers.");
    }

    private async Task RefreshDashboardAsync()
    {
        if (!_host.IsRunning) return;
        try
        {
            var snapshot = await _host.GetDashboardSnapshotAsync();
            _participants.Clear();
            foreach (var participant in snapshot.Participants)
            {
                _participants.Add(new ParticipantRow(
                    participant.Identity,
                    participant.DisplayName,
                    participant.Role == "teacher" ? "Professeur" : participant.Role == "student" ? "Élève" : participant.Role,
                    participant.State,
                    participant.CanPublish ? "Oui" : "Non",
                    participant.PublishedTrackCount));
            }

            ParticipantsText.Text = snapshot.ParticipantCount.ToString();
            var active = snapshot.Participants.FirstOrDefault(item => item.Identity == snapshot.ActiveSpeakerIdentity);
            ActiveSpeakerText.Text = active?.DisplayName ?? "Aucun";
            SummaryText.Text = snapshot.ParticipantCount == 0
                ? "Serveur prêt. En attente des téléphones sur le réseau local."
                : $"Salon actif : {snapshot.ParticipantCount} téléphone(s) connecté(s).";
        }
        catch (Exception ex)
        {
            // Keep the UI responsive if the server is restarting or the API is temporarily unavailable.
            StatusText.Text = "Relais actif · état indisponible";
            AddLogOnce($"Lecture de l'état : {ex.Message}");
        }
    }

    private void UpdateSessionDetails()
    {
        var session = _host.CurrentSession;
        if (session is null) return;

        JoinCodeText.Text = session.JoinCode;
        TeacherPinText.Text = session.TeacherPin;
        var host = session.PrimaryHost;
        ServerAddressText.Text = $"Relais : ws://{host}:{SessionHost.LiveKitPort}\nDécouverte/API : http://{host}:{SessionHost.ApiPort}";
        MdnsText.Text = "_classeaudio._tcp.local  ·  port 8090";
        SummaryText.Text = $"Salon local {session.RoomName}. Les codes sont à transmettre uniquement aux participants de cette classe.";
    }

    private void ResetSessionDetails()
    {
        JoinCodeText.Text = "— — — — — —";
        TeacherPinText.Text = "— — — — — —";
        ServerAddressText.Text = "—";
        MdnsText.Text = "_classeaudio._tcp.local";
    }

    private void AddLog(string message)
    {
        _logLines.Add($"[{DateTime.Now:HH:mm:ss}] {message}");
        while (_logLines.Count > 500)
            _logLines.RemoveAt(0);
        if (_logLines.Count > 0)
            LogList.ScrollIntoView(_logLines[^1]);
    }

    private string? _lastRefreshError;
    private void AddLogOnce(string message)
    {
        if (string.Equals(message, _lastRefreshError, StringComparison.Ordinal)) return;
        _lastRefreshError = message;
        AddLog(message);
    }

    private sealed record ParticipantRow(
        string Identity,
        string DisplayName,
        string Role,
        string State,
        string CanPublish,
        int PublishedTrackCount);
}
