package org.schabi.newpipe.extractor.services.youtube.sabr

import java.math.BigInteger
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException

/**
 * Pure-Java Ed25519 signature verification (RFC 8032), verify-only.
 *
 * Replaces the Bouncy Castle dependency that was pulled in solely to check the SABR
 * JavaScript policy signature on API levels below 33 (where {@code Signature("Ed25519")}
 * is unavailable). This is a faithful port of the RFC 8032 §6 reference using
 * extended (X:Y:Z:T) coordinates over BigInteger. It is not constant-time and not
 * performance-tuned — that's fine for a one-shot integrity check with no secrets.
 */
internal class Ed25519Verify private constructor() {

    companion object {
        private val ONE: BigInteger = BigInteger.ONE
        private val TWO: BigInteger = BigInteger.valueOf(2)
        private val EIGHT: BigInteger = BigInteger.valueOf(8)
        // p = 2^255 - 19
        private val P: BigInteger = TWO.pow(255).subtract(BigInteger.valueOf(19))
        // group order L = 2^252 + 27742317777372353535851937790883648493
        private val L: BigInteger = TWO.pow(252)
            .add(BigInteger("27742317777372353535851937790883648493"))
        // d = -121665 / 121666 (mod p)
        private val D: BigInteger = BigInteger.valueOf(-121665)
            .multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P)
        // sqrt(-1) mod p
        private val SQRT_M1: BigInteger =
            TWO.modPow(P.subtract(ONE).divide(BigInteger.valueOf(4)), P)
        // base point G in extended coords
        private val G: Array<BigInteger> = makeBasePoint()

        private fun makeBasePoint(): Array<BigInteger> {
            val gy = BigInteger.valueOf(4)
                .multiply(BigInteger.valueOf(5).modInverse(P)).mod(P)
            val gx = recoverX(gy, 0)!!
            return arrayOf(gx, gy, ONE, gx.multiply(gy).mod(P))
        }

        private fun recoverX(y: BigInteger, sign: Int): BigInteger? {
            if (y.compareTo(P) >= 0) return null
            val yy = y.multiply(y).mod(P)
            val x2 = yy.subtract(ONE)
                .multiply(D.multiply(yy).add(ONE).modInverse(P)).mod(P)
            if (x2.signum() == 0) {
                return if (sign != 0) null else BigInteger.ZERO
            }
            var x = x2.modPow(P.add(BigInteger.valueOf(3)).divide(EIGHT), P)
            if (x.multiply(x).subtract(x2).mod(P).signum() != 0) {
                x = x.multiply(SQRT_M1).mod(P)
            }
            if (x.multiply(x).subtract(x2).mod(P).signum() != 0) return null
            if ((if (x.testBit(0)) 1 else 0) != sign) x = P.subtract(x)
            return x
        }

        private fun add(p1: Array<BigInteger>, p2: Array<BigInteger>): Array<BigInteger> {
            val a = p1[1].subtract(p1[0]).multiply(p2[1].subtract(p2[0])).mod(P)
            val b = p1[1].add(p1[0]).multiply(p2[1].add(p2[0])).mod(P)
            val c = TWO.multiply(p1[3]).multiply(p2[3]).multiply(D).mod(P)
            val d = TWO.multiply(p1[2]).multiply(p2[2]).mod(P)
            val e = b.subtract(a)
            val f = d.subtract(c)
            val g = d.add(c)
            val h = b.add(a)
            return arrayOf(
                e.multiply(f).mod(P), g.multiply(h).mod(P),
                f.multiply(g).mod(P), e.multiply(h).mod(P)
            )
        }

        private fun mul(s: BigInteger, point: Array<BigInteger>): Array<BigInteger> {
            var q = arrayOf(BigInteger.ZERO, ONE, ONE, BigInteger.ZERO) // neutral element
            var cur = point
            var remaining = s
            while (remaining.signum() > 0) {
                if (remaining.testBit(0)) q = add(q, cur)
                cur = add(cur, cur)
                remaining = remaining.shiftRight(1)
            }
            return q
        }

        private fun equal(p1: Array<BigInteger>, p2: Array<BigInteger>): Boolean {
            if (p1[0].multiply(p2[2]).subtract(p2[0].multiply(p1[2])).mod(P).signum() != 0) return false
            return p1[1].multiply(p2[2]).subtract(p2[1].multiply(p1[2])).mod(P).signum() == 0
        }

        private fun leToInt(src: ByteArray, off: Int, len: Int): BigInteger {
            val be = ByteArray(len)
            for (i in 0 until len) be[i] = src[off + len - 1 - i]
            return BigInteger(1, be)
        }

        private fun decompress(src: ByteArray, off: Int): Array<BigInteger>? {
            var y = leToInt(src, off, 32)
            val sign = if (y.testBit(255)) 1 else 0
            y = y.clearBit(255)
            val x = recoverX(y, sign) ?: return null
            return arrayOf(x, y, ONE, x.multiply(y).mod(P))
        }

        /** True iff [signature] (64 bytes) is a valid Ed25519 signature of [message] under [publicKey] (32 bytes). */
        internal fun verify(signature: ByteArray?, message: ByteArray?, publicKey: ByteArray?): Boolean {
            if (signature == null || message == null || publicKey == null
                || signature.size != 64 || publicKey.size != 32
            ) {
                return false
            }
            try {
                val pointA = decompress(publicKey, 0) ?: return false
                val pointR = decompress(signature, 0) ?: return false
                val s = leToInt(signature, 32, 32)
                if (s.compareTo(L) >= 0) return false // reject non-canonical S
                val sha = MessageDigest.getInstance("SHA-512")
                sha.update(signature, 0, 32)
                sha.update(publicKey)
                sha.update(message)
                val h = leToInt(sha.digest(), 0, 64).mod(L)
                return equal(mul(s, G), add(pointR, mul(h, pointA)))
            } catch (e: NoSuchAlgorithmException) {
                return false
            }
        }
    }
}
