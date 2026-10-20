package com.emailfilter.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class QuotedPrintableTest {

    @Test
    void decode_translatesHexEscapesAndRemovesSoftLineBreaks() {
        assertEquals("a=b", QuotedPrintable.decode("a=3Db"));
        assertEquals("Café", QuotedPrintable.decode("Caf=C3=A9"));
        assertEquals("unir líneas", QuotedPrintable.decode("unir =\r\nl=C3=ADneas"));
    }

    @Test
    void decode_keepsAnInvalidEscapeAsIs() {
        assertEquals("a=ZZb", QuotedPrintable.decode("a=ZZb"));
        assertEquals("suelto=", QuotedPrintable.decode("suelto="));
    }

    @Test
    void decode_usesTheCharsetThePartDeclares() {
        var latin1 = "Caf=E9 y m=E1s".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        assertEquals("Café y más", QuotedPrintable.decode(latin1, java.nio.charset.StandardCharsets.ISO_8859_1));
    }

    @Test
    void decodeIfEncoded_decodesTextWithQuotedPrintableMarkers() {
        assertEquals("Café = listo", QuotedPrintable.decodeIfEncoded("Caf=C3=A9 =3D listo"));
    }

    @Test
    void decodeIfEncoded_leavesAlreadyDecodedTextUntouched() {
        assertEquals("<p>Precio: 10 €</p>", QuotedPrintable.decodeIfEncoded("<p>Precio: 10 €</p>"));
        assertNull(QuotedPrintable.decodeIfEncoded(null));
    }

    @Test
    void decodeIfEncoded_ignoresShortEncodedTextWithoutMarkers() {
        // Limitación conocida (ver el javadoc): sin "=3D" ni salto suave no hay forma
        // segura de distinguirlo de texto ya decodificado, así que no se toca.
        assertEquals("Hola Caf=C3=A9", QuotedPrintable.decodeIfEncoded("Hola Caf=C3=A9"));
    }
}
