package org.schabi.newpipe.extractor.services.youtube.sabr;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Pure-Java Ed25519 signature verification (RFC 8032), verify-only.
 *
 * Replaces the Bouncy Castle dependency that was pulled in solely to check the SABR
 * JavaScript policy signature on API levels below 33 (where {@code Signature("Ed25519")}
 * is unavailable). This is a faithful port of the RFC 8032 §6 reference using
 * extended (X:Y:Z:T) coordinates over BigInteger. It is not constant-time and not
 * performance-tuned — that's fine for a one-shot integrity check with no secrets.
 */
final class Ed25519Verify {
    private Ed25519Verify() {}

    private static final BigInteger ONE = BigInteger.ONE;
    private static final BigInteger TWO = BigInteger.valueOf(2);
    private static final BigInteger EIGHT = BigInteger.valueOf(8);
    // p = 2^255 - 19
    private static final BigInteger P = TWO.pow(255).subtract(BigInteger.valueOf(19));
    // group order L = 2^252 + 27742317777372353535851937790883648493
    private static final BigInteger L = TWO.pow(252)
            .add(new BigInteger("27742317777372353535851937790883648493"));
    // d = -121665 / 121666 (mod p)
    private static final BigInteger D = BigInteger.valueOf(-121665)
            .multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P);
    // sqrt(-1) mod p
    private static final BigInteger SQRT_M1 =
            TWO.modPow(P.subtract(ONE).divide(BigInteger.valueOf(4)), P);
    // base point G in extended coords
    private static final BigInteger[] G = makeBasePoint();

    private static BigInteger[] makeBasePoint() {
        final BigInteger gy = BigInteger.valueOf(4)
                .multiply(BigInteger.valueOf(5).modInverse(P)).mod(P);
        final BigInteger gx = recoverX(gy, 0);
        return new BigInteger[]{gx, gy, ONE, gx.multiply(gy).mod(P)};
    }

    private static BigInteger recoverX(final BigInteger y, final int sign) {
        if (y.compareTo(P) >= 0) return null;
        final BigInteger yy = y.multiply(y).mod(P);
        final BigInteger x2 = yy.subtract(ONE)
                .multiply(D.multiply(yy).add(ONE).modInverse(P)).mod(P);
        if (x2.signum() == 0) {
            return sign != 0 ? null : BigInteger.ZERO;
        }
        BigInteger x = x2.modPow(P.add(BigInteger.valueOf(3)).divide(EIGHT), P);
        if (x.multiply(x).subtract(x2).mod(P).signum() != 0) {
            x = x.multiply(SQRT_M1).mod(P);
        }
        if (x.multiply(x).subtract(x2).mod(P).signum() != 0) return null;
        if ((x.testBit(0) ? 1 : 0) != sign) x = P.subtract(x);
        return x;
    }

    private static BigInteger[] add(final BigInteger[] p1, final BigInteger[] p2) {
        final BigInteger a = p1[1].subtract(p1[0]).multiply(p2[1].subtract(p2[0])).mod(P);
        final BigInteger b = p1[1].add(p1[0]).multiply(p2[1].add(p2[0])).mod(P);
        final BigInteger c = TWO.multiply(p1[3]).multiply(p2[3]).multiply(D).mod(P);
        final BigInteger d = TWO.multiply(p1[2]).multiply(p2[2]).mod(P);
        final BigInteger e = b.subtract(a);
        final BigInteger f = d.subtract(c);
        final BigInteger g = d.add(c);
        final BigInteger h = b.add(a);
        return new BigInteger[]{
                e.multiply(f).mod(P), g.multiply(h).mod(P),
                f.multiply(g).mod(P), e.multiply(h).mod(P)};
    }

    private static BigInteger[] mul(BigInteger s, final BigInteger[] point) {
        BigInteger[] q = {BigInteger.ZERO, ONE, ONE, BigInteger.ZERO}; // neutral element
        BigInteger[] cur = point;
        while (s.signum() > 0) {
            if (s.testBit(0)) q = add(q, cur);
            cur = add(cur, cur);
            s = s.shiftRight(1);
        }
        return q;
    }

    private static boolean equal(final BigInteger[] p1, final BigInteger[] p2) {
        if (p1[0].multiply(p2[2]).subtract(p2[0].multiply(p1[2])).mod(P).signum() != 0) return false;
        return p1[1].multiply(p2[2]).subtract(p2[1].multiply(p1[2])).mod(P).signum() == 0;
    }

    private static BigInteger leToInt(final byte[] src, final int off, final int len) {
        final byte[] be = new byte[len];
        for (int i = 0; i < len; i++) be[i] = src[off + len - 1 - i];
        return new BigInteger(1, be);
    }

    private static BigInteger[] decompress(final byte[] src, final int off) {
        BigInteger y = leToInt(src, off, 32);
        final int sign = y.testBit(255) ? 1 : 0;
        y = y.clearBit(255);
        final BigInteger x = recoverX(y, sign);
        if (x == null) return null;
        return new BigInteger[]{x, y, ONE, x.multiply(y).mod(P)};
    }

    /** True iff {@code signature} (64 bytes) is a valid Ed25519 signature of {@code message} under {@code publicKey} (32 bytes). */
    static boolean verify(final byte[] signature, final byte[] message, final byte[] publicKey) {
        if (signature == null || message == null || publicKey == null
                || signature.length != 64 || publicKey.length != 32) {
            return false;
        }
        try {
            final BigInteger[] pointA = decompress(publicKey, 0);
            if (pointA == null) return false;
            final BigInteger[] pointR = decompress(signature, 0);
            if (pointR == null) return false;
            final BigInteger s = leToInt(signature, 32, 32);
            if (s.compareTo(L) >= 0) return false; // reject non-canonical S
            final MessageDigest sha = MessageDigest.getInstance("SHA-512");
            sha.update(signature, 0, 32);
            sha.update(publicKey);
            sha.update(message);
            final BigInteger h = leToInt(sha.digest(), 0, 64).mod(L);
            return equal(mul(s, G), add(pointR, mul(h, pointA)));
        } catch (final NoSuchAlgorithmException impossible) {
            return false;
        }
    }
}
