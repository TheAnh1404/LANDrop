package com.landrop.security;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.*;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * High-performance, zero-dependency End-to-End P2P Encryption Session.
 * <p>
 * Provides forward secrecy via ephemeral Elliptic Curve Diffie-Hellman (ECDH, NIST P-256)
 * key agreement and authenticated payload encryption via AES-256-GCM with a 128-bit authentication tag.
 */
public final class P2pCryptoSession {

    private static final String EC_ALGORITHM = "EC";
    private static final String EC_CURVE = "secp256r1";
    private static final String KEY_AGREEMENT_ALGORITHM = "ECDH";
    private static final String AES_GCM_CIPHER = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final int GCM_IV_LENGTH_BYTES = 12;

    private final KeyPair localKeyPair;
    private final SecureRandom secureRandom;
    private SecretKey sharedAesKey;
    private String securityFingerprint;

    /**
     * Initializes a new cryptographic session with an ephemeral ECDH keypair.
     */
    public P2pCryptoSession() {
        this.secureRandom = new SecureRandom();
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance(EC_ALGORITHM);
            kpg.initialize(new ECGenParameterSpec(EC_CURVE), secureRandom);
            this.localKeyPair = kpg.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to initialize ECDH keypair generator", e);
        }
    }

    /**
     * Gets the encoded X.509 bytes of the local public key to share with the remote peer.
     *
     * @return the DER-encoded public key bytes
     */
    public byte[] getLocalPublicKeyBytes() {
        return localKeyPair.getPublic().getEncoded();
    }

    /**
     * Completes key agreement using the remote peer's public key bytes.
     * Derives a 256-bit AES session key and a short safety fingerprint.
     *
     * @param remotePublicKeyBytes the DER-encoded public key bytes of the remote peer
     * @throws GeneralSecurityException if key decoding or agreement fails
     */
    public synchronized void completeKeyAgreement(byte[] remotePublicKeyBytes) throws GeneralSecurityException {
        if (this.sharedAesKey != null) {
            return;
        }
        KeyFactory kf = KeyFactory.getInstance(EC_ALGORITHM);
        PublicKey remotePublicKey = kf.generatePublic(new X509EncodedKeySpec(remotePublicKeyBytes));

        KeyAgreement ka = KeyAgreement.getInstance(KEY_AGREEMENT_ALGORITHM);
        ka.init(localKeyPair.getPrivate());
        ka.doPhase(remotePublicKey, true);
        byte[] sharedSecret = ka.generateSecret();

        // Derive 256-bit AES key via SHA-256
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        byte[] aesKeyBytes = sha256.digest(sharedSecret);
        this.sharedAesKey = new SecretKeySpec(aesKeyBytes, "AES");

        // Generate 6-digit numeric verification fingerprint from the key hash
        byte[] fingerprintHash = sha256.digest(aesKeyBytes);
        int num = ((fingerprintHash[0] & 0xFF) << 16) | ((fingerprintHash[1] & 0xFF) << 8) | (fingerprintHash[2] & 0xFF);
        this.securityFingerprint = String.format("%06d", num % 1_000_000);
    }

    /**
     * Checks if the session key has been successfully negotiated.
     */
    public boolean isEstablished() {
        return sharedAesKey != null;
    }

    /**
     * Gets the human-readable 6-digit safety fingerprint for user cross-verification.
     */
    public String getSecurityFingerprint() {
        return securityFingerprint != null ? securityFingerprint : "------";
    }

    /**
     * Encrypts plaintext bytes using AES-256-GCM.
     * Prepend a random 12-byte IV to the ciphertext.
     *
     * @param plaintext the unencrypted data
     * @return 12-byte IV + ciphertext (with 16-byte authentication tag appended)
     * @throws GeneralSecurityException if encryption fails
     */
    public byte[] encrypt(byte[] plaintext) throws GeneralSecurityException {
        if (!isEstablished()) {
            throw new IllegalStateException("Crypto session is not established. Complete key agreement first.");
        }
        byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
        secureRandom.nextBytes(iv);

        Cipher cipher = Cipher.getInstance(AES_GCM_CIPHER);
        cipher.init(Cipher.ENCRYPT_MODE, sharedAesKey, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
        byte[] ciphertext = cipher.doFinal(plaintext);

        byte[] result = new byte[iv.length + ciphertext.length];
        System.arraycopy(iv, 0, result, 0, iv.length);
        System.arraycopy(ciphertext, 0, result, iv.length, ciphertext.length);
        return result;
    }

    /**
     * Decrypts and authenticates AES-256-GCM encrypted payload.
     *
     * @param encryptedData 12-byte IV + ciphertext
     * @return the decrypted plaintext
     * @throws GeneralSecurityException if decryption fails or authentication tag is invalid (tampered)
     */
    public byte[] decrypt(byte[] encryptedData) throws GeneralSecurityException {
        if (!isEstablished()) {
            throw new IllegalStateException("Crypto session is not established. Complete key agreement first.");
        }
        if (encryptedData == null || encryptedData.length < GCM_IV_LENGTH_BYTES + 16) {
            throw new IllegalArgumentException("Ciphertext too short to contain valid IV and GCM tag");
        }
        byte[] iv = Arrays.copyOfRange(encryptedData, 0, GCM_IV_LENGTH_BYTES);
        byte[] ciphertext = Arrays.copyOfRange(encryptedData, GCM_IV_LENGTH_BYTES, encryptedData.length);

        Cipher cipher = Cipher.getInstance(AES_GCM_CIPHER);
        cipher.init(Cipher.DECRYPT_MODE, sharedAesKey, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
        return cipher.doFinal(ciphertext);
    }
}
