using System.Net.Http;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Text.Json;
using ClassroomAudio.Host.Models;

namespace ClassroomAudio.Host.Core;

public sealed record LiveKitParticipant(string Identity, string State, bool CanPublish, int PublishedTrackCount);

/// <summary>Minimal local Twirp client for the two room-management calls used by the host.</summary>
public sealed class LiveKitApiClient : IDisposable
{
    private readonly SessionOptions _options;
    private readonly LiveKitTokenService _tokens;
    private readonly HttpClient _http = new() { Timeout = TimeSpan.FromSeconds(5) };

    public LiveKitApiClient(SessionOptions options, LiveKitTokenService tokens)
    {
        _options = options;
        _tokens = tokens;
    }

    public async Task CreateRoomAsync(CancellationToken cancellationToken = default)
    {
        _ = await PostAsync("CreateRoom", new
        {
            name = _options.RoomName,
            maxParticipants = 220,
            emptyTimeout = 300,
            departureTimeout = 20
        }, cancellationToken);
    }

    public async Task DeleteRoomAsync(CancellationToken cancellationToken = default)
    {
        _ = await PostAsync("DeleteRoom", new { room = _options.RoomName }, cancellationToken);
    }

    public async Task<IReadOnlyList<LiveKitParticipant>> ListParticipantsAsync(CancellationToken cancellationToken = default)
    {
        var responseText = await PostAsync(
            "ListParticipants",
            new { room = _options.RoomName },
            cancellationToken);

        using var document = JsonDocument.Parse(responseText);
        if (!document.RootElement.TryGetProperty("participants", out var participants)
            || participants.ValueKind != JsonValueKind.Array)
            return Array.Empty<LiveKitParticipant>();

        var result = new List<LiveKitParticipant>();
        foreach (var participant in participants.EnumerateArray())
        {
            var identity = ReadString(participant, "identity") ?? "unknown";
            var state = ReadString(participant, "state") ?? "connected";
            var canPublish = participant.TryGetProperty("permission", out var permission)
                && permission.TryGetProperty("canPublish", out var publishValue)
                && publishValue.ValueKind == JsonValueKind.True;
            var tracks = participant.TryGetProperty("tracks", out var trackArray)
                && trackArray.ValueKind == JsonValueKind.Array
                    ? trackArray.GetArrayLength()
                    : 0;

            result.Add(new LiveKitParticipant(identity, state, canPublish, tracks));
        }

        return result;
    }

    public async Task UpdateMicrophonePermissionAsync(
        string identity,
        bool canPublish,
        CancellationToken cancellationToken = default)
    {
        var permission = new Dictionary<string, object?>
        {
            ["canPublish"] = canPublish,
            ["canPublishData"] = false
        };

        if (canPublish)
            permission["canPublishSources"] = new[] { "microphone" };

        var body = new Dictionary<string, object?>
        {
            ["room"] = _options.RoomName,
            ["identity"] = identity,
            ["permission"] = permission
        };

        _ = await PostAsync("UpdateParticipant", body, cancellationToken);
    }

    private async Task<string> PostAsync(string method, object body, CancellationToken cancellationToken)
    {
        using var request = new HttpRequestMessage(
            HttpMethod.Post,
            $"http://127.0.0.1:{_options.LiveKitPort}/twirp/livekit.RoomService/{method}");
        request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", _tokens.CreateRoomAdminToken());
        request.Content = JsonContent.Create(body);

        using var response = await _http.SendAsync(request, cancellationToken);
        var text = await response.Content.ReadAsStringAsync(cancellationToken);
        if (!response.IsSuccessStatusCode)
        {
            var safeText = string.IsNullOrWhiteSpace(text) ? response.ReasonPhrase : text.Trim();
            throw new InvalidOperationException($"LiveKit {method} a échoué ({(int)response.StatusCode}) : {safeText}");
        }

        return text;
    }

    private static string? ReadString(JsonElement element, string name) =>
        element.TryGetProperty(name, out var value) && value.ValueKind == JsonValueKind.String
            ? value.GetString()
            : null;

    public void Dispose() => _http.Dispose();
}
