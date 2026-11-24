using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;

namespace ClassroomAudio.Host.Core;

public static class NetworkAddressResolver
{
    public static IReadOnlyList<IPAddress> GetLanIPv4Addresses()
    {
        var addresses = NetworkInterface.GetAllNetworkInterfaces()
            .Where(adapter => adapter.OperationalStatus == OperationalStatus.Up)
            .Where(adapter => adapter.NetworkInterfaceType is not NetworkInterfaceType.Loopback
                                                and not NetworkInterfaceType.Tunnel)
            .SelectMany(adapter => adapter.GetIPProperties().UnicastAddresses)
            .Select(item => item.Address)
            .Where(address => address.AddressFamily == AddressFamily.InterNetwork)
            .Where(address => !IPAddress.IsLoopback(address))
            .Where(address => address.GetAddressBytes()[0] != 169 || address.GetAddressBytes()[1] != 254)
            .Distinct()
            .OrderByDescending(IsPrivateIPv4)
            .ThenBy(address => address.ToString(), StringComparer.Ordinal)
            .ToArray();

        return addresses;
    }

    private static bool IsPrivateIPv4(IPAddress address)
    {
        var bytes = address.GetAddressBytes();
        return bytes[0] == 10
            || (bytes[0] == 172 && bytes[1] is >= 16 and <= 31)
            || (bytes[0] == 192 && bytes[1] == 168);
    }
}
