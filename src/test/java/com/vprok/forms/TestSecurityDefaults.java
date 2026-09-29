package com.vprok.forms;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Since FORMS-12 every request needs a signed-in user, and every editor POST a CSRF token. The
 * tests written before that are about structure, not security, so the auto-configured MockMvc
 * sends every request as a signed-in user with a valid token by default. Security itself is
 * tested in SecurityIT, with a MockMvc that has none of these defaults.
 *
 * <p>A plain {@code @Configuration} under the application's package, so component scanning picks
 * it up for every {@code @SpringBootTest} without each test importing it.
 */
@Configuration
public class TestSecurityDefaults {

    @Bean
    MockMvcBuilderCustomizer signedInByDefault() {
        return builder -> builder.defaultRequest(get("/").with(user("tester")).with(csrf()));
    }
}
