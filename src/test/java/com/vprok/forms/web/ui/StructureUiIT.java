package com.vprok.forms.web.ui;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vprok.forms.entity.Element;
import com.vprok.forms.repository.ElementRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Covers the server-rendered tree editor (FORMS-6): recursive tree rendering (the tricky bit -
 * combining th:each with fragment inclusion on the same tag evaluates the fragment before the
 * loop variable is bound, so the fragment call must live on a nested element) and the
 * error-banner path for an invalid parent/child combination. Runs against real Postgres via
 * Testcontainers; skipped automatically without Docker.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class StructureUiIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ElementRepository elementRepository;

    @Test
    void createsRendersTreeAndRejectsInvalidCombinationThroughUi() throws Exception {
        mockMvc.perform(post("/ui/pages").param("code", "UI_PAGE_1").param("label", "UI Page 1"))
                .andExpect(status().is3xxRedirection());

        Element page = elementRepository.findByElementTypeAndCodeAndDeletedAtIsNull("PAGE", "UI_PAGE_1").orElseThrow();

        mockMvc.perform(post("/ui/elements/{id}/children", page.getId())
                        .param("elementType", "SECTION")
                        .param("code", "UI_SEC_1")
                        .param("label", "UI Section One"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/ui/pages/" + page.getId()));

        Element section = elementRepository
                .findByParentElementIdAndDeletedAtIsNullOrderByDisplayOrderAsc(page.getId())
                .get(0);

        mockMvc.perform(post("/ui/elements/{id}/children", section.getId())
                        .param("elementType", "FIELD_TEXT")
                        .param("code", "UI_FLD_1")
                        .param("label", "UI Field One"))
                .andExpect(status().is3xxRedirection());

        // The bug this locks in: recursive tree-node rendering (page -> section -> field) must
        // actually reach the leaf, not throw while resolving the fragment's loop variable.
        mockMvc.perform(get("/ui/pages/{id}", page.getId()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("UI Section One")))
                .andExpect(content().string(containsString("UI Field One")));

        // Server-side hierarchy validation must reject this even though the UI's own dropdown
        // wouldn't offer it - not just a client-side restriction. Flash attributes are
        // session-scoped, so the follow-up GET must reuse the same session the POST used.
        MvcResult rejected = mockMvc.perform(post("/ui/elements/{id}/children", page.getId())
                        .param("elementType", "FIELD_TEXT")
                        .param("code", "SNEAKY")
                        .param("label", "Sneaky"))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        mockMvc.perform(get("/ui/pages/{id}", page.getId())
                        .session((MockHttpSession) rejected.getRequest().getSession()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("PAGE may not contain FIELD_TEXT")));
    }
}
