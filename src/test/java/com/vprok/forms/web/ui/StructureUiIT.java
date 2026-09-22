package com.vprok.forms.web.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vprok.forms.entity.Element;
import com.vprok.forms.repository.ElementRepository;
import com.vprok.forms.service.ElementService;
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

    @Autowired
    private ElementService elementService;

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

    @Test
    void templatesAreBrowsableAndUsingOneClonesItIntoARealPage() throws Exception {
        mockMvc.perform(post("/ui/templates").param("code", "UI_TPL").param("label", "UI Template"))
                .andExpect(redirectedUrl("/ui/templates"));
        Element template = elementRepository.findByElementTypeAndCodeAndDeletedAtIsNull("PAGE", "UI_TPL").orElseThrow();
        assertThat(template.isTemplate()).isTrue();

        Element templateSection = elementService.create(template.getId(), "SECTION", "UI_TPL_SEC", "Template section", null);
        Element templateMap = elementService.create(templateSection.getId(), "MAP", "UI_CONTACT", "Contact block", null);
        elementService.create(templateMap.getId(), "FIELD_TEXT", "UI_EMAIL", "Email address", null);

        mockMvc.perform(post("/ui/pages").param("code", "UI_REAL").param("label", "UI Real Page"));
        Element realPage = elementRepository.findByElementTypeAndCodeAndDeletedAtIsNull("PAGE", "UI_REAL").orElseThrow();
        Element realSection = elementService.create(realPage.getId(), "SECTION", "UI_REAL_SEC", "Real section", null);

        // The template is kept out of the forms list, and shown - with its MAP and fields - in its own view.
        mockMvc.perform(get("/ui/pages"))
                .andExpect(content().string(containsString("UI Real Page")))
                .andExpect(content().string(not(containsString("UI Template"))));
        mockMvc.perform(get("/ui/templates"))
                .andExpect(content().string(containsString("UI Template")))
                .andExpect(content().string(containsString("Contact block")))
                .andExpect(content().string(containsString("Email address")));

        // The real page's section offers the template, with the one-time-copy warning beside it -
        // and the template page itself does not, even though its section could legally hold a MAP.
        mockMvc.perform(get("/ui/pages/{id}", realPage.getId()))
                .andExpect(content().string(containsString("Use this MAP template")))
                .andExpect(content().string(containsString("One-time copy, not a live link")));
        mockMvc.perform(get("/ui/pages/{id}", template.getId()))
                .andExpect(content().string(containsString("Template page.")))
                .andExpect(content().string(not(containsString("Use this MAP template"))));

        MvcResult cloned = mockMvc.perform(post("/ui/elements/{id}/clone-map", realSection.getId())
                        .param("sourceMapId", templateMap.getId().toString()))
                .andExpect(redirectedUrl("/ui/pages/" + realPage.getId()))
                .andReturn();

        // Flash attributes are session-scoped, so the follow-up GET reuses the POST's session.
        mockMvc.perform(get("/ui/pages/{id}", realPage.getId())
                        .session((MockHttpSession) cloned.getRequest().getSession()))
                .andExpect(content().string(containsString("independent one-time copy")))
                .andExpect(content().string(containsString("(UI_CONTACT)")))
                .andExpect(content().string(containsString("(UI_EMAIL)")));

        // Marking a real page as a template takes it out of the forms list.
        mockMvc.perform(post("/ui/pages/{id}/template", realPage.getId()).param("template", "true"))
                .andExpect(redirectedUrl("/ui/pages/" + realPage.getId()));
        mockMvc.perform(get("/ui/pages"))
                .andExpect(content().string(not(containsString("UI Real Page"))));
    }
}
