using System.Net;
using Makaretu.Dns;

namespace ClassroomAudio.Host.Core;

/// <summary>Advertises the local join API through DNS-SD/mDNS; manual IP entry remains available.</summary>
public sealed class MdnsAdvertiser : IDisposable
{
    private readonly ServiceDiscovery _discovery;
    private readonly ServiceProfile _profile;

    public MdnsAdvertiser(int apiPort, IReadOnlyList<IPAddress> addresses)
    {
        _discovery = new ServiceDiscovery();
        _profile = new ServiceProfile(
            "ClasseAudio",
            "_classeaudio._tcp",
            checked((ushort)apiPort),
            addresses);
    }

    public void Start()
    {
        _discovery.Advertise(_profile);
        _discovery.Announce(_profile);
    }

    public void Dispose()
    {
        try { _discovery.Unadvertise(_profile); }
        catch { /* Best-effort goodbye packet; the TTL will also expire. */ }
        _discovery.Dispose();
    }
}
