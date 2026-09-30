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
import com.vprok.forms.entity.GridPosition;
import com.vprok.forms.repository.ElementRepository;
import com.vprok.forms.service.ElementAttributeValueService;
import com.vprok.forms.service.ElementService;
import com.vprok.forms.service.UiAttributeEntryView;
import com.vprok.forms.service.UiAttributeService;
import java.util.List;
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
 * The server-rendered structure editor: since FORMS-18 a three-column page editor (sections |
 * the selected section as nested boxes | the inspector) where the URL carries the page, the
 * selected section and (for the inspector) the element. Covers recursive tree rendering (the
 * fragment call must sit on a nested element, not beside th:each), the error-banner path, the
 * inspector for every element type, returning to what was on screen after an action, and that
 * every URL is canonical.
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

    @Autowired
    private UiAttributeService uiAttributes;

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

        // Adding anything else opens it in the inspector, scrolled to it in the section.
        MvcResult addedField = mockMvc.perform(post("/ui/elements/{id}/children", section.getId())
                        .param("elementType", "FIELD_TEXT")
                        .param("code", "UI_FLD_1")
                        .param("label", "UI Field One"))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        Element field = elementRepository.findByParentElementIdAndDeletedAtIsNullOrderByDisplayOrderAsc(section.getId()).get(0);
        assertThat(addedField.getResponse().getRedirectedUrl()).isEqualTo(detailUrl(page, section, field) + "#el-" + field.getId());

        // Recursive rendering (section -> field) must reach the leaf.
        mockMvc.perform(get(sectionUrl(page, section)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("UI Section One")))
                .andExpect(content().string(containsString("UI Field One")));

        // Server-side validation rejects what the add buttons wouldn't offer; the error returns the
        // user to the section they were on ("section"), in a single redirect so the banner survives.
        MvcResult rejected = mockMvc.perform(post("/ui/elements/{id}/children", page.getId())
                        .param("elementType", "FIELD_TEXT")
                        .param("code", "SNEAKY")
                        .param("label", "Sneaky")
                        .param("section", section.getId().toString()))
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

        // Every node in the middle pane links to the inspector - containers included.
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

        // FIELD_LIST: attributes and list options share the one inspector.
        mockMvc.perform(get(detailUrl(page, section, list)))
                .andExpect(content().string(containsString("List options")))
                .andExpect(content().string(containsString("New option")));

        // The old per-element URLs still work - they now land on the inspector.
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

        // Both attributes are offered in the inspector, with their hints.
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
    void theInspectorOpensBesideTheSectionAndActionsReturnToWhatWasOnScreen() throws Exception {
        Element page = elementService.create(null, "PAGE", "UI_INSPECT", "Inspect page", null);
        Element section = elementService.create(page.getId(), "SECTION", "UI_IN_SEC", "Inspect section", null);
        Element subsection = elementService.create(section.getId(), "SUBSECTION", "UI_IN_SUB", "Inspect subsection", null);
        Element first = elementService.create(subsection.getId(), "FIELD_TEXT", "UI_IN_FIRST", "First field", null);
        Element second = elementService.create(subsection.getId(), "FIELD_BOOLEAN", "UI_IN_SECOND", "Second field", null);
        Element map = elementService.create(section.getId(), "MAP", "UI_IN_MAP", "Inspect map", null);

        // The whole section stays visible next to the inspector, with the selected element marked.
        mockMvc.perform(get(detailUrl(page, section, first)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("class=\"inspector\"")))
                .andExpect(content().string(containsString("Inspect subsection")))
                .andExpect(content().string(containsString("Second field")))
                .andExpect(content().string(containsString("class=\"field-row is-selected\" id=\"el-" + first.getId() + "\"")));

        // One add button per allowed child type, all field types behind one "Field" button.
        mockMvc.perform(get(sectionUrl(page, section)))
                .andExpect(content().string(containsString("+ Field")))
                .andExpect(content().string(containsString("+ Subsection")))
                .andExpect(content().string(containsString("+ Map")))
                .andExpect(content().string(not(containsString("class=\"inspector\""))));

        // Moving a field while another is in the inspector keeps the inspector open.
        String withFirstOpen = detailUrl(page, section, first);
        mockMvc.perform(post("/ui/elements/{id}/move-up", second.getId())
                        .param("section", section.getId().toString())
                        .param("selected", first.getId().toString()))
                .andExpect(redirectedUrl(withFirstOpen + "#el-" + second.getId()));
        assertThat(elementService.getChildren(subsection.getId())).extracting(Element::getCode)
                .containsExactly("UI_IN_SECOND", "UI_IN_FIRST");

        // Renaming from the inspector stays in the inspector.
        mockMvc.perform(post("/ui/elements/{id}/edit", first.getId())
                        .param("label", "First field, renamed")
                        .param("section", section.getId().toString())
                        .param("selected", first.getId().toString()))
                .andExpect(redirectedUrl(withFirstOpen));
        assertThat(elementService.getActiveOrThrow(first.getId()).getLabel()).isEqualTo("First field, renamed");

        // Deleting another element keeps the inspector; deleting the element in it closes it.
        mockMvc.perform(post("/ui/elements/{id}/delete", map.getId())
                        .param("section", section.getId().toString())
                        .param("selected", first.getId().toString()))
                .andExpect(redirectedUrl(withFirstOpen));
        mockMvc.perform(post("/ui/elements/{id}/delete", first.getId())
                        .param("section", section.getId().toString())
                        .param("selected", first.getId().toString()))
                .andExpect(redirectedUrl(sectionUrl(page, section)));
    }

    @Test
    void subsectionsNestInsideSubsections() throws Exception {
        Element page = elementService.create(null, "PAGE", "UI_NEST", "Nest page", null);
        Element section = elementService.create(page.getId(), "SECTION", "UI_NEST_SEC", "Nest section", null);
        Element outer = elementService.create(section.getId(), "SUBSECTION", "UI_NEST_OUTER", "Outer subsection", null);

        MvcResult added = mockMvc.perform(post("/ui/elements/{id}/children", outer.getId())
                        .param("elementType", "SUBSECTION")
                        .param("code", "UI_NEST_INNER")
                        .param("label", "Inner subsection")
                        .param("section", section.getId().toString()))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        Element inner = elementService.getChildren(outer.getId()).get(0);
        assertThat(inner.getElementType()).isEqualTo("SUBSECTION");
        assertThat(added.getResponse().getRedirectedUrl()).isEqualTo(detailUrl(page, section, inner) + "#el-" + inner.getId());
        elementService.create(inner.getId(), "FIELD_TEXT", "UI_NEST_FLD", "Deep field", null);

        // Both levels render, the field inside the inner one, and anything at any depth is shown
        // under its top-level section.
        mockMvc.perform(get(sectionUrl(page, section)))
                .andExpect(content().string(containsString("Outer subsection")))
                .andExpect(content().string(containsString("Inner subsection")))
                .andExpect(content().string(containsString("Deep field")));
        mockMvc.perform(get(sectionUrl(page, inner)))
                .andExpect(redirectedUrl(sectionUrl(page, section)));
    }

    @Test
    void deletingAPageFromTheSwitcherSoftDeletesItAndOpensTheNextPage() throws Exception {
        Element first = elementService.create(null, "PAGE", "UI_DEL_1", "Delete me first", null);
        Element firstSection = elementService.create(first.getId(), "SECTION", "UI_DEL_1_SEC", "S", null);
        elementService.create(firstSection.getId(), "FIELD_TEXT", "UI_DEL_1_FLD", "F", null);
        Element second = elementService.create(null, "PAGE", "UI_DEL_2", "Delete me second", null);
        Element secondSection = elementService.create(second.getId(), "SECTION", "UI_DEL_2_SEC", "S", null);

        // The confirmation names the page and how much goes with it.
        mockMvc.perform(get(sectionUrl(first, firstSection)))
                .andExpect(content().string(containsString("Delete page Delete me first (UI_DEL_1) and its 2 element(s)?")));

        // Pages are listed by code, so UI_DEL_2 moves up into UI_DEL_1's place.
        MvcResult deleted = mockMvc.perform(post("/ui/elements/{id}/delete", first.getId())
                        .param("section", firstSection.getId().toString()))
                .andExpect(redirectedUrl(sectionUrl(second, secondSection)))
                .andReturn();
        mockMvc.perform(get(sectionUrl(second, secondSection)).session(sessionOf(deleted)))
                .andExpect(content().string(containsString("Deleted page &quot;Delete me first&quot; (UI_DEL_1).")))
                .andExpect(content().string(not(containsString(">Delete me first<"))));

        // A soft delete: the row is still there, and the code is free to use again.
        assertThat(elementRepository.findById(first.getId()).orElseThrow().getDeletedAt()).isNotNull();
        mockMvc.perform(post("/ui/pages").param("code", "UI_DEL_1").param("label", "Reused code"))
                .andExpect(status().is3xxRedirection());
        assertThat(elementRepository.findByElementTypeAndCodeAndDeletedAtIsNull("PAGE", "UI_DEL_1")).isPresent();
    }

    @Test
    void theTemplateUiIsGoneButTemplatePagesStayHidden() throws Exception {
        Element realPage = elementService.create(null, "PAGE", "UI_NO_TPL", "No templates here", null);
        Element realSection = elementService.create(realPage.getId(), "SECTION", "UI_NO_TPL_SEC", "Section", null);
        Element template = elementService.create(null, "PAGE", "UI_OLD_TPL", "Old template page", null);
        template.setTemplate(true);
        elementRepository.save(template);

        mockMvc.perform(get(sectionUrl(realPage, realSection)))
                .andExpect(content().string(not(containsString("/ui/templates"))))
                .andExpect(content().string(not(containsString("Mark as template"))))
                .andExpect(content().string(not(containsString("Use this MAP template"))))
                .andExpect(content().string(not(containsString("Old template page"))));
        mockMvc.perform(get("/ui/templates")).andExpect(status().isNotFound());
        mockMvc.perform(get("/ui/pages")).andExpect(content().string(not(containsString("Old template page"))));
    }

    @Test
    void aMatrixIsDrawnAsAGridWhoseCellsCanBeFilledAndRearranged() throws Exception {
        Element page = elementService.create(null, "PAGE", "UI_MATRIX", "Matrix page", null);
        Element section = elementService.create(page.getId(), "SECTION", "UI_MX_SEC", "Matrix section", null);
        Element matrix = elementService.create(section.getId(), "MATRIX", "UI_MX_GRID", "Contact grid", null);
        Element wide = elementService.create(matrix.getId(), "FIELD_TEXTAREA", "UI_MX_WIDE", "Remarks", null,
                new GridPosition(1, 1, 1, 2));

        // A real grid: two columns, the field placed across both, and an extra empty row with an
        // add button per cell carrying that cell. Grid children have no move-up/move-down.
        mockMvc.perform(get(sectionUrl(page, section)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("2 columns")))
                .andExpect(content().string(containsString("grid-template-columns: repeat(2, minmax(0, 1fr));")))
                .andExpect(content().string(containsString("grid-row: 1 / span 1; grid-column: 1 / span 2;")))
                .andExpect(content().string(containsString("data-row=\"2\" data-column=\"2\"")))
                .andExpect(content().string(containsString("name=\"row\" value=\"2\"")))
                .andExpect(content().string(not(containsString("/ui/elements/" + wide.getId() + "/move-up"))));

        // Adding into a chosen empty cell puts the field there and opens it in the inspector,
        // which offers its position.
        MvcResult added = mockMvc.perform(post("/ui/elements/{id}/children", matrix.getId())
                        .param("elementType", "FIELD_TEXT")
                        .param("code", "UI_MX_CELL")
                        .param("label", "Cell field")
                        .param("row", "2")
                        .param("column", "2")
                        .param("section", section.getId().toString()))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        Element cell = elementRepository.findByElementTypeAndCodeAndDeletedAtIsNull("FIELD_TEXT", "UI_MX_CELL").orElseThrow();
        assertThat(cell.getGridPosition()).isEqualTo(GridPosition.cell(2, 2));
        assertThat(added.getResponse().getRedirectedUrl()).isEqualTo(detailUrl(page, section, cell) + "#el-" + cell.getId());
        mockMvc.perform(get(detailUrl(page, section, cell)))
                .andExpect(content().string(containsString("Position in grid")))
                .andExpect(content().string(containsString("The grid has 2 columns.")));

        // The position form (and a drag and drop, which posts the same form) moves it; a taken
        // space is refused with a banner and nothing changes.
        mockMvc.perform(post("/ui/elements/{id}/position", cell.getId())
                        .param("row", "3").param("column", "1").param("rowSpan", "2").param("columnSpan", "1")
                        .param("section", section.getId().toString()))
                .andExpect(redirectedUrl(sectionUrl(page, section) + "#el-" + cell.getId()));
        assertThat(elementService.getActiveOrThrow(cell.getId()).getGridPosition()).isEqualTo(new GridPosition(3, 1, 2, 1));

        MvcResult refused = mockMvc.perform(post("/ui/elements/{id}/position", cell.getId())
                        .param("row", "1").param("column", "2").param("rowSpan", "1").param("columnSpan", "1")
                        .param("section", section.getId().toString())
                        .param("selected", cell.getId().toString()))
                .andExpect(redirectedUrl(detailUrl(page, section, cell) + "#el-" + cell.getId()))
                .andReturn();
        mockMvc.perform(get(detailUrl(page, section, cell)).session(sessionOf(refused)))
                .andExpect(content().string(containsString("That space is taken by Remarks (UI_MX_WIDE).")));
        assertThat(elementService.getActiveOrThrow(cell.getId()).getGridPosition()).isEqualTo(new GridPosition(3, 1, 2, 1));

        // The matrix's own settings include its column count.
        mockMvc.perform(get(detailUrl(page, section, matrix)))
                .andExpect(content().string(containsString("Columns")))
                .andExpect(content().string(not(containsString("Position in grid"))));
    }

    @Test
    void aFieldsInspectorHasAUiAttributesTabThatStaysOpenAcrossElements() throws Exception {
        Element page = elementService.create(null, "PAGE", "UI_TABS", "Tabs", null);
        Element section = elementService.create(page.getId(), "SECTION", "UI_TABS_SEC", "Tabs section", null);
        Element email = elementService.create(section.getId(), "FIELD_TEXT", "UI_TABS_EMAIL", "Email", null);
        Element phone = elementService.create(section.getId(), "FIELD_NUMBER", "UI_TABS_PHONE", "Phone", null);
        Element sub = elementService.create(section.getId(), "SUBSECTION", "UI_TABS_SUB", "Tabs subsection", null);
        MockHttpSession session = new MockHttpSession();

        // A field opens on Parameters, with both tabs offered.
        mockMvc.perform(get(detailUrl(page, section, email)).session(session))
                .andExpect(content().string(containsString("inspector-tabs")))
                .andExpect(content().string(containsString("Save attributes")))
                .andExpect(content().string(not(containsString("data-ui-box"))));
        // A container has no UI attributes, so no tabs.
        mockMvc.perform(get(detailUrl(page, section, sub)).session(session))
                .andExpect(content().string(not(containsString("inspector-tabs"))));

        // Choosing the UI attributes tab shows only that tab...
        mockMvc.perform(get(detailUrl(page, section, email)).param("tab", "ui").session(session))
                .andExpect(content().string(containsString("data-ui-box")))
                .andExpect(content().string(containsString("No UI attributes yet.")))
                .andExpect(content().string(not(containsString("Save attributes"))));
        // ...and it stays open for the next field picked, whatever its type.
        mockMvc.perform(get(detailUrl(page, section, phone)).session(session))
                .andExpect(content().string(containsString("data-ui-box")));
        // A container in between shows its parameters, without forgetting the choice.
        mockMvc.perform(get(detailUrl(page, section, sub)).session(session))
                .andExpect(content().string(containsString("Save attributes")))
                .andExpect(content().string(not(containsString("data-ui-box"))));
        mockMvc.perform(get(detailUrl(page, section, email)).session(session))
                .andExpect(content().string(containsString("data-ui-box")));

        // Choosing Parameters again is remembered the same way.
        mockMvc.perform(get(detailUrl(page, section, email)).param("tab", "parameters").session(session))
                .andExpect(content().string(containsString("Save attributes")));
        mockMvc.perform(get(detailUrl(page, section, phone)).session(session))
                .andExpect(content().string(not(containsString("data-ui-box"))));
    }

    @Test
    void uiAttributeLinesAreAddedSelectedSavedMovedAndDeletedFromTheBox() throws Exception {
        Element page = elementService.create(null, "PAGE", "UI_BOX", "Box", null);
        Element section = elementService.create(page.getId(), "SECTION", "UI_BOX_SEC", "Box section", null);
        Element field = elementService.create(section.getId(), "FIELD_TEXT", "UI_BOX_FLD", "Box field", null);
        String uiTab = detailUrl(page, section, field) + "?tab=ui";

        // ADD: a line appears above the box, and the box comes back empty with SAVE/DELETE disabled.
        mockMvc.perform(post("/ui/elements/{id}/ui-attributes", field.getId())
                        .param("TARGET_VALUE", "email").param("ACTION_TYPE", "VALIDATE").param("UI_LABEL", ""))
                .andExpect(redirectedUrl(uiTab));
        mockMvc.perform(get(uiTab))
                .andExpect(content().string(containsString("TAR_VAL &quot;email&quot; ; ACT_TYP &quot;VALIDATE&quot;")))
                .andExpect(content().string(containsString("title=\"Select a line first\"")))
                .andExpect(content().string(not(containsString("value=\"email\""))));

        // ADD with an empty box: refused, with the reason, back on the tab.
        MvcResult empty = mockMvc.perform(post("/ui/elements/{id}/ui-attributes", field.getId()).param("TARGET_VALUE", " "))
                .andExpect(redirectedUrl(uiTab))
                .andReturn();
        mockMvc.perform(get(uiTab).session(sessionOf(empty)))
                .andExpect(content().string(containsString("A UI attribute entry needs at least one value")));

        mockMvc.perform(post("/ui/elements/{id}/ui-attributes", field.getId()).param("UI_LABEL", "Second"))
                .andExpect(redirectedUrl(uiTab));
        List<UiAttributeEntryView> entries = uiAttributes.list(field.getId());
        Long first = entries.get(0).id();
        Long second = entries.get(1).id();

        // Clicking a line loads it into the box, and SAVE/DELETE now act on it.
        mockMvc.perform(get(uiTab + "&entry=" + first))
                .andExpect(content().string(containsString("value=\"email\"")))
                .andExpect(content().string(containsString("/ui/elements/" + field.getId() + "/ui-attributes/" + first + "/delete")))
                .andExpect(content().string(not(containsString("title=\"Select a line first\""))));

        // SAVE updates that line, which stays selected.
        mockMvc.perform(post("/ui/elements/{id}/ui-attributes/{entry}", field.getId(), first)
                        .param("TARGET_VALUE", "phone").param("ACTION_TYPE", "VALIDATE"))
                .andExpect(redirectedUrl(uiTab + "&entry=" + first));
        assertThat(uiAttributes.list(field.getId()).get(0).summary()).isEqualTo("TAR_VAL \"phone\" ; ACT_TYP \"VALIDATE\"");

        // The arrows reorder, keeping whichever line was selected.
        mockMvc.perform(post("/ui/elements/{id}/ui-attributes/{entry}/move-down", field.getId(), first)
                        .param("entry", first.toString()))
                .andExpect(redirectedUrl(uiTab + "&entry=" + first));
        assertThat(uiAttributes.list(field.getId())).extracting(UiAttributeEntryView::id).containsExactly(second, first);

        // DELETE removes the line; the box comes back empty.
        mockMvc.perform(post("/ui/elements/{id}/ui-attributes/{entry}/delete", field.getId(), first))
                .andExpect(redirectedUrl(uiTab));
        assertThat(uiAttributes.list(field.getId())).extracting(UiAttributeEntryView::id).containsExactly(second);
    }
}
