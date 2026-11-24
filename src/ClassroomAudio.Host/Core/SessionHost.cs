using System.Diagnostics;
using System.IO;
using System.Net;
using System.Net.Http;
using System.Security.Cryptography;
using ClassroomAudio.Host.Models;

namespace ClassroomAudio.Host.Core;

/// <summary>Owns the local LiveKit process, the LAN API, and the temporary session secrets.</summary>
public sealed class SessionHost : IAsyncDisposable
{
    public const int ApiPort = 8090;
    public const int LiveKitPort = 7880;
    private const int LiveKitTcpFallbackPort = 7881;
    private const int LiveKitUdpMuxPort = 7882;

    private Process? _serverProcess;
    private LocalApiHost? _apiHost;
    private MdnsAdvertiser? _mdnsAdvertiser;
    private LiveKitApiClient? _liveKitApi;
    private string? _configPath;

    public event Action<string>? LogLine;
    public SessionOptions? CurrentSession { get; private set; }
    public SessionCoordinator? Coordinator { get; private set; }

    public bool IsRunning => _serverProcess is { HasExited: false } && _apiHost is not null;

    public async Task StartAsync(CancellationToken cancellationToken = default)
    {
        if (IsRunning)
            throw new InvalidOperationException("Le serveur est déjà démarré.");

        var runtimeDirectory = Path.Combine(AppContext.BaseDirectory, "runtime");
        var serverPath = Path.Combine(runtimeDirectory, "livekit-server.exe");
        if (!File.Exists(serverPath))
            throw new FileNotFoundException("Le serveur LiveKit intégré est introuvable.", serverPath);

        var addresses = NetworkAddressResolver.GetLanIPv4Addresses();
        var sessionId = Guid.NewGuid().ToString("N");
        var options = new SessionOptions(
            RoomName: $"classe-{RandomNumberGenerator.GetInt32(100000, 1000000)}",
            JoinCode: RandomNumberGenerator.GetInt32(0, 1000000).ToString("D6"),
            TeacherPin: RandomNumberGenerator.GetInt32(0, 1000000).ToString("D6"),
            ApiKey: $"classe_{LiveKitTokenService.RandomToken(15)}",
            ApiSecret: LiveKitTokenService.RandomToken(32),
            ApiPort: ApiPort,
            LiveKitPort: LiveKitPort,
            LanAddresses: addresses);

        var sessionDirectory = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "ClasseAudio",
            "sessions");
        Directory.CreateDirectory(sessionDirectory);
        CleanupStaleSessionFiles(sessionDirectory);
        _configPath = Path.Combine(sessionDirectory, $"{sessionId}.yaml");
        await File.WriteAllTextAsync(_configPath, BuildLiveKitConfig(options), cancellationToken);

        try
        {
            LogLine?.Invoke($"Démarrage de LiveKit {GetBundledVersionLabel()}…");
            var startInfo = new ProcessStartInfo
            {
                FileName = serverPath,
                WorkingDirectory = runtimeDirectory,
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardOutput = true,
                RedirectStandardError = true
            };
            startInfo.ArgumentList.Add("--config");
            startInfo.ArgumentList.Add(_configPath);
            startInfo.ArgumentList.Add("--bind");
            startInfo.ArgumentList.Add("0.0.0.0");

            _serverProcess = new Process { StartInfo = startInfo, EnableRaisingEvents = true };
            _serverProcess.OutputDataReceived += (_, args) =>
            {
                if (!string.IsNullOrWhiteSpace(args.Data)) LogLine?.Invoke(args.Data);
            };
            _serverProcess.ErrorDataReceived += (_, args) =>
            {
                if (!string.IsNullOrWhiteSpace(args.Data)) LogLine?.Invoke(args.Data);
            };

            if (!_serverProcess.Start())
                throw new InvalidOperationException("Windows n'a pas pu lancer livekit-server.exe.");

            _serverProcess.BeginOutputReadLine();
            _serverProcess.BeginErrorReadLine();
            await WaitForLiveKitAsync(_serverProcess, cancellationToken);

            var tokens = new LiveKitTokenService(options);
            _liveKitApi = new LiveKitApiClient(options, tokens);
            await _liveKitApi.CreateRoomAsync(cancellationToken);
            LogLine?.Invoke($"Salon LiveKit créé : {options.RoomName} (limite configurée : 220; charge réelle non validée).");
            Coordinator = new SessionCoordinator(options, tokens, _liveKitApi);
            _apiHost = await LocalApiHost.StartAsync(Coordinator, cancellationToken);
            CurrentSession = options;

            if (addresses.Count > 0)
            {
                try
                {
                    _mdnsAdvertiser = new MdnsAdvertiser(options.ApiPort, addresses);
                    _mdnsAdvertiser.Start();
                    LogLine?.Invoke("Découverte locale annoncée : _classeaudio._tcp.local");
                }
                catch (Exception ex)
                {
                    _mdnsAdvertiser?.Dispose();
                    _mdnsAdvertiser = null;
                    LogLine?.Invoke($"Découverte automatique indisponible; utiliser l'adresse manuelle. Détail : {ex.Message}");
                }
            }
            else
            {
                LogLine?.Invoke("Aucune adresse LAN détectée; le mode manuel ne fonctionnera qu'après connexion au Wi-Fi/Ethernet.");
            }

            LogLine?.Invoke($"API locale en écoute sur le port TCP {options.ApiPort}.");
            LogLine?.Invoke($"Signal LiveKit : TCP {options.LiveKitPort}; média : UDP {LiveKitUdpMuxPort}; repli : TCP {LiveKitTcpFallbackPort}.");
            LogLine?.Invoke("Aucun enregistrement audio n'est activé.");
        }
        catch
        {
            await StopAsync();
            throw;
        }
    }

    public async Task StopAsync(CancellationToken cancellationToken = default)
    {
        _mdnsAdvertiser?.Dispose();
        _mdnsAdvertiser = null;

        if (_apiHost is not null)
        {
            try { await _apiHost.StopAsync(cancellationToken); }
            catch (Exception ex) { LogLine?.Invoke($"Arrêt API : {ex.Message}"); }
            try { await _apiHost.DisposeAsync(); }
            catch (Exception ex) { LogLine?.Invoke($"Libération API : {ex.Message}"); }
            _apiHost = null;
        }

        if (_liveKitApi is not null && _serverProcess is { HasExited: false })
        {
            try { await _liveKitApi.DeleteRoomAsync(CancellationToken.None); }
            catch (Exception ex)
            {
                LogLine?.Invoke($"La fermeture du salon LiveKit a échoué; arrêt du serveur tout de même effectué. Détail : {ex.Message}");
            }
        }

        if (_serverProcess is not null)
        {
            try
            {
                if (!_serverProcess.HasExited)
                {
                    _serverProcess.Kill(entireProcessTree: true);
                    await _serverProcess.WaitForExitAsync(CancellationToken.None);
                }
            }
            catch (InvalidOperationException) { }
            catch (System.ComponentModel.Win32Exception ex) { LogLine?.Invoke($"Arrêt du processus : {ex.Message}"); }
            finally
            {
                _serverProcess.Dispose();
                _serverProcess = null;
            }
        }

        _liveKitApi?.Dispose();
        _liveKitApi = null;
        Coordinator = null;
        CurrentSession = null;

        if (_configPath is not null)
        {
            try { File.Delete(_configPath); }
            catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
            {
                LogLine?.Invoke($"La configuration temporaire n'a pas pu être supprimée : {ex.Message}");
            }
            _configPath = null;
        }

        LogLine?.Invoke("Serveur arrêté; secrets de cette session supprimés de la mémoire et du fichier temporaire.");
    }

    public Task<DashboardSnapshot> GetDashboardSnapshotAsync(CancellationToken cancellationToken = default)
    {
        if (Coordinator is null)
            throw new InvalidOperationException("Aucune session en cours.");
        return Coordinator.GetDashboardSnapshotAsync(cancellationToken);
    }

    private static async Task WaitForLiveKitAsync(Process process, CancellationToken cancellationToken)
    {
        using var http = new HttpClient { Timeout = TimeSpan.FromSeconds(1) };
        var address = $"http://127.0.0.1:{LiveKitPort}/";

        for (var attempt = 0; attempt < 30; attempt++)
        {
            cancellationToken.ThrowIfCancellationRequested();
            if (process.HasExited)
                throw new InvalidOperationException($"LiveKit s'est arrêté au démarrage (code {process.ExitCode}). Vérifier les journaux et les ports 7880–7882.");

            try
            {
                using var response = await http.GetAsync(address, cancellationToken);
                return; // Any HTTP response proves the local signal listener is ready.
            }
            catch (HttpRequestException) { }
            catch (TaskCanceledException) when (!cancellationToken.IsCancellationRequested) { }

            await Task.Delay(TimeSpan.FromMilliseconds(500), cancellationToken);
        }

        throw new TimeoutException("LiveKit n'a pas ouvert son port de signalisation en 15 secondes.");
    }

    private static void CleanupStaleSessionFiles(string directory)
    {
        foreach (var path in Directory.EnumerateFiles(directory, "*.yaml"))
        {
            try
            {
                if (DateTime.UtcNow - File.GetLastWriteTimeUtc(path) > TimeSpan.FromHours(24))
                    File.Delete(path);
            }
            catch (IOException) { }
            catch (UnauthorizedAccessException) { }
        }
    }

    private static string BuildLiveKitConfig(SessionOptions options) => $"""
        port: {options.LiveKitPort}
        log_level: info
        rtc:
          tcp_port: {LiveKitTcpFallbackPort}
          udp_port: {LiveKitUdpMuxPort}
          use_external_ip: false
        keys:
          {options.ApiKey}: {options.ApiSecret}
        """;

    private static string GetBundledVersionLabel() => "v1.13.11";

    public async ValueTask DisposeAsync() => await StopAsync();
}
