package com.emailfilter.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EmailDTOTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void serializesStarredAndReadWithTheirIsPrefix() throws Exception {
        var dto = EmailDTO.builder().providerEmailId("m1").isStarred(true).isRead(false).build();

        var json = mapper.writeValueAsString(dto);

        assertThat(json)
                .contains("\"isStarred\":true")
                .contains("\"isRead\":false")
                .doesNotContain("\"starred\"")
                .doesNotContain("\"read\"");
    }
}
