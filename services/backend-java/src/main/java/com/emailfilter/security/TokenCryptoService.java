package com.emailfilter.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * Cifra los tokens OAuth en reposo (AES-GCM). Valores sin cifrar quedan tal cual
 * (filas legadas / modo dev), identificables porque no empiezan con {@code enc:}.
 */
@Slf4j
@Component
public class TokenCryptoService {

    private static final String PREFIX = "enc:";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public TokenCryptoService(@Value("${TOKEN_ENCRYPTION_KEY:}") String encryptionKey) {
        if (encryptionKey == null || encryptionKey.isBlank()) {
            this.key = null;
            log.warn("TOKEN_ENCRYPTION_KEY no definida: los tokens OAuth se guardan en texto plano (modo dev)");
        } else {
            // SHA-256 de la clave: acepta cualquier longitud de secreto y da exactamente 128 bits.
            this.key = new SecretKeySpec(sha256(encryptionKey), "AES");
        }
    }

    /** {@code enc:<base64(iv||ciphertext)>} o el texto plano si no hay clave. */
    public String encrypt(String plaintext) {
        if (plaintext == null || key == null) {
            return plaintext;
        }
        try {
            var iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            var ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            var combined = new byte[IV_LENGTH + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, IV_LENGTH);
            System.arraycopy(ciphertext, 0, combined, IV_LENGTH, ciphertext.length);
            return PREFIX + Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo cifrar el token OAuth", e);
        }
    }

    /**
     * Devuelve el texto plano. Si no empieza con {@code enc:} (fila legada) o el
     * descifrado falla (clave cambiada/corrupta), se devuelve la entrada sin tocar.
     */
    public String decrypt(String stored) {
        if (stored == null || key == null || !stored.startsWith(PREFIX)) {
            return stored;
        }
        try {
            var combined = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            var iv = Arrays.copyOfRange(combined, 0, IV_LENGTH);
            var ciphertext = Arrays.copyOfRange(combined, IV_LENGTH, combined.length);
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("No se pudo descifrar el token OAuth guardado; se trata como texto plano legado");
            return stored;
        }
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }
}
