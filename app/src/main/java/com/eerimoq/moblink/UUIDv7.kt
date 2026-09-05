/**
 *     DO WHAT THE FUCK YOU WANT TO PUBLIC LICENSE
 *            Version 2, December 2004
 *
 * Copyright (C) 2024 0xShamil
 *
 * Everyone is permitted to copy and distribute verbatim or modified copies of this license
 * document, and changing it is allowed as long as the name is changed.
 *
 *             DO WHAT THE FUCK YOU WANT TO PUBLIC LICENSE
 *    TERMS AND CONDITIONS FOR COPYING, DISTRIBUTION AND MODIFICATION
 *
 * 0. You just DO WHAT THE FUCK YOU WANT TO.
 */
package com.eerimoq.moblink

import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

object UUIDv7 {
    private val numberGenerator: ThreadLocal<SecureRandom> = ThreadLocal.withInitial {
        SecureRandom()
    }

    // Shared monotonic states
    private val lastMillis = AtomicLong(Long.MIN_VALUE)
    private val lastSeq12 = AtomicInteger(-1)

    /** @return A UUID object representing a UUIDv7 value. */
    fun randomUUID(): UUID {
        val bytes = randomBytesMonotonic()
        val msb = bytes.toLongBE(0)
        val lsb = bytes.toLongBE(8)
        return UUID(msb, lsb)
    }

    /**
     * Generates a 16-byte array. The first 6 bytes contain the current timestamp in milliseconds.
     * The next bytes are random, with specific bits set for version and variant.
     *
     * Layout:
     * - [0..5] : 48-bit Unix epoch milliseconds (timestamp)
     * - [6] : high nibble = version 7, low nibble = rand_a[11:8]
     * - [7] : rand_a[7:0] (12-bit monotonic sequence, wraps mod 4096)
     * - [8] : IETF variant in high 2 bits, lower 6 bits random
     * - [9..15] : remaining 62 bits of randomness across [8..15], except variant bits
     *
     * @return A ByteArray of 16 bytes representing the UUIDv7.
     */
    private fun randomBytesMonotonic(): ByteArray {
        val value = ByteArray(16).also { numberGenerator.get().nextBytes(it) }

        val now = System.currentTimeMillis()
        val prev = lastMillis.get()
        val ts = if (now >= prev) now else prev // clamp to avoid regressions

        value[0] = ((ts ushr 40) and 0xFF).toByte()
        value[1] = ((ts ushr 32) and 0xFF).toByte()
        value[2] = ((ts ushr 24) and 0xFF).toByte()
        value[3] = ((ts ushr 16) and 0xFF).toByte()
        value[4] = ((ts ushr 8) and 0xFF).toByte()
        value[5] = (ts and 0xFF).toByte()

        // If same millisecond as last call, increment 12-bit counter else reseed
        val seq: Int = nextSeq(ts)

        // Set the version to 7 in high nibble of byte 6
        value[6] = (((0x7 shl 4) or ((seq ushr 8) and 0x0F))).toByte()

        // Set low 8 bits of rand_a in byte 7
        value[7] = (seq and 0xFF).toByte()

        // Set the variant to IETF variant
        value[8] = ((value[8].toInt() and 0x3F) or 0x80).toByte()

        return value
    }

    // Big-endian 8-byte to long
    private fun ByteArray.toLongBE(offset: Int = 0): Long {
        return (this[offset].toLong() and 0xFF shl 56) or
            (this[offset + 1].toLong() and 0xFF shl 48) or
            (this[offset + 2].toLong() and 0xFF shl 40) or
            (this[offset + 3].toLong() and 0xFF shl 32) or
            (this[offset + 4].toLong() and 0xFF shl 24) or
            (this[offset + 5].toLong() and 0xFF shl 16) or
            (this[offset + 6].toLong() and 0xFF shl 8) or
            (this[offset + 7].toLong() and 0xFF)
    }

    /**
     * Returns the 12-bit monotonic sequence for the given millisecond:
     * - If [ts] differs from the last seen timestamp, seed with a random 12-bit value.
     * - If [ts] matches, increment modulo 4096 to preserve order for same-ms calls.
     *
     * Uses CAS on [lastMillis]/[lastSeq12] to remain lock-light under contention.
     */
    private fun nextSeq(ts: Long): Int {
        while (true) {
            val lastTs = lastMillis.get()
            val lastSeq = lastSeq12.get()

            if (ts != lastTs) {
                // New millisecond: randomize the starting point to retain entropy
                val seeded = numberGenerator.get().nextInt(1 shl 12)
                if (lastMillis.compareAndSet(lastTs, ts)) {
                    lastSeq12.set(seeded)
                    return seeded
                }
            } else {
                // Same millisecond: increment and wrap in 12 bits
                val next = (lastSeq + 1) and 0x0FFF
                if (lastSeq12.compareAndSet(lastSeq, next)) {
                    return next
                }
            }
            // If either CAS fails, retry with fresh reads
        }
    }
}
