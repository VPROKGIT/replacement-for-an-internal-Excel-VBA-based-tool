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
import com.vprok.forms.service.ElementAttributeValueService;
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
 * The server-rendered structure editor: since FORMS-17 a two-pane page editor where the URL
 * carries the page, the selected section and (for the detail pane) the element. Covers recursive
 * tree rendering (the fragment call must sit on a nested element, not beside th:each), the
 * error-banner path, the detail pane for every element type, and that every URL is canonical.
 * Flash attributes are session-scoped, so a GET following a POST reuses the POST's session.
 * Runs against real Postgres via Testcontainers; skipped automatically without Docker.
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

    @Autowired
    private ElementAttributeValueService attributeValues;

    private static String sectionUrl(Element page, Element section) {
        return "/ui/pages/" + page.getId() + "/sections/" + section.getId();
    }

    private static String detailUrl(Element page, Element section, Element element) {
        return sectionUrl(page, section) + "/elements/" + element.getId();
    }

    private static MockHttpSession sessionOf(MvcResult result) {
        return (MockHttpSession) result.getRequest().getSession();
    }

    @Test
    void createsRendersTreeAndRejectsInvalidCombinationThroughUi() throws Exception {
        mockMvc.perform(post("/ui/pages").param("code", "UI_PAGE_1").param("label", "UI Page 1"))
                .andExpect(status().is3xxRedirection());
        Element page = elementRepository.findByElementTypeAndCodeAndDeletedAtIsNull("PAGE", "UI_PAGE_1").orElseThrow();

        // Adding a section from the left pane opens that section.
        MvcResult added = mockMvc.perform(post("/ui/elements/{id}/children", page.getId())
                        .param("elementType", "SECTION")
                        .param("code", "UI_SEC_1")
                        .param("label", "UI Section One"))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        Element section = elementRepository.findByParentElementIdAndDeletedAtIsNullOrderByDisplayOrderAsc(page.getId()).get(0);
        assertThat(added.getResponse().getRedirectedUrl()).isEqualTo(sectionUrl(page, section));

        mockMvc.perform(post("/ui/elements/{id}/children", section.getId())
                        .param("elementType", "FIELD_TEXT")
                        .param("code", "UI_FLD_1")
                        .param("label", "UI Field One"))
                .andExpect(redirectedUrl(sectionUrl(page, section)));

        // Recursive rendering (section -> field) must reach the leaf.
        mockMvc.perform(get(sectionUrl(page, section)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("UI Section One")))
                .andExpect(content().string(containsString("UI Field One")));

        // Server-side validation rejects what the dropdown wouldn't offer; the error returns the
        // user to the section they were on ("from"), in a single redirect so the banner survives.
        MvcResult rejected = mockMvc.perform(post("/ui/elements/{id}/children", page.getId())
                        .param("elementType", "FIELD_TEXT")
                        .param("code", "SNEAKY")
                        .param("label", "Sneaky")
                        .param("from", section.getId().toString()))
                .andExpect(redirectedUrl(sectionUrl(page, section)))
                .andReturn();
        mockMvc.perform(get(sectionUrl(page, section)).session(sessionOf(rejected)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("PAGE may not contain FIELD_TEXT")));
    }

    @Test
    void theEditorIsTwoPanesAndEveryUrlSaysExactlyWhatIsShown() throws Exception {
        Element pageA = elementService.create(null, "PAGE", "UI_TWO_A", "Two-pane A", null);
        Element alpha = elementService.create(pageA.getId(), "SECTION", "UI_ALPHA", "Alpha section", null);
        elementService.create(alpha.getId(), "FIELD_TEXT", "UI_ALPHA_FLD", "Alpha field", null);
        Element beta = elementService.create(pageA.getId(), "SECTION", "UI_BETA", "Beta section", null);
        Element betaSub = elementService.create(beta.getId(), "SUBSECTION", "UI_BETA_SUB", "Beta subsection", null);
        elementService.create(betaSub.getId(), "FIELD_TEXT", "UI_BETA_FLD", "Beta field", null);
        Element pageB = elementService.create(null, "PAGE", "UI_TWO_B", "Two-pane B", null);
        Element gamma = elementService.create(pageB.getId(), "SECTION", "UI_GAMMA", "Gamma section", null);

        // A bare page URL is not what's shown - it redirects to the first section's URL.
        mockMvc.perform(get("/ui/pages/{id}", pageA.getId()))
                .andExpect(redirectedUrl(sectionUrl(pageA, alpha)));

        // Left pane lists every section and marks the selected one; right pane shows only its subtree.
        mockMvc.perform(get(sectionUrl(pageA, alpha)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Alpha section")))
                .andExpect(content().string(containsString("Beta section")))
                .andExpect(content().string(containsString("class=\"selected\"")))
                .andExpect(content().string(containsString("Alpha field")))
                .andExpect(content().string(not(containsString("Beta field"))));
        mockMvc.perform(get(sectionUrl(pageA, beta)))
                .andExpect(content().string(containsString("Beta field")))
                .andExpect(content().string(not(containsString("Alpha field"))));

        // The page switcher lists pages and is a plain GET that lands on a real, bookmarkable URL.
        mockMvc.perform(get(sectionUrl(pageA, alpha)))
                .andExpect(content().string(containsString("Two-pane B")));
        mockMvc.perform(get("/ui/pages/switch").param("id", pageB.getId().toString()))
                .andExpect(redirectedUrl("/ui/pages/" + pageB.getId()));
        mockMvc.perform(get("/ui/pages/{id}", pageB.getId()))
                .andExpect(redirectedUrl(sectionUrl(pageB, gamma)));

        // Inconsistent URLs are sent to the one that really shows the element.
        mockMvc.perform(get(sectionUrl(pageB, alpha)))
                .andExpect(redirectedUrl(sectionUrl(pageA, alpha)));
        mockMvc.perform(get(sectionUrl(pageA, betaSub)))
                .andExpect(redirectedUrl(sectionUrl(pageA, beta)));
        mockMvc.perform(get(detailUrl(pageA, alpha, betaSub)))
                .andExpect(redirectedUrl(detailUrl(pageA, beta, betaSub)));

        // Reordering the selected section keeps it selected; deleting it goes to the new first one.
        mockMvc.perform(post("/ui/elements/{id}/move-up", beta.getId()))
                .andExpect(redirectedUrl(sectionUrl(pageA, beta)));
        mockMvc.perform(post("/ui/elements/{id}/delete", beta.getId()))
                .andExpect(redirectedUrl(sectionUrl(pageA, alpha)));

        // A page without sections has its own (bare) URL and an empty right pane.
        Element emptyPage = elementService.create(null, "PAGE", "UI_TWO_EMPTY", "Empty page", null);
        mockMvc.perform(get("/ui/pages/{id}", emptyPage.getId()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("No sections yet")));
    }

    @Test
    void containersGetADetailPaneAndCanEditTheirOwnAttributes() throws Exception {
        Element page = elementService.create(null, "PAGE", "UI_CONTAINER_ATTRS", "Container attrs", null);
        Element section = elementService.create(page.getId(), "SECTION", "UI_CA_SEC", "Section", null);
        Element subsection = elementService.create(section.getId(), "SUBSECTION", "UI_CA_SUB", "Subsection", null);
        Element map = elementService.create(section.getId(), "MAP", "UI_CA_MAP", "Map", null);
        Element list = elementService.create(section.getId(), "FIELD_LIST", "UI_CA_LIST", "List", null);

        // Every node in the right pane links to its detail pane - containers included.
        mockMvc.perform(get(sectionUrl(page, section)))
                .andExpect(content().string(containsString("href=\"" + detailUrl(page, section, section) + "\"")))
                .andExpect(content().string(containsString("href=\"" + detailUrl(page, section, subsection) + "\"")))
                .andExpect(content().string(containsString("href=\"" + detailUrl(page, section, map) + "\"")));

        // SECTION: COLLAPSED is offered and saves.
        mockMvc.perform(get(detailUrl(page, section, section)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Collapsed by default")));
        mockMvc.perform(post("/ui/elements/{id}/attributes", section.getId()).param("COLLAPSED", "true"))
                .andExpect(redirectedUrl(detailUrl(page, section, section)));

        // SUBSECTION: the same.
        mockMvc.perform(post("/ui/elements/{id}/attributes", subsection.getId()).param("COLLAPSED", "true"))
                .andExpect(redirectedUrl(detailUrl(page, section, subsection)));

        assertThat(attributeValues.list(section.getId()))
                .extracting(v -> v.getAttributeDefinition().getCode() + "=" + v.getValue())
                .containsExactly("COLLAPSED=true");
        assertThat(attributeValues.list(subsection.getId()))
                .extracting(v -> v.getAttributeDefinition().getCode() + "=" + v.getValue())
                .containsExactly("COLLAPSED=true");

        // MAP: none are seeded for it yet, and the pane says so.
        mockMvc.perform(get(detailUrl(page, section, map)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("No attributes are applicable to this element type.")));

        // FIELD_LIST: attributes and list options share the one detail pane.
        mockMvc.perform(get(detailUrl(page, section, list)))
                .andExpect(content().string(containsString("List options")))
                .andExpect(content().string(containsString("New option")));

        // The old per-element URLs still work - they now land on the detail pane.
        mockMvc.perform(get("/ui/elements/{id}/attributes", subsection.getId()))
                .andExpect(redirectedUrl(detailUrl(page, section, subsection)));
        mockMvc.perform(get("/ui/elements/{id}/list-options", list.getId()))
                .andExpect(redirectedUrl(detailUrl(page, section, list) + "#list-options"));
    }

    @Test
    void documentAttributesAreEditableAndTheFormSavesThemTogether() throws Exception {
        mockMvc.perform(post("/ui/pages").param("code", "UI_DOC_PAGE").param("label", "UI Doc Page"));
        Element page = elementRepository.findByElementTypeAndCodeAndDeletedAtIsNull("PAGE", "UI_DOC_PAGE").orElseThrow();
        Element section = elementService.create(page.getId(), "SECTION", "UI_DOC_SEC", "Docs", null);
        Element document = elementService.create(section.getId(), "FIELD_DOCUMENT", "UI_DOSSIER", "Dossier", null);
        String detail = detailUrl(page, section, document);

        // Both attributes are offered in the detail pane, with their hints.
        mockMvc.perform(get(detail))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Confidential")))
                .andExpect(content().string(containsString("Non-confidential version (code)")))
                .andExpect(content().string(containsString("Required when Confidential is true")));

        // Rule broken in one form submit: rejected with a banner, and nothing saved.
        MvcResult rejected = mockMvc.perform(post("/ui/elements/{id}/attributes", document.getId())
                        .param("CONFIDENTIAL", "true")
                        .param("NON_CONFIDENTIAL_VERSION_CODE", ""))
                .andExpect(redirectedUrl(detail))
                .andReturn();
        mockMvc.perform(get(detail).session(sessionOf(rejected)))
                .andExpect(content().string(containsString("must name its non-confidential version")));
        mockMvc.perform(get("/api/elements/{id}/attribute-values", document.getId()))
                .andExpect(content().json("[]"));

        // Both set in the same submit: accepted, whichever the server happens to write first.
        mockMvc.perform(post("/ui/elements/{id}/attributes", document.getId())
                        .param("CONFIDENTIAL", "true")
                        .param("NON_CONFIDENTIAL_VERSION_CODE", "UI_DOSSIER_PUBLIC"))
                .andExpect(redirectedUrl(detail));
        mockMvc.perform(get("/api/elements/{id}/attribute-values", document.getId()))
                .andExpect(content().string(containsString("UI_DOSSIER_PUBLIC")))
                .andExpect(content().string(containsString("CONFIDENTIAL")));
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
        mockMvc.perform(get(sectionUrl(realPage, realSection)))
                .andExpect(content().string(containsString("Use this MAP template")))
                .andExpect(content().string(containsString("One-time copy, not a live link")));
        mockMvc.perform(get(sectionUrl(template, templateSection)))
                .andExpect(content().string(containsString("Template page.")))
                .andExpect(content().string(not(containsString("Use this MAP template"))));

        MvcResult cloned = mockMvc.perform(post("/ui/elements/{id}/clone-map", realSection.getId())
                        .param("sourceMapId", templateMap.getId().toString()))
                .andExpect(redirectedUrl(sectionUrl(realPage, realSection)))
                .andReturn();
        mockMvc.perform(get(sectionUrl(realPage, realSection)).session(sessionOf(cloned)))
                .andExpect(content().string(containsString("independent one-time copy")))
                .andExpect(content().string(containsString("(UI_CONTACT)")))
                .andExpect(content().string(containsString("(UI_EMAIL)")));

        // Marking a real page as a template takes it out of the forms list.
        mockMvc.perform(post("/ui/pages/{id}/template", realPage.getId()).param("template", "true"))
                .andExpect(redirectedUrl(sectionUrl(realPage, realSection)));
        mockMvc.perform(get("/ui/pages"))
                .andExpect(content().string(not(containsString("UI Real Page"))));
    }
}
