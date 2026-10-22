package com.emailfilter.repository;

import com.emailfilter.model.EmailMessage;
import com.emailfilter.model.User;
import com.emailfilter.model.Visto;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.List;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Prueba a nivel SQL que {@code removeSpamLabelByProviderEmailIds} borra el token exacto
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
    void removeSpamLabelByProviderEmailIds_stripsSpamTokenInAnyPosition() {
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

        emailRepository.removeSpamLabelByProviderEmailIds(user.getId(), List.of("only", "leading", "trailing",
                "middle", "clean", "null-labels"));
        entityManager.flush();
        entityManager.clear();

        assertEquals("", labelsOf(user, "only"));
        assertEquals("IMPORTANT", labelsOf(user, "leading"));
        assertEquals("IMPORTANT", labelsOf(user, "trailing"));
        assertEquals("IMPORTANT,CATEGORY_UPDATES", labelsOf(user, "middle"));
        assertEquals("IMPORTANT", labelsOf(user, "clean"));
        assertNull(labelsOf(user, "null-labels"));
    }

    /**
     * "No leído" del dashboard = el proveedor sigue diciendo UNREAD y el usuario
     * nunca lo abrió acá (fila en {@code visto} con visto=true).
     */
    @Test
    void countUnreadByUserId_excludesSeenAndAlreadyReadEmails() {
        var user = entityManager.persist(User.builder()
                .email("unread-stats@example.com")
                .provider("google")
                .build());
        var other = entityManager.persist(User.builder()
                .email("unread-stats-other@example.com")
                .provider("google")
                .build());

        persistEmail(user, "unread-unseen", null);
        persistEmail(user, "unread-seen", null);
        persistEmail(user, "unread-flagged", null, true);
        // Mismo providerEmailId pero de otro usuario: el visto ajeno no debe influir.
        persistEmail(other, "unread-unseen", null);

        entityManager.persist(Visto.builder()
                .user(user).providerEmailId("unread-seen").visto(true).build());
        entityManager.persist(Visto.builder()
                .user(other).providerEmailId("unread-unseen").visto(true).build());
        entityManager.flush();
        entityManager.clear();

        assertEquals(1L, emailRepository.countUnreadByUserId(user.getId()));
        assertEquals(0L, emailRepository.countUnreadByUserId(other.getId()));
    }

    private void persistEmail(User user, String providerEmailId, String labels) {
        persistEmail(user, providerEmailId, labels, false);
    }

    private void persistEmail(User user, String providerEmailId, String labels, boolean isRead) {
        entityManager.persist(EmailMessage.builder()
                .user(user)
                .provider("google")
                .providerEmailId(providerEmailId)
                .labels(labels)
                .isRead(isRead)
                .build());
    }

    private String labelsOf(User user, String providerEmailId) {
        return emailRepository.findByProviderEmailIdAndUserId(providerEmailId, user.getId())
                .orElseThrow()
                .getLabels();
    }
}
