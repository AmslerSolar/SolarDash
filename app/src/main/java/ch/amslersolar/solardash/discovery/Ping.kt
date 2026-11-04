// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Amsler Solar GmbH

package ch.amslersolar.solardash.discovery

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import ch.amslersolar.solardash.driver.net.DeviceNetwork
import ch.amslersolar.solardash.driver.net.ipLiteral
import java.net.Inet4Address
import java.net.InetSocketAddress

/**
 * ICMP echo to many addresses at once, through an unprivileged ping socket (Linux allows them to every app on
 * Android: net.ipv4.ping_group_range is 0 to 2147483647). The kernel fills in the identifier and checksum.
 */
object Ping {
    /**
     * The [hosts] (IPv4 literals) that answered within [timeoutMs]; the silent ones are asked twice. Empty when
     * no ping socket can be opened. Blocking.
     */
    fun sweep(hosts: List<String>, timeoutMs: Long = 1_200): Set<String> {
        val targets = hosts.mapNotNull { h -> (ipLiteral(h) as? Inet4Address)?.let { h to it } }
        if (targets.isEmpty()) return emptySet()
        val fd = try {
            Os.socket(OsConstants.AF_INET, OsConstants.SOCK_DGRAM, OsConstants.IPPROTO_ICMP)
        } catch (_: ErrnoException) {
            return emptySet()
        }
        val answered = HashSet<String>()
        try {
            runCatching { DeviceNetwork.current.bind(fd, targets.first().first) }
            val buffer = ByteArray(1500)
            for (round in 0 until TRIES) {
                // Echo request: type 8, code 0, checksum and identifier (kernel), sequence number.
                val request = byteArrayOf(8, 0, 0, 0, 0, 0, 0, (round + 1).toByte())
                for ((host, address) in targets) {
                    if (host !in answered) runCatching { Os.sendto(fd, request, 0, request.size, 0, address, 0) }
                }
                val until = System.nanoTime() + timeoutMs / TRIES * 1_000_000
                while (true) {
                    val left = (until - System.nanoTime()) / 1_000_000
                    if (left <= 0) break
                    val poll = StructPollfd().apply { this.fd = fd; events = OsConstants.POLLIN.toShort() }
                    val ready = runCatching { Os.poll(arrayOf(poll), left.toInt()) }.getOrDefault(0)
                    if (ready <= 0) continue
                    val from = InetSocketAddress(0)
                    val n = runCatching { Os.recvfrom(fd, buffer, 0, buffer.size, 0, from) }.getOrDefault(0)
                    if (n >= 8 && buffer[0] == ECHO_REPLY) from.address?.hostAddress?.let { answered += it }
                }
            }
        } finally {
            runCatching { Os.close(fd) }
        }
        return answered
    }

    private const val TRIES = 2
    private const val ECHO_REPLY: Byte = 0
}
