package com.emailfilter.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenCryptoServiceTest {

    @Test
    void encryptThenDecrypt_roundTrips() {
        var service = new TokenCryptoService("clave-de-prueba-de-cualquier-largo");

        var encrypted = service.encrypt("ya29.a0AfH6-secret-access-token");

        assertTrue(encrypted.startsWith("enc:"));
        assertNotEquals("ya29.a0AfH6-secret-access-token", encrypted);
        assertEquals("ya29.a0AfH6-secret-access-token", service.decrypt(encrypted));
    }

    @Test
    void encrypt_isPassthroughWhenKeyUnset() {
        var service = new TokenCryptoService("");

        assertEquals("plain-token", service.encrypt("plain-token"));
        assertEquals("plain-token", service.decrypt("plain-token"));
        // Sin clave no se descifra: se asume fila legada.
        assertEquals("enc:whatever", service.decrypt("enc:whatever"));
    }

    @Test
    void decrypt_returnsNonEncryptedValuesUntouched() {
        var service = new TokenCryptoService("some-key");

        assertEquals("ya29.legacy-plaintext", service.decrypt("ya29.legacy-plaintext"));
        assertNull(service.decrypt(null));
        // Base64 inválido tras el prefijo: no puede descifrarse, se devuelve igual.
        assertEquals("enc:!!!not-base64!!!", service.decrypt("enc:!!!not-base64!!!"));
    }
}
