package com.emailfilter.util;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Decodificación mínima de quoted-printable (RFC 2045).
 *
 * Gmail devuelve el cuerpo tal cual está en el MIME original, así que una parte
 * con {@code Content-Transfer-Encoding: quoted-printable} sigue teniendo {@code =XX}
 * y saltos suaves después de decodificar el base64. Sin esto se ven cosas como
 * {@code =3D} o {@code Caf=C3=A9} en el lector.
 */
public final class QuotedPrintable {

    private QuotedPrintable() {}

    /** El texto se interpreta como bytes 1:1 (el contenido quoted-printable es ASCII). */
    public static String decode(String input) {
        return decode(input.getBytes(StandardCharsets.ISO_8859_1));
    }

    public static String decode(byte[] input) {
        return decode(input, StandardCharsets.UTF_8);
    }

    /** El charset lo declara el Content-Type de la parte; los escapes QP son siempre ASCII. */
    public static String decode(byte[] input, Charset charset) {
        var out = new ByteArrayOutputStream(input.length);
        for (int i = 0; i < input.length; i++) {
            int b = input[i] & 0xFF;
            if (b != '=') {
                out.write(b);
                continue;
            }
            if (i + 1 < input.length) {
                int next = input[i + 1] & 0xFF;
                // Salto suave: "=" + fin de línea se elimina y la línea se une.
                if (next == '\r' && i + 2 < input.length && input[i + 2] == '\n') { i += 2; continue; }
                if (next == '\n') { i += 1; continue; }
                if (i + 2 < input.length) {
                    int hi = Character.digit((char) next, 16);
                    int lo = Character.digit((char) (input[i + 2] & 0xFF), 16);
                    if (hi >= 0 && lo >= 0) { out.write((hi << 4) | lo); i += 2; continue; }
                }
            }
            out.write(b);
        }
        return out.toString(charset);
    }

    /**
     * Repara cuerpos guardados antes de existir esta decodificación. Sólo actúa con
     * marcas inequívocas de quoted-printable ({@code =3D} o salto suave), así que es
     * inofensivo sobre texto ya decodificado.
     * shortcut: puente para filas viejas; se puede eliminar tras un full sync.
     */
    public static String decodeIfEncoded(String text) {
        if (text == null) return null;
        if (!text.contains("=3D") && !text.contains("=\r\n") && !text.contains("=\n")) return text;
        return decode(text);
    }
}
