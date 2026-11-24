using System.Net;
using System.Net.Http;
using ClassroomAudio.Host.Models;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Server.Kestrel.Core;
using Microsoft.Extensions.Logging;

namespace ClassroomAudio.Host.Core;

/// <summary>LAN API consumed by future Android clients. No endpoint records audio.</summary>
public sealed class LocalApiHost : IAsyncDisposable
{
    private readonly WebApplication _app;

    private LocalApiHost(WebApplication app) => _app = app;

    public static async Task<LocalApiHost> StartAsync(SessionCoordinator coordinator, CancellationToken cancellationToken = default)
    {
        var builder = WebApplication.CreateBuilder(new WebApplicationOptions
        {
            Args = Array.Empty<string>(),
            ContentRootPath = AppContext.BaseDirectory
        });
        builder.Logging.ClearProviders();
        builder.WebHost.ConfigureKestrel(options => options.ListenAnyIP(coordinator.Options.ApiPort));

        var app = builder.Build();
        var joinLimiter = new JoinAttemptLimiter(maximumFailures: 8, window: TimeSpan.FromMinutes(1));
        app.MapGet("/health", () => Results.Ok(new { status = "ok" }));
        app.MapGet("/api/v1/session", (HttpContext context) =>
            Results.Ok(coordinator.GetDiscoveryInfo(GetConnectedHost(context, coordinator.Options))));

        app.MapPost("/api/v1/join", (JoinRequest request, HttpContext context) =>
        {
            var remoteIp = context.Connection.RemoteIpAddress?.MapToIPv4().ToString() ?? "unknown";
            var limiterKey = $"{remoteIp}|{request.Role?.Trim().ToLowerInvariant()}";
            if (joinLimiter.IsBlocked(limiterKey))
                return Results.Json(new { error = "Trop de tentatives; réessayez dans une minute." }, statusCode: StatusCodes.Status429TooManyRequests);

            try
            {
                var host = GetConnectedHost(context, coordinator.Options);
                var response = coordinator.Join(request, host);
                joinLimiter.RecordSuccess(limiterKey);
                return Results.Ok(response);
            }
            catch (JoinRejectedException ex)
            {
                if (ex.StatusCode == StatusCodes.Status401Unauthorized)
                    joinLimiter.RecordFailure(limiterKey);
                return Results.Json(new { error = ex.Message }, statusCode: ex.StatusCode);
            }
        });

        app.MapPost("/api/v1/speaking/request", (HttpContext context) =>
        {
            if (!coordinator.TryAuthenticate(context.Request.Headers.Authorization.ToString(), out var caller) || caller is null)
                return Results.Unauthorized();
            if (caller.Role != "student")
                return Results.StatusCode(StatusCodes.Status403Forbidden);

            var result = coordinator.RequestToSpeak(caller);
            return ToHttpResult(result);
        });

        app.MapGet("/api/v1/speaking/requests", (HttpContext context) =>
        {
            if (!coordinator.TryAuthenticate(context.Request.Headers.Authorization.ToString(), out var caller) || caller is null)
                return Results.Unauthorized();
            if (caller.Role != "teacher")
                return Results.StatusCode(StatusCodes.Status403Forbidden);
            return Results.Ok(coordinator.GetSpeakingRequests());
        });

        app.MapGet("/api/v1/participants", async (HttpContext context, CancellationToken ct) =>
        {
            if (!coordinator.TryAuthenticate(context.Request.Headers["Authorization"].ToString(), out var caller) || caller is null)
                return Results.Unauthorized();
            if (caller.Role != "teacher")
                return Results.StatusCode(StatusCodes.Status403Forbidden);

            try
            {
                return Results.Ok(await coordinator.GetDashboardSnapshotAsync(ct));
            }
            catch (Exception ex) when (ex is HttpRequestException or InvalidOperationException)
            {
                return Results.Json(new { error = ex.Message }, statusCode: StatusCodes.Status503ServiceUnavailable);
            }
        });

        app.MapPost("/api/v1/speakers/{identity}/allow", async (string identity, HttpContext context, CancellationToken ct) =>
        {
            if (!coordinator.TryAuthenticate(context.Request.Headers.Authorization.ToString(), out var caller) || caller is null)
                return Results.Unauthorized();
            if (caller.Role != "teacher")
                return Results.StatusCode(StatusCodes.Status403Forbidden);

            return ToHttpResult(await coordinator.GrantSpeakerAsync(identity, ct));
        });

        app.MapPost("/api/v1/speakers/{identity}/revoke", async (string identity, HttpContext context, CancellationToken ct) =>
        {
            if (!coordinator.TryAuthenticate(context.Request.Headers.Authorization.ToString(), out var caller) || caller is null)
                return Results.Unauthorized();
            if (caller.Role != "teacher")
                return Results.StatusCode(StatusCodes.Status403Forbidden);

            return ToHttpResult(await coordinator.RevokeSpeakerAsync(identity, ct));
        });

        try
        {
            await app.StartAsync(cancellationToken);
            return new LocalApiHost(app);
        }
        catch
        {
            await app.DisposeAsync();
            throw;
        }
    }

    private static IResult ToHttpResult(OperationResult result) =>
        result.Success
            ? Results.Ok(new { message = result.Message })
            : Results.Json(new { error = result.Message }, statusCode: result.StatusCode);

    private static string GetConnectedHost(HttpContext context, ClassroomAudio.Host.Models.SessionOptions options)
    {
        var localAddress = context.Connection.LocalIpAddress;
        if (localAddress is not null
            && !IPAddress.IsLoopback(localAddress)
            && !localAddress.Equals(IPAddress.Any)
            && !localAddress.Equals(IPAddress.IPv6Any))
            return localAddress.MapToIPv4().ToString();

        return options.PrimaryHost;
    }

    public async Task StopAsync(CancellationToken cancellationToken = default) =>
        await _app.StopAsync(cancellationToken);

    public async ValueTask DisposeAsync() => await _app.DisposeAsync();
}
