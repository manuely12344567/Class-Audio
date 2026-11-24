using System.Collections.Concurrent;
using System.Security.Cryptography;
using System.Text;
using ClassroomAudio.Host.Models;

namespace ClassroomAudio.Host.Core;

public sealed record AuthenticatedParticipant(string Identity, string DisplayName, string Role);

/// <summary>
/// Issues participant credentials locally and enforces the one-student-speaker rule.
/// Control tokens are random, kept in memory only, and invalidated when the host stops.
/// </summary>
public sealed class SessionCoordinator
{
    private const int MaximumParticipantsPerSession = 220;
    private readonly SessionOptions _options;
    private readonly LiveKitTokenService _tokens;
    private readonly LiveKitApiClient _liveKit;
    private readonly ConcurrentDictionary<string, AuthenticatedParticipant> _byControlToken = new();
    private readonly ConcurrentDictionary<string, AuthenticatedParticipant> _byIdentity = new();
    private readonly ConcurrentDictionary<string, SpeakRequestItem> _requests = new();
    private readonly SemaphoreSlim _speakerGate = new(1, 1);
    private string? _activeSpeakerIdentity;

    public SessionCoordinator(SessionOptions options, LiveKitTokenService tokens, LiveKitApiClient liveKit)
    {
        _options = options;
        _tokens = tokens;
        _liveKit = liveKit;
    }

    public SessionOptions Options => _options;
    public string? ActiveSpeakerIdentity => Volatile.Read(ref _activeSpeakerIdentity);

    public JoinResponse Join(JoinRequest request, string connectedHost)
    {
        var role = request.Role?.Trim().ToLowerInvariant();
        if (role is not ("student" or "teacher"))
            throw new JoinRejectedException(400, "Le rôle doit être student ou teacher.");

        if (!SecretEquals(request.JoinCode, _options.JoinCode))
            throw new JoinRejectedException(401, "Code de classe incorrect.");

        var isTeacher = role == "teacher";
        if (isTeacher && !SecretEquals(request.TeacherPin, _options.TeacherPin))
            throw new JoinRejectedException(401, "Code professeur incorrect.");

        if (_byIdentity.Count >= MaximumParticipantsPerSession)
            throw new JoinRejectedException(429, "La limite de participants de ce prototype est atteinte.");

        var displayName = NormalizeDisplayName(request.DisplayName);
        var identity = $"{(isTeacher ? "teacher" : "student")}-{LiveKitTokenService.RandomToken(9)}";
        var controlToken = LiveKitTokenService.RandomToken(32);
        var participant = new AuthenticatedParticipant(identity, displayName, role!);

        _byIdentity[identity] = participant;
        _byControlToken[controlToken] = participant;

        var accessToken = _tokens.CreateParticipantToken(identity, isTeacher);
        var host = IsUsableHost(connectedHost) ? connectedHost : _options.PrimaryHost;
        var liveKitUrl = $"ws://{host}:{_options.LiveKitPort}";

        return new JoinResponse(
            liveKitUrl,
            _options.RoomName,
            identity,
            role!,
            accessToken,
            controlToken,
            TokenExpiresInSeconds: 4 * 60 * 60);
    }

    public DiscoveryInfo GetDiscoveryInfo(string connectedHost)
    {
        var host = IsUsableHost(connectedHost) ? connectedHost : _options.PrimaryHost;
        return new DiscoveryInfo(
            ServiceName: "ClasseAudio",
            ServiceType: "_classeaudio._tcp.local",
            Room: _options.RoomName,
            LiveKitUrl: $"ws://{host}:{_options.LiveKitPort}",
            ApiPort: _options.ApiPort,
            JoinPath: "/api/v1/join",
            HostAddress: host);
    }

    public bool TryAuthenticate(string? authorizationHeader, out AuthenticatedParticipant? participant)
    {
        participant = null;
        if (string.IsNullOrWhiteSpace(authorizationHeader)
            || !authorizationHeader.StartsWith("Bearer ", StringComparison.OrdinalIgnoreCase))
            return false;

        var controlToken = authorizationHeader[7..].Trim();
        return controlToken.Length > 0 && _byControlToken.TryGetValue(controlToken, out participant);
    }

    public OperationResult RequestToSpeak(AuthenticatedParticipant caller)
    {
        if (caller.Role != "student")
            return OperationResult.Forbidden("Seuls les élèves peuvent demander la parole.");

        if (ActiveSpeakerIdentity == caller.Identity)
            return OperationResult.Conflict("Cet élève est déjà autorisé à parler.");

        _requests.TryAdd(caller.Identity, new SpeakRequestItem(
            caller.Identity,
            caller.DisplayName,
            DateTimeOffset.UtcNow));
        return OperationResult.Ok("Demande envoyée au professeur.");
    }

    public IReadOnlyList<SpeakRequestItem> GetSpeakingRequests() =>
        _requests.Values.OrderBy(item => item.RequestedAt).ToArray();

    public async Task<OperationResult> GrantSpeakerAsync(string identity, CancellationToken cancellationToken = default)
    {
        await _speakerGate.WaitAsync(cancellationToken);
        try
        {
            if (ActiveSpeakerIdentity is not null)
                return OperationResult.Conflict("Un autre élève est déjà autorisé à parler.");
            if (!_byIdentity.TryGetValue(identity, out var participant) || participant.Role != "student")
                return OperationResult.NotFound("Élève inconnu pour cette session.");
            if (!_requests.ContainsKey(identity))
                return OperationResult.Conflict("Cet élève n'a pas de demande de parole en attente.");

            // The LiveKit server enforces this permission; a student cannot publish
            // merely by editing the Android client.
            await _liveKit.UpdateMicrophonePermissionAsync(identity, canPublish: true, cancellationToken);
            Volatile.Write(ref _activeSpeakerIdentity, identity);
            _requests.TryRemove(identity, out _);
            return OperationResult.Ok("Parole accordée. Le serveur n'accepte qu'un élève à la fois.");
        }
        catch (InvalidOperationException ex)
        {
            return OperationResult.Conflict(ex.Message);
        }
        finally
        {
            _speakerGate.Release();
        }
    }

    public async Task<OperationResult> RevokeSpeakerAsync(string identity, CancellationToken cancellationToken = default)
    {
        await _speakerGate.WaitAsync(cancellationToken);
        try
        {
            if (ActiveSpeakerIdentity != identity)
                return OperationResult.NotFound("Cet élève n'est pas l'orateur actif.");

            await _liveKit.UpdateMicrophonePermissionAsync(identity, canPublish: false, cancellationToken);
            Volatile.Write(ref _activeSpeakerIdentity, null);
            return OperationResult.Ok("Permission micro retirée.");
        }
        catch (InvalidOperationException ex)
        {
            return OperationResult.Conflict(ex.Message);
        }
        finally
        {
            _speakerGate.Release();
        }
    }

    public async Task<DashboardSnapshot> GetDashboardSnapshotAsync(CancellationToken cancellationToken = default)
    {
        var serverParticipants = await _liveKit.ListParticipantsAsync(cancellationToken);
        var identities = serverParticipants.Select(item => item.Identity).ToHashSet(StringComparer.Ordinal);
        foreach (var requestedIdentity in _requests.Keys)
        {
            if (!identities.Contains(requestedIdentity))
                _requests.TryRemove(requestedIdentity, out _);
        }
        var currentSpeaker = ActiveSpeakerIdentity;
        if (currentSpeaker is not null && !identities.Contains(currentSpeaker))
        {
            await _speakerGate.WaitAsync(cancellationToken);
            try
            {
                if (ActiveSpeakerIdentity == currentSpeaker)
                    Volatile.Write(ref _activeSpeakerIdentity, null);
            }
            finally
            {
                _speakerGate.Release();
            }
        }

        var visibleParticipants = serverParticipants.Select(item =>
        {
            _byIdentity.TryGetValue(item.Identity, out var known);
            return new ParticipantSummary(
                item.Identity,
                known?.DisplayName ?? item.Identity,
                known?.Role ?? "inconnu",
                item.State,
                item.CanPublish,
                item.PublishedTrackCount);
        }).ToArray();

        return new DashboardSnapshot(
            visibleParticipants.Length,
            ActiveSpeakerIdentity,
            visibleParticipants,
            GetSpeakingRequests());
    }

    private static string NormalizeDisplayName(string? value)
    {
        var cleaned = new string((value ?? string.Empty).Where(character => !char.IsControl(character)).Take(40).ToArray()).Trim();
        return string.IsNullOrWhiteSpace(cleaned) ? "Participant" : cleaned;
    }

    private static bool SecretEquals(string? received, string expected)
    {
        var left = Encoding.UTF8.GetBytes(received ?? string.Empty);
        var right = Encoding.UTF8.GetBytes(expected);
        return left.Length == right.Length && CryptographicOperations.FixedTimeEquals(left, right);
    }

    private static bool IsUsableHost(string? host)
    {
        if (string.IsNullOrWhiteSpace(host) || !System.Net.IPAddress.TryParse(host, out var address))
            return false;
        return !System.Net.IPAddress.IsLoopback(address)
            && !address.Equals(System.Net.IPAddress.Any)
            && !address.Equals(System.Net.IPAddress.IPv6Any);
    }
}
