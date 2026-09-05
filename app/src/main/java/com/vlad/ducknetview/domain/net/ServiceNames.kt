package com.vlad.ducknetview.domain.net

import com.vlad.ducknetview.domain.model.Proto

/**
 * Well-known port names. Android has no /etc/services (and no guarantee of a
 * readable one on any device), so the table the TUI treated as a fallback is
 * the only source here.
 */
object ServiceNames {

    private val TCP = mapOf(
        7 to "echo", 9 to "discard", 13 to "daytime", 19 to "chargen",
        20 to "ftp-data", 21 to "ftp", 22 to "ssh", 23 to "telnet", 25 to "smtp",
        37 to "time", 43 to "whois", 49 to "tacacs", 53 to "dns", 70 to "gopher",
        79 to "finger", 80 to "http", 88 to "kerberos", 102 to "iso-tsap",
        110 to "pop3", 111 to "rpcbind", 113 to "ident", 119 to "nntp",
        135 to "msrpc", 139 to "netbios-ssn", 143 to "imap", 179 to "bgp",
        194 to "irc", 389 to "ldap", 427 to "svrloc", 443 to "https",
        445 to "smb", 464 to "kpasswd", 465 to "smtps", 502 to "modbus",
        512 to "exec", 513 to "login", 514 to "shell", 515 to "printer",
        540 to "uucp", 548 to "afp", 554 to "rtsp", 587 to "submission",
        593 to "http-rpc-epmap", 623 to "ipmi", 631 to "ipp", 636 to "ldaps",
        646 to "ldp", 783 to "spamd", 830 to "netconf", 853 to "dns-over-tls",
        860 to "iscsi", 873 to "rsync", 902 to "vmware", 989 to "ftps-data",
        990 to "ftps", 993 to "imaps", 995 to "pop3s", 1080 to "socks",
        1194 to "openvpn", 1433 to "mssql", 1521 to "oracle", 1723 to "pptp",
        1883 to "mqtt", 1935 to "rtmp", 2049 to "nfs", 2082 to "cpanel",
        2083 to "cpanel-ssl", 2086 to "whm", 2087 to "whm-ssl",
        2181 to "zookeeper", 2222 to "ssh-alt", 2375 to "docker",
        2376 to "docker-tls", 2379 to "etcd-client", 2380 to "etcd-peer",
        3128 to "squid", 3260 to "iscsi", 3268 to "globalcatalog",
        3306 to "mysql", 3389 to "rdp", 3478 to "turn", 3690 to "svn",
        4070 to "spotify", 4369 to "epmd", 5000 to "http-alt", 5060 to "sip",
        5061 to "sips", 5222 to "xmpp-client", 5228 to "android-mtalk",
        5229 to "android-mtalk", 5230 to "android-mtalk", 5269 to "xmpp-server",
        5353 to "mdns", 5432 to "postgresql", 5555 to "adb", 5601 to "kibana",
        5672 to "amqp", 5900 to "vnc", 5901 to "vnc-1", 5938 to "teamviewer",
        6000 to "x11", 6379 to "redis", 6443 to "kube-apiserver",
        6667 to "irc", 6881 to "bittorrent", 8000 to "http-alt",
        8008 to "chromecast", 8009 to "chromecast-ssl", 8080 to "http-proxy",
        8081 to "http-alt", 8086 to "influxdb", 8088 to "radan-http",
        8123 to "home-assistant", 8291 to "winbox", 8443 to "https-alt",
        8883 to "mqtts", 8888 to "http-alt", 9000 to "http-alt",
        9090 to "prometheus", 9092 to "kafka", 9100 to "jetdirect",
        9200 to "elasticsearch", 9300 to "elasticsearch-node", 9418 to "git",
        10000 to "webmin", 11211 to "memcached", 15672 to "rabbitmq-mgmt",
        16992 to "amt", 25565 to "minecraft", 27017 to "mongodb",
        32400 to "plex", 33060 to "mysqlx",
    )

    private val UDP = mapOf(
        7 to "echo", 19 to "chargen", 37 to "time", 53 to "dns",
        67 to "dhcp-server", 68 to "dhcp-client", 69 to "tftp",
        88 to "kerberos", 111 to "rpcbind", 123 to "ntp",
        137 to "netbios-ns", 138 to "netbios-dgm", 161 to "snmp",
        162 to "snmptrap", 177 to "xdmcp", 427 to "svrloc", 443 to "quic",
        500 to "isakmp", 514 to "syslog", 520 to "rip", 521 to "ripng",
        546 to "dhcpv6-client", 547 to "dhcpv6-server", 623 to "ipmi",
        631 to "ipp", 853 to "dns-over-quic", 1194 to "openvpn",
        1434 to "mssql-monitor", 1701 to "l2tp", 1812 to "radius",
        1813 to "radius-acct", 1900 to "ssdp", 3478 to "stun",
        3479 to "stun", 3702 to "ws-discovery", 4500 to "ipsec-nat-t",
        4789 to "vxlan", 5060 to "sip", 5061 to "sips", 5350 to "nat-pmp",
        5351 to "nat-pmp", 5353 to "mdns", 5355 to "llmnr", 5683 to "coap",
        5684 to "coaps", 6771 to "bt-lsd", 6881 to "bittorrent",
        17500 to "dropbox-lsd", 27015 to "source-engine",
        51820 to "wireguard", 57621 to "spotify-p2p",
    )

    /** "" when the port is not well known; never guesses from the range. */
    fun of(port: Int, proto: Proto): String {
        if (port <= 0 || port > 65535) return ""
        return when (proto) {
            Proto.UDP -> UDP[port] ?: ""
            Proto.TCP -> TCP[port] ?: ""
            else -> ""
        }
    }

    /** Both tables, for tests and for a "known ports" count in the UI. */
    fun size(): Int = TCP.size + UDP.size
}
