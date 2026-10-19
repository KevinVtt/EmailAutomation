package com.emailfilter.service;

import com.emailfilter.dto.EmailDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class AIServiceClient {

    private RestTemplate restTemplate = createRestTemplate();

    private static RestTemplate createRestTemplate() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(30000);
        return new RestTemplate(factory);
    }

    @Value("${ai.service.url}")
    private String aiServiceUrl;

    public Map<String, Object> analyzeEmails(List<EmailDTO> emails) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        var entity = new HttpEntity<>(Map.of("emails", emails), headers);

        log.info("AIServiceClient llamando a {}/analyze con {} emails", aiServiceUrl, emails.size());
        var result = restTemplate.exchange(
                aiServiceUrl + "/analyze",
                HttpMethod.POST,
                entity,
                new ParameterizedTypeReference<Map<String, Object>>() {}
        ).getBody();
        log.info("AIServiceClient analyze respuesta: {}", result);
        return result;
    }

    public Map<String, Object> chat(String message, String conversationId, Map<String, Object> scope) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        var body = new java.util.HashMap<String, Object>();
        body.put("message", message);
        body.put("conversation_id", conversationId != null ? conversationId : "");
        body.put("context", List.of());
        if (scope != null) {
            body.put("scope", scope);
        }
        var entity = new HttpEntity<>(body, headers);

        log.info("AIServiceClient llamando a {}/chat con message={}, conversationId={}", aiServiceUrl, message, conversationId);
        long t0 = System.currentTimeMillis();
        var result = restTemplate.exchange(
                aiServiceUrl + "/chat",
                HttpMethod.POST,
                entity,
                new ParameterizedTypeReference<Map<String, Object>>() {}
        ).getBody();
        long t1 = System.currentTimeMillis();
        log.info("AIServiceClient chat respondió en {} ms. response={}, criteria={}", (t1-t0), result != null ? result.get("response") : "null", result != null ? result.get("criteria") : "null");
        return result;
    }

    public String summarizeEmails(List<EmailDTO> emails) {
        if (emails.isEmpty()) {
            return "No hay correos para resumir.";
        }

        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        var entity = new HttpEntity<>(Map.of("emails", emails), headers);

        log.info("AIServiceClient llamando a {}/chat/summarize con {} emails", aiServiceUrl, emails.size());
        var response = restTemplate.exchange(
                aiServiceUrl + "/chat/summarize",
                HttpMethod.POST,
                entity,
                new ParameterizedTypeReference<Map<String, Object>>() {}
        ).getBody();

        var summary = response != null ? (String) response.get("summary") : "";
        log.info("AIServiceClient summarize respuesta: {}", summary);
        return summary;
    }

    public Map<String, Object> rewrite(String draft, String originalSubject, String originalFrom,
                                        String originalBody, String tone, String language, String customRules,
                                        String userName) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        var body = Map.of(
                "draft", draft,
                "original_subject", originalSubject != null ? originalSubject : "",
                "original_from", originalFrom != null ? originalFrom : "",
                "original_body", originalBody != null ? originalBody : "",
                "tone", tone != null ? tone : "formal",
                "language", language != null ? language : "auto",
                "custom_rules", customRules != null ? customRules : "",
                "user_name", userName != null ? userName : ""
        );
        var entity = new HttpEntity<>(body, headers);

        log.info("AIServiceClient llamando a {}/rewrite con tone={}, language={}, draft_len={}", aiServiceUrl, tone, language, draft.length());
        long t0 = System.currentTimeMillis();
        var result = restTemplate.exchange(
                aiServiceUrl + "/rewrite",
                HttpMethod.POST,
                entity,
                new ParameterizedTypeReference<Map<String, Object>>() {}
        ).getBody();
        long t1 = System.currentTimeMillis();
        log.info("AIServiceClient rewrite respondió en {} ms, rewritten_len={}", (t1 - t0),
                result != null && result.get("rewritten") != null ? ((String) result.get("rewritten")).length() : 0);
        return result;
    }

    public Map<String, Object> compose(String instruction, String contactName, String contactCompany,
                                       String contactTopic, String contactNotes, List<String> recentSubjects,
                                       String subjectHint, String tone, String language, String userName) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        var body = new java.util.HashMap<String, Object>();
        body.put("instruction", instruction);
        body.put("contact_name", contactName != null ? contactName : "");
        body.put("contact_company", contactCompany != null ? contactCompany : "");
        body.put("contact_topic", contactTopic != null ? contactTopic : "");
        body.put("contact_notes", contactNotes != null ? contactNotes : "");
        body.put("recent_subjects", recentSubjects != null ? recentSubjects : List.of());
        body.put("subject_hint", subjectHint != null ? subjectHint : "");
        body.put("tone", tone != null ? tone : "formal");
        body.put("language", language != null ? language : "auto");
        body.put("user_name", userName != null ? userName : "");
        var entity = new HttpEntity<>(body, headers);

        log.info("AIServiceClient llamando a {}/compose con tone={}, instruction_len={}",
                aiServiceUrl, tone, instruction != null ? instruction.length() : 0);
        return restTemplate.exchange(
                aiServiceUrl + "/compose",
                HttpMethod.POST,
                entity,
                new ParameterizedTypeReference<Map<String, Object>>() {}
        ).getBody();
    }

    public Map<String, Object> contactProfile(String address, String displayName, List<String> subjects) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        var body = new java.util.HashMap<String, Object>();
        body.put("address", address);
        body.put("display_name", displayName != null ? displayName : "");
        body.put("subjects", subjects != null ? subjects : List.of());
        var entity = new HttpEntity<>(body, headers);

        log.info("AIServiceClient llamando a {}/contact-profile con address={}", aiServiceUrl, address);
        return restTemplate.exchange(
                aiServiceUrl + "/contact-profile",
                HttpMethod.POST,
                entity,
                new ParameterizedTypeReference<Map<String, Object>>() {}
        ).getBody();
    }
}
