package com.vlad.ducknetview.engine.vpn

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructTimeval
import java.io.FileDescriptor
import java.net.InetAddress

/**
 * A socket that can send an ICMP echo request and read the replies to it.
 *
 * An interface so the relay and the latency prober can both be tested without
 * a device: the only implementation talks to the kernel through
 * [android.system.Os].
 */
interface EchoSocket {

    /** True when the message was handed to the kernel. */
    fun send(message: ByteArray, offset: Int, length: Int, to: InetAddress): Boolean

    /**
     * Blocking read, bounded by the socket's receive timeout. Returns the
     * message length, [TIMED_OUT] when the timeout elapsed with nothing to
     * read, or a negative value on a real failure.
     */
    fun receive(into: ByteArray): Int

    fun close()

    companion object {
        const val TIMED_OUT = 0
    }
}

/**
 * Unprivileged ICMP echo through a Linux "ping" socket.
 *
 * `SOCK_DGRAM`/`IPPROTO_ICMP` is open to ordinary apps on Android — the kernel
 * restricts it to echo, rewrites the identifier to the socket's own port and
 * computes the checksum, so no raw socket and no root is needed. It is a
 * kernel-configuration question (`net.ipv4.ping_group_range`) rather than a
 * guaranteed API, which is why [open] returns null instead of throwing and
 * every caller has a path that works without it.
 */
object PingSockets {

    /**
     * @param protect hands the socket to `VpnService.protect` so an app-wide
     *   TUN does not swallow the proxy's own upstream probes. A relay must
     *   pass it; a probe made while our package is excluded from the TUN need
     *   not, and passes the default.
     */
    fun open(
        ipVersion: Int,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
        protect: (Int) -> Boolean = { true },
    ): EchoSocket? {
        val domain = if (ipVersion == 4) OsConstants.AF_INET else OsConstants.AF_INET6
        val protocol = if (ipVersion == 4) OsConstants.IPPROTO_ICMP else OsConstants.IPPROTO_ICMPV6
        val fd = try {
            Os.socket(domain, OsConstants.SOCK_DGRAM, protocol)
        } catch (e: ErrnoException) {
            // EACCES/EPERM here is the ping_group_range gate, and is the normal
            // answer on a device that does not allow it.
            return null
        }
        return try {
            Os.setsockoptTimeval(
                fd,
                OsConstants.SOL_SOCKET,
                OsConstants.SO_RCVTIMEO,
                StructTimeval.fromMillis(timeoutMs.toLong()),
            )
            // protect() acts on the open file description, which a dup shares,
            // so the duplicate can be closed immediately afterwards. This is
            // the only way to reach VpnService.protect(int) from a descriptor
            // the public API hands back as a FileDescriptor.
            if (!protectFd(fd, protect)) throw ErrnoException("protect", OsConstants.EPERM)
            OsEchoSocket(fd)
        } catch (e: Exception) {
            runCatching { Os.close(fd) }
            null
        }
    }

    private fun protectFd(fd: FileDescriptor, protect: (Int) -> Boolean): Boolean =
        ParcelFileDescriptor.dup(fd).use { protect(it.fd) }

    /** Whether this kernel lets the app open an echo socket at all. */
    fun available(ipVersion: Int = 4): Boolean {
        val socket = open(ipVersion, timeoutMs = 1) ?: return false
        socket.close()
        return true
    }

    const val DEFAULT_TIMEOUT_MS = 1000

    private class OsEchoSocket(private val fd: FileDescriptor) : EchoSocket {

        @Volatile private var closed = false

        override fun send(
            message: ByteArray,
            offset: Int,
            length: Int,
            to: InetAddress,
        ): Boolean = try {
            // Port 0: a ping socket addresses a host, not a service.
            Os.sendto(fd, message, offset, length, 0, to, 0) >= 0
        } catch (e: Exception) {
            false
        }

        override fun receive(into: ByteArray): Int = try {
            Os.recvfrom(fd, into, 0, into.size, 0, null)
        } catch (e: ErrnoException) {
            // A timeout is how a cancelled read gets its chance to notice; it
            // is not a failure and must not tear the flow down.
            if (e.errno == OsConstants.EAGAIN || e.errno == OsConstants.EINTR) {
                EchoSocket.TIMED_OUT
            } else {
                -1
            }
        } catch (e: Exception) {
            -1
        }

        override fun close() {
            if (closed) return
            closed = true
            runCatching { Os.close(fd) }
        }
    }
}
