package com.healthclaim.casedesk.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/** AES-256-GCM encryption for sensitive columns (KYC numbers, revealable investigator passwords). */
@Component
public class CryptoService {

    private static CryptoService instance;
    private static final String PREFIX = "v1:";
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public CryptoService(@Value("${app.crypto.key:}") String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalStateException(
                "Encryption key missing. Set env APP_CRYPTO_KEY (generate one with: openssl rand -base64 32). "
                + "Keep it safe and backed up — without it, encrypted data cannot be read.");
        }
        byte[] k = Base64.getDecoder().decode(base64Key.trim());
        if (k.length != 32) throw new IllegalStateException("APP_CRYPTO_KEY must be 32 bytes, base64-encoded.");
        this.key = new SecretKeySpec(k, "AES");
        instance = this;
    }

    public static CryptoService get() { return instance; }

    public String encrypt(String plain) {
        try {
            byte[] iv = new byte[12];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            ByteBuffer bb = ByteBuffer.allocate(iv.length + ct.length);
            bb.put(iv).put(ct);
            return PREFIX + Base64.getEncoder().encodeToString(bb.array());
        } catch (Exception e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    public String decrypt(String stored) {
        try {
            if (!stored.startsWith(PREFIX)) throw new IllegalStateException("Unexpected value format");
            byte[] all = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, all, 0, 12));
            return new String(c.doFinal(all, 12, all.length - 12), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Decryption failed (wrong APP_CRYPTO_KEY?)", e);
        }
    }
}
