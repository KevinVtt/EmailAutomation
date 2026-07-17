package com.emailfilter.integration;

import com.emailfilter.model.EmailMessage;
import com.emailfilter.model.User;
import com.emailfilter.repository.EmailRepository;
import com.emailfilter.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class EmailFilterIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("testdb")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.data.redis.host", () -> "localhost");
        registry.add("spring.data.redis.port", () -> "6379");
        registry.add("spring.autoconfigure.exclude", () ->
                "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration," +
                "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EmailRepository emailRepository;

    private User testUser;

    @BeforeEach
    void setUp() {
        emailRepository.deleteAll();
        userRepository.deleteAll();

        testUser = userRepository.save(User.builder()
                .email("test@example.com")
                .name("Test User")
                .provider("google")
                .providerId("12345")
                .build());
    }

    @Test
    void saveAndFindEmails() {
        var email = new EmailMessage();
        email.setUser(testUser);
        email.setProvider("google");
        email.setProviderEmailId("msg-1");
        email.setSubject("Test Subject");
        email.setFromAddress("sender@example.com");
        email.setBodyPreview("This is a test");
        email.setRead(false);
        email.setStarred(true);
        email.setReceivedAt(Instant.now());

        var saved = emailRepository.save(email);
        assertNotNull(saved.getId());

        var found = emailRepository.findByUserIdOrderByReceivedAtDesc(testUser.getId(), org.springframework.data.domain.PageRequest.of(0, 10));
        assertEquals(1, found.getTotalElements());

        var result = emailRepository.findByUserIdOrderByReceivedAtDesc(testUser.getId(), org.springframework.data.domain.PageRequest.of(0, 10));
        var emailDTO = result.getContent().get(0);
        assertEquals("Test Subject", emailDTO.getSubject());
    }
}
