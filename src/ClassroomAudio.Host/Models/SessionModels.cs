using System.Net;

namespace ClassroomAudio.Host.Models;

public sealed record SessionOptions(
    string RoomName,
    string JoinCode,
    string TeacherPin,
    string ApiKey,
    string ApiSecret,
    int ApiPort,
    int LiveKitPort,
    IReadOnlyList<IPAddress> LanAddresses)
{
    public string PrimaryHost => LanAddresses.FirstOrDefault()?.ToString() ?? "127.0.0.1";
    public string PrimaryLiveKitUrl => $"ws://{PrimaryHost}:{LiveKitPort}";
}

public sealed class JoinRequest
{
    public string? DisplayName { get; init; }
    public string? Role { get; init; }
    public string? JoinCode { get; init; }
    public string? TeacherPin { get; init; }
}

public sealed record JoinResponse(
    string LiveKitUrl,
    string Room,
    string Identity,
    string Role,
    string AccessToken,
    string ControlToken,
    int TokenExpiresInSeconds);

public sealed record DiscoveryInfo(
    string ServiceName,
    string ServiceType,
    string Room,
    string LiveKitUrl,
    int ApiPort,
    string JoinPath,
    string HostAddress);

public sealed record SpeakRequestItem(string Identity, string DisplayName, DateTimeOffset RequestedAt);

public sealed record ParticipantSummary(
    string Identity,
    string DisplayName,
    string Role,
    string State,
    bool CanPublish,
    int PublishedTrackCount);

public sealed record DashboardSnapshot(
    int ParticipantCount,
    string? ActiveSpeakerIdentity,
    IReadOnlyList<ParticipantSummary> Participants,
    IReadOnlyList<SpeakRequestItem> SpeakingRequests);

public sealed record OperationResult(bool Success, int StatusCode, string Message)
{
    public static OperationResult Ok(string message = "OK") => new(true, 200, message);
    public static OperationResult Conflict(string message) => new(false, 409, message);
    public static OperationResult NotFound(string message) => new(false, 404, message);
    public static OperationResult Forbidden(string message) => new(false, 403, message);
}

public sealed class JoinRejectedException(int statusCode, string message) : Exception(message)
{
    public int StatusCode { get; } = statusCode;
}
