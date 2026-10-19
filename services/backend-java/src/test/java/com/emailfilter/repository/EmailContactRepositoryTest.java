package com.emailfilter.repository;

import com.emailfilter.model.EmailContact;
import com.emailfilter.model.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:contactrepo;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password="
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class EmailContactRepositoryTest {

    @Autowired
    private EmailRepository emailRepository;

    @Autowired
    private EmailContactRepository contactRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void countBySenderSince_groupsTheSameAddressRegardlessOfCasing() {
        var user = entityManager.persist(User.builder()
                .email("contacts@example.com")
                .provider("google")
                .build());
        persistEmail(user, "m1", "Juan@X.com");
        persistEmail(user, "m2", "juan@x.com");
        persistEmail(user, "m3", "otra@y.com");
        entityManager.flush();
        entityManager.clear();

        var rows = emailRepository.countBySenderSince(user.getId(), Instant.EPOCH);

        assertEquals(2, rows.size());
        var juan = rows.stream().filter(r -> "juan@x.com".equals(r[0])).findFirst().orElseThrow();
        assertEquals(2L, ((Number) juan[1]).longValue());
    }

    @Test
    void findMostActive_ordersByCombinedTraffic() {
        var user = entityManager.persist(User.builder()
                .email("traffic@example.com")
                .provider("google")
                .build());
        contactRepository.save(EmailContact.builder().user(user).address("poco@x.com")
                .sentCount(1).receivedCount(1).build());
        contactRepository.save(EmailContact.builder().user(user).address("mucho@x.com")
                .sentCount(10).receivedCount(5).build());
        entityManager.flush();
        entityManager.clear();

        var top = contactRepository.findMostActive(user.getId(), PageRequest.of(0, 10));

        assertEquals("mucho@x.com", top.get(0).getAddress());
    }

    private void persistEmail(User user, String providerEmailId, String fromAddress) {
        entityManager.persist(com.emailfilter.model.EmailMessage.builder()
                .user(user)
                .provider("google")
                .providerEmailId(providerEmailId)
                .fromAddress(fromAddress)
                .receivedAt(Instant.now())
                .build());
    }
}
