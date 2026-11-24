using System.Collections.Concurrent;

namespace ClassroomAudio.Host.Core;

/// <summary>Small per-IP/per-role limiter for the prototype's six-digit join codes.</summary>
public sealed class JoinAttemptLimiter(int maximumFailures, TimeSpan window)
{
    private readonly ConcurrentDictionary<string, AttemptBucket> _buckets = new(StringComparer.Ordinal);

    public bool IsBlocked(string key)
    {
        var bucket = GetBucket(key);
        lock (bucket.Sync)
        {
            ResetIfExpired(bucket);
            return bucket.Failures >= maximumFailures;
        }
    }

    public void RecordFailure(string key)
    {
        var bucket = GetBucket(key);
        lock (bucket.Sync)
        {
            ResetIfExpired(bucket);
            bucket.Failures++;
        }
    }

    public void RecordSuccess(string key)
    {
        var bucket = GetBucket(key);
        lock (bucket.Sync)
        {
            bucket.StartedAt = DateTimeOffset.UtcNow;
            bucket.Failures = 0;
        }
    }

    private AttemptBucket GetBucket(string key) =>
        _buckets.GetOrAdd(key, _ => new AttemptBucket(DateTimeOffset.UtcNow));

    private void ResetIfExpired(AttemptBucket bucket)
    {
        if (DateTimeOffset.UtcNow - bucket.StartedAt >= window)
        {
            bucket.StartedAt = DateTimeOffset.UtcNow;
            bucket.Failures = 0;
        }
    }

    private sealed class AttemptBucket(DateTimeOffset startedAt)
    {
        public object Sync { get; } = new();
        public DateTimeOffset StartedAt { get; set; } = startedAt;
        public int Failures { get; set; }
    }
}
