package com.vprok.forms.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.logout;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vprok.forms.entity.Element;
import com.vprok.forms.repository.ElementRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Sign-in (FORMS-12), with a MockMvc built by hand so none of TestSecurityDefaults' "signed in,
 * with a CSRF token" defaults apply. The test user is configured in
 * src/test/resources/config/application.properties.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class SecurityIT {

    private static final String USER = "tester";
    private static final String PASSWORD = "tester-password";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ElementRepository elementRepository;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void theEditorAsksForASignInAndOnlyTheRightPasswordGetsIn() throws Exception {
        mockMvc.perform(get("/ui/pages"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
        mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Sign in")));

        mockMvc.perform(formLogin().user(USER).password("wrong"))
                .andExpect(redirectedUrl("/login?error"));
        mockMvc.perform(formLogin().user(USER).password(PASSWORD))
                .andExpect(redirectedUrl("/ui/pages"));

        // Sent to sign in on the way to a page: back to exactly that page afterwards, no "?continue".
        // The query in the URL itself (not .param()), as a browser sends it, so it is part of what gets saved.
        MvcResult interrupted = mockMvc.perform(get("/ui/pages/switch?id=1")).andReturn();
        mockMvc.perform(post("/login").with(csrf())
                        .param("username", USER).param("password", PASSWORD)
                        .session((MockHttpSession) interrupted.getRequest().getSession()))
                .andExpect(redirectedUrl("http://localhost/ui/pages/switch?id=1"));

        // Signed in: the editor shows who, with a way out.
        mockMvc.perform(get("/ui/pages").with(user(USER)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Sign out")));
        mockMvc.perform(logout())
                .andExpect(redirectedUrl("/login?logout"));

        // What the login page itself needs stays reachable.
        mockMvc.perform(get("/css/app.css")).andExpect(status().isOk());
        mockMvc.perform(get("/js/editor.js")).andExpect(status().isOk());
    }

    @Test
    void everyEditorChangeNeedsACsrfTokenAndRecordsWhoMadeIt() throws Exception {
        mockMvc.perform(post("/ui/pages").with(user(USER)).param("code", "SEC_NO_TOKEN").param("label", "No token"))
                .andExpect(status().isForbidden());
        assertThat(elementRepository.findByElementTypeAndCodeAndDeletedAtIsNull("PAGE", "SEC_NO_TOKEN")).isEmpty();

        mockMvc.perform(post("/ui/pages").with(user("alice")).with(csrf()).param("code", "SEC_AUDITED").param("label", "Audited"))
                .andExpect(status().is3xxRedirection());
        Element page = elementRepository.findByElementTypeAndCodeAndDeletedAtIsNull("PAGE", "SEC_AUDITED").orElseThrow();
        assertThat(page.getCreatedBy()).isEqualTo("alice");
        assertThat(page.getUpdatedBy()).isEqualTo("alice");

        // Every form the editor renders carries the token - including the one drag and drop posts.
        mockMvc.perform(get("/ui/pages/{id}", page.getId()).with(user(USER)))
                .andExpect(content().string(containsString("name=\"_csrf\"")));
    }

    @Test
    void theApiAndExportTakeBasicCredentialsWithoutASession() throws Exception {
        mockMvc.perform(get("/api/pages"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", containsString("Basic")));
        mockMvc.perform(get("/api/pages").with(httpBasic(USER, "wrong")))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/pages").with(httpBasic(USER, PASSWORD)))
                .andExpect(status().isOk());

        // Tools post JSON with Basic credentials and no CSRF token.
        mockMvc.perform(post("/api/elements").with(httpBasic(USER, PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"elementType\": \"PAGE\", \"code\": \"SEC_API_PAGE\", \"label\": \"From the API\"}"))
                .andExpect(status().isCreated());
        assertThat(elementRepository.findByElementTypeAndCodeAndDeletedAtIsNull("PAGE", "SEC_API_PAGE").orElseThrow().getCreatedBy())
                .isEqualTo(USER);
        mockMvc.perform(get("/api/export/pages/by-code/{code}", "SEC_API_PAGE").with(httpBasic(USER, PASSWORD)))
                .andExpect(status().isOk());

        // No session is created, so an API call never signs a browser in to the editor.
        assertThat(mockMvc.perform(get("/api/pages").with(httpBasic(USER, PASSWORD)))
                .andReturn().getRequest().getSession(false)).isNull();
    }
}
