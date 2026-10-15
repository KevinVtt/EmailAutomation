package com.emailfilter.repository;

import com.emailfilter.model.EmailMessage;
import com.emailfilter.model.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Prueba a nivel SQL que {@code removeSpamLabelByUserId} borra el token exacto
 * {@code SPAM} de labels en cualquier posición. H2 en modo PostgreSQL porque el
 * esquema usa {@code JSONB} en otra entidad.
 */
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:emailrepo;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password="
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class EmailRepositoryTest {

    @Autowired
    private EmailRepository emailRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void removeSpamLabelByUserId_stripsSpamTokenInAnyPosition() {
        var user = entityManager.persist(User.builder()
                .email("spam-labels@example.com")
                .provider("google")
                .build());

        persistEmail(user, "only", "SPAM");
        persistEmail(user, "leading", "SPAM,IMPORTANT");
        persistEmail(user, "trailing", "IMPORTANT,SPAM");
        persistEmail(user, "middle", "IMPORTANT,SPAM,CATEGORY_UPDATES");
        persistEmail(user, "clean", "IMPORTANT");
        persistEmail(user, "null-labels", null);
        entityManager.flush();
        entityManager.clear();

        emailRepository.removeSpamLabelByUserId(user.getId());
        entityManager.flush();
        entityManager.clear();

        assertEquals("", labelsOf(user, "only"));
        assertEquals("IMPORTANT", labelsOf(user, "leading"));
        assertEquals("IMPORTANT", labelsOf(user, "trailing"));
        assertEquals("IMPORTANT,CATEGORY_UPDATES", labelsOf(user, "middle"));
        assertEquals("IMPORTANT", labelsOf(user, "clean"));
        assertNull(labelsOf(user, "null-labels"));
    }

    private void persistEmail(User user, String providerEmailId, String labels) {
        entityManager.persist(EmailMessage.builder()
                .user(user)
                .provider("google")
                .providerEmailId(providerEmailId)
                .labels(labels)
                .build());
    }

    private String labelsOf(User user, String providerEmailId) {
        return emailRepository.findByProviderEmailIdAndUserId(providerEmailId, user.getId())
                .orElseThrow()
                .getLabels();
    }
}
