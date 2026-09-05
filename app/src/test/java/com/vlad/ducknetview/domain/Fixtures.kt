package com.vlad.ducknetview.domain

import com.vlad.ducknetview.domain.model.AppRow
import com.vlad.ducknetview.domain.model.ClosedConn
import com.vlad.ducknetview.domain.model.ConnRow
import com.vlad.ducknetview.domain.model.ConnState
import com.vlad.ducknetview.domain.model.Exposure
import com.vlad.ducknetview.domain.model.NetSnapshot
import com.vlad.ducknetview.domain.model.NetworkRow
import com.vlad.ducknetview.domain.model.Proto
import com.vlad.ducknetview.domain.model.ServiceRow
import com.vlad.ducknetview.domain.model.Transport
import com.vlad.ducknetview.domain.net.IpScope

/** Row builders shared by the domain tests; every argument has a usable default. */
object Fixtures {

    fun conn(
        key: String = "c",
        proto: Proto = Proto.TCP,
        localAddr: String = "192.168.1.5",
        localPort: Int = 40000,
        remoteAddr: String = "93.184.216.34",
        remotePort: Int = 443,
        state: ConnState = ConnState.ESTABLISHED,
        uid: Int = 10001,
        appLabel: String = "Browser",
        packageName: String = "com.example.browser",
        service: String = "https",
        rxBytes: Long = 0L,
        txBytes: Long = 0L,
        rxBps: Long = 0L,
        txBps: Long = 0L,
        rttMillis: Int = -1,
        firstSeen: Long = 0L,
        network: String = "wifi",
        resolvedHost: String? = null,
        watchlisted: Boolean = false,
    ): ConnRow = ConnRow(
        key = key,
        proto = proto,
        localAddr = localAddr,
        localPort = localPort,
        remoteAddr = remoteAddr,
        remotePort = remotePort,
        state = state,
        uid = uid,
        appLabel = appLabel,
        packageName = packageName,
        service = service,
        scope = IpScope.of(remoteAddr),
        rxBytes = rxBytes,
        txBytes = txBytes,
        rxBps = rxBps,
        txBps = txBps,
        rttMillis = rttMillis,
        firstSeen = firstSeen,
        lastSeen = firstSeen,
        network = network,
        resolvedHost = resolvedHost,
        watchlisted = watchlisted,
    )

    fun app(
        uid: Int = 10001,
        label: String = "Browser",
        packageName: String = "com.example.browser",
        isSystem: Boolean = false,
        connCount: Int = 0,
        rxBps: Long = 0L,
        txBps: Long = 0L,
        sessionRx: Long = 0L,
        sessionTx: Long = 0L,
        todayRx: Long = 0L,
        todayTx: Long = 0L,
    ): AppRow = AppRow(
        uid = uid,
        packageName = packageName,
        label = label,
        isSystem = isSystem,
        connCount = connCount,
        rxBps = rxBps,
        txBps = txBps,
        sessionRx = sessionRx,
        sessionTx = sessionTx,
        todayRx = todayRx,
        todayTx = todayTx,
    )

    fun service(
        proto: Proto = Proto.TCP,
        bindAddr: String = "0.0.0.0",
        port: Int = 8080,
        service: String = "http-alt",
        appLabel: String = "Server",
        uid: Int = 10002,
    ): ServiceRow = ServiceRow(
        proto = proto,
        bindAddr = bindAddr,
        port = port,
        service = service,
        exposure = IpScope.exposureOf(bindAddr),
        uid = uid,
        appLabel = appLabel,
    )

    fun network(
        id: String = "net-1",
        ifaceName: String = "wlan0",
        transport: Transport = Transport.WIFI,
        up: Boolean = true,
        addresses: List<String> = listOf("192.168.1.5"),
        isDefault: Boolean = true,
        gateway: String? = "192.168.1.1",
        dnsServers: List<String> = listOf("192.168.1.1"),
        metered: Boolean = false,
        validated: Boolean = true,
    ): NetworkRow = NetworkRow(
        id = id,
        ifaceName = ifaceName,
        transport = transport,
        up = up,
        addresses = addresses,
        isDefault = isDefault,
        gateway = gateway,
        dnsServers = dnsServers,
        metered = metered,
        validated = validated,
    )

    fun closed(
        row: ConnRow = conn(),
        closedAt: Long = 1_000L,
        lifetimeMillis: Long = 500L,
        finalRx: Long = 0L,
        finalTx: Long = 0L,
    ): ClosedConn = ClosedConn(row, closedAt, lifetimeMillis, finalRx, finalTx)

    fun snapshot(
        atMillis: Long = 1_000L,
        conns: List<ConnRow> = emptyList(),
        apps: List<AppRow> = emptyList(),
        services: List<ServiceRow> = emptyList(),
        networks: List<NetworkRow> = emptyList(),
    ): NetSnapshot = NetSnapshot(
        atMillis = atMillis,
        conns = conns,
        apps = apps,
        services = services,
        networks = networks,
    )

    fun exposure(bind: String): Exposure = IpScope.exposureOf(bind)
}
