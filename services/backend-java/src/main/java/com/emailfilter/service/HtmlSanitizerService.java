package com.emailfilter.service;

import org.owasp.html.PolicyFactory;
import org.owasp.html.Sanitizers;
import org.springframework.stereotype.Service;

/**
 * Sanitizes raw HTML before it is persisted (defense layer 1).
 * Restrictive policy: basic formatting + links + tables + images,
 * no scripts, no inline styles, no iframes.
 */
@Service
public class HtmlSanitizerService {

    private static final PolicyFactory POLICY = Sanitizers.FORMATTING
            .and(Sanitizers.BLOCKS)
            .and(Sanitizers.LINKS)
            .and(Sanitizers.TABLES)
            .and(Sanitizers.IMAGES);

    public String sanitize(String rawHtml) {
        if (rawHtml == null || rawHtml.isBlank()) return rawHtml;
        return POLICY.sanitize(rawHtml);
    }
}