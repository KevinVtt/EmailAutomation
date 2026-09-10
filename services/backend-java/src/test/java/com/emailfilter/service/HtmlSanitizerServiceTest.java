package com.emailfilter.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class HtmlSanitizerServiceTest {

    private final HtmlSanitizerService sanitizer = new HtmlSanitizerService();

    @Test
    void sanitize_null_returnsNull() {
        assertNull(sanitizer.sanitize(null));
    }

    @Test
    void sanitize_blank_returnsBlankPassthrough() {
        assertEquals("", sanitizer.sanitize(""));
        assertEquals("   ", sanitizer.sanitize("   "));
    }

    @Test
    void sanitize_removesScriptTags() {
        String result = sanitizer.sanitize("<p>Hola</p><script>alert('xss')</script>");
        assertEquals("<p>Hola</p>", result);
    }

    @Test
    void sanitize_removesEventHandlerAttributes() {
        String result = sanitizer.sanitize("<p onclick=\"alert('xss')\">Hola</p>");
        assertEquals("<p>Hola</p>", result);
    }

    @Test
    void sanitize_neutralizesJavascriptLinks() {
        // The sanitizer drops the whole <a> element for unsafe schemes — text survives, javascript: is gone
        String result = sanitizer.sanitize("<a href=\"javascript:alert('xss')\">click</a>");
        assertEquals("click", result);
    }

    @Test
    void sanitize_allowsImgWithSrc() {
        String result = sanitizer.sanitize("<img src=\"https://example.com/logo.png\" alt=\"logo\">");
        assertEquals("<img src=\"https://example.com/logo.png\" alt=\"logo\" />", result);
    }
}