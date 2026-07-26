package org.schabi.newpipe.extractor.services.youtube.sabr;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** RFC 8032 §7.1 known-answer vectors for the pure-Java Ed25519 verifier. */
public class Ed25519VerifyTest {

    private static byte[] hex(final String s) {
        final byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private void vector(final String pub, final String msg, final String sig) {
        assertTrue("valid vector must verify",
                Ed25519Verify.verify(hex(sig), hex(msg), hex(pub)));
    }

    @Test
    public void rfc8032Vector1_emptyMessage() {
        vector("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a",
                "",
                "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e0652249015"
                        + "55fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b");
    }

    @Test
    public void rfc8032Vector2_oneByte() {
        vector("3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c",
                "72",
                "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da"
                        + "085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00");
    }

    @Test
    public void rfc8032Vector3_twoBytes() {
        vector("fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025",
                "af82",
                "6291d657deec24024827e69c3abe01a30ce548a284743a445e3680d7db5ac3ac"
                        + "18ff9b538d16f290ae67f760984dc6594a7c15e9716ed28dc027beceea1ec40a");
    }

    @Test
    public void rejectsTamperedMessage() {
        assertFalse(Ed25519Verify.verify(
                hex("92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da"
                        + "085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00"),
                hex("73"), // was 72
                hex("3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c")));
    }

    @Test
    public void rejectsWrongKey() {
        assertFalse(Ed25519Verify.verify(
                hex("92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da"
                        + "085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00"),
                hex("72"),
                hex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")));
    }

    @Test
    public void rejectsBadLengths() {
        assertFalse(Ed25519Verify.verify(new byte[63], new byte[1], new byte[32]));
        assertFalse(Ed25519Verify.verify(new byte[64], new byte[1], new byte[31]));
    }
}
