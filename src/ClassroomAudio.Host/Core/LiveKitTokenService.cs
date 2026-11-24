using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using ClassroomAudio.Host.Models;

namespace ClassroomAudio.Host.Core;

/// <summary>
/// Creates short-lived LiveKit JWTs locally. The API key and secret are generated
/// for each class session and are never sent to a cloud service.
/// </summary>
public sealed class LiveKitTokenService(SessionOptions options)
{
    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web);

    public string CreateParticipantToken(string identity, bool isTeacher)
    {
        var now = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
        var videoGrant = new Dictionary<string, object?>
        {
            ["roomJoin"] = true,
            ["room"] = options.RoomName,
            ["canSubscribe"] = true,
            ["canPublish"] = isTeacher,
            ["canPublishData"] = false
        };

        // Teachers may publish a microphone track. Students are explicitly
        // subscribe-only until the teacher changes their permission server-side.
        if (isTeacher)
            videoGrant["canPublishSources"] = new[] { "microphone" };

        var payload = new Dictionary<string, object?>
        {
            ["iss"] = options.ApiKey,
            ["sub"] = identity,
            ["nbf"] = now - 5,
            ["iat"] = now,
            ["exp"] = now + 4 * 60 * 60,
            ["jti"] = RandomToken(12),
            ["video"] = videoGrant
        };

        return Sign(payload);
    }

    public string CreateRoomAdminToken()
    {
        var now = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
        var payload = new Dictionary<string, object?>
        {
            ["iss"] = options.ApiKey,
            ["sub"] = "classe-audio-host",
            ["nbf"] = now - 5,
            ["iat"] = now,
            ["exp"] = now + 10 * 60,
            ["jti"] = RandomToken(12),
            ["video"] = new Dictionary<string, object?>
            {
                ["roomAdmin"] = true,
                ["roomCreate"] = true,
                ["room"] = options.RoomName
            }
        };

        return Sign(payload);
    }

    private string Sign(object payload)
    {
        var headerBytes = JsonSerializer.SerializeToUtf8Bytes(new { alg = "HS256", typ = "JWT" }, JsonOptions);
        var payloadBytes = JsonSerializer.SerializeToUtf8Bytes(payload, JsonOptions);
        var unsignedToken = $"{Base64Url(headerBytes)}.{Base64Url(payloadBytes)}";
        using var hmac = new HMACSHA256(Encoding.UTF8.GetBytes(options.ApiSecret));
        var signature = hmac.ComputeHash(Encoding.ASCII.GetBytes(unsignedToken));
        return $"{unsignedToken}.{Base64Url(signature)}";
    }

    public static string RandomToken(int byteCount = 32) => Base64Url(RandomNumberGenerator.GetBytes(byteCount));

    public static string Base64Url(ReadOnlySpan<byte> bytes) =>
        Convert.ToBase64String(bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_');
}
