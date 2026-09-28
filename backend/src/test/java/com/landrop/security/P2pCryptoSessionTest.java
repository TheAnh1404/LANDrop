package com.landrop.security;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

import static org.junit.jupiter.api.Assertions.*;

class P2pCryptoSessionTest {

    @Test
    void testSuccessfulKeyAgreementAndBidirectionalEncryption() throws Exception {
        P2pCryptoSession sessionA = new P2pCryptoSession();
        P2pCryptoSession sessionB = new P2pCryptoSession();

        assertFalse(sessionA.isEstablished());
        assertFalse(sessionB.isEstablished());

        // Key exchange
        byte[] pubA = sessionA.getLocalPublicKeyBytes();
        byte[] pubB = sessionB.getLocalPublicKeyBytes();

        sessionA.completeKeyAgreement(pubB);
        sessionB.completeKeyAgreement(pubA);

        assertTrue(sessionA.isEstablished());
        assertTrue(sessionB.isEstablished());

        // Safety fingerprints must match exactly on both peers
        assertEquals(sessionA.getSecurityFingerprint(), sessionB.getSecurityFingerprint());
        assertEquals(6, sessionA.getSecurityFingerprint().length());

        // A sends to B
        String secretMessageA = "Bảo mật mạng LAN LANDrop tuyệt đối với AES-256-GCM!";
        byte[] ciphertextA = sessionA.encrypt(secretMessageA.getBytes(StandardCharsets.UTF_8));
        assertNotEquals(secretMessageA, new String(ciphertextA, StandardCharsets.UTF_8));

        byte[] decryptedB = sessionB.decrypt(ciphertextA);
        assertEquals(secretMessageA, new String(decryptedB, StandardCharsets.UTF_8));

        // B sends to A
        String secretMessageB = "Xác nhận đã nhận gói tin mã hóa đầu cuối!";
        byte[] ciphertextB = sessionB.encrypt(secretMessageB.getBytes(StandardCharsets.UTF_8));
        byte[] decryptedA = sessionA.decrypt(ciphertextB);
        assertEquals(secretMessageB, new String(decryptedA, StandardCharsets.UTF_8));
    }

    @Test
    void testTamperedCiphertextRejection() throws Exception {
        P2pCryptoSession sessionA = new P2pCryptoSession();
        P2pCryptoSession sessionB = new P2pCryptoSession();

        sessionA.completeKeyAgreement(sessionB.getLocalPublicKeyBytes());
        sessionB.completeKeyAgreement(sessionA.getLocalPublicKeyBytes());

        byte[] ciphertext = sessionA.encrypt("Dữ liệu không được sửa đổi".getBytes(StandardCharsets.UTF_8));

        // Tamper with the last byte
        ciphertext[ciphertext.length - 1] ^= 0x01;

        assertThrows(GeneralSecurityException.class, () -> sessionB.decrypt(ciphertext));
    }

    @Test
    void testUnestablishedSessionThrows() {
        P2pCryptoSession session = new P2pCryptoSession();
        assertThrows(IllegalStateException.class, () -> session.encrypt("test".getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalStateException.class, () -> session.decrypt(new byte[32]));
    }
}
