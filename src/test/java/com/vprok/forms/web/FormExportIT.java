package com.vprok.forms.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vprok.forms.entity.Element;
import com.vprok.forms.entity.ElementListOption;
import com.vprok.forms.service.ElementAttributeValueService;
import com.vprok.forms.service.ElementListOptionService;
import com.vprok.forms.service.ElementService;
import org.junit.jupiter.api.Test;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Asserts the exact JSON contract documented in docs/json-export-schema.md, using STRICT
 * comparison so an unexpected extra key (or a missing one) fails rather than passing silently.
 * Runs against real Postgres via Testcontainers; skipped automatically without Docker.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class FormExportIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ElementService elementService;

    @Autowired
    private ElementAttributeValueService elementAttributeValueService;

    @Autowired
    private ElementListOptionService elementListOptionService;

    @Test
    void exportsFullPageWithExactJsonShape() throws Exception {
        // Deliberately covers both placements the flexible hierarchy allows: DIRECT_FIELD sits
        // straight under the section, NESTED_NUMBER/NESTED_LIST sit under a subsection.
        Element page = elementService.create(null, "PAGE", "EXPORT_PAGE_1", "Export Page", null);
        Element section = elementService.create(page.getId(), "SECTION", "SEC_A", "Section A", null);
        Element directField = elementService.create(section.getId(), "FIELD_TEXT", "DIRECT_FIELD", "Direct Field", null);
        Element subsection = elementService.create(section.getId(), "SUBSECTION", "SUB_A", "Subsection A", null);
        Element nestedNumber = elementService.create(subsection.getId(), "FIELD_NUMBER", "NESTED_NUMBER", "Nested Number", null);
        Element nestedList = elementService.create(subsection.getId(), "FIELD_LIST", "NESTED_LIST", "Nested List", null);

        // One attribute of each JSON-relevant data type: BOOLEAN, INTEGER, DECIMAL.
        elementAttributeValueService.setValue(section.getId(), "COLLAPSED", "true");
        elementAttributeValueService.setValue(directField.getId(), "MANDATORY", "true");
        elementAttributeValueService.setValue(directField.getId(), "MAX_LENGTH", "50");
        elementAttributeValueService.setValue(nestedNumber.getId(), "MIN_VALUE", "1.5");
        elementAttributeValueService.setValue(nestedList.getId(), "MANDATORY", "false");

        elementListOptionService.create(nestedList.getId(), "RED", "Red", null, false);
        elementListOptionService.create(nestedList.getId(), "BLUE", "Blue", null, true);
        ElementListOption retired = elementListOptionService.create(nestedList.getId(), "GREEN", "Green", null, false);
        elementListOptionService.deactivate(nestedList.getId(), retired.getId());

        String expected = """
                {
                  "id": %d,
                  "code": "EXPORT_PAGE_1",
                  "label": "Export Page",
                  "type": "PAGE",
                  "children": [
                    {
                      "id": %d,
                      "code": "SEC_A",
                      "label": "Section A",
                      "type": "SECTION",
                      "attributes": { "collapsed": true },
                      "children": [
                        {
                          "id": %d,
                          "code": "DIRECT_FIELD",
                          "label": "Direct Field",
                          "type": "FIELD_TEXT",
                          "attributes": { "mandatory": true, "maxLength": 50 }
                        },
                        {
                          "id": %d,
                          "code": "SUB_A",
                          "label": "Subsection A",
                          "type": "SUBSECTION",
                          "children": [
                            {
                              "id": %d,
                              "code": "NESTED_NUMBER",
                              "label": "Nested Number",
                              "type": "FIELD_NUMBER",
                              "attributes": { "minValue": 1.5 }
                            },
                            {
                              "id": %d,
                              "code": "NESTED_LIST",
                              "label": "Nested List",
                              "type": "FIELD_LIST",
                              "attributes": { "mandatory": false },
                              "options": [
                                { "code": "RED", "label": "Red", "isDefault": false },
                                { "code": "BLUE", "label": "Blue", "isDefault": true }
                              ]
                            }
                          ]
                        }
                      ]
                    }
                  ]
                }
                """
                .formatted(
                        page.getId(),
                        section.getId(),
                        directField.getId(),
                        subsection.getId(),
                        nestedNumber.getId(),
                        nestedList.getId());

        String actual = mockMvc.perform(get("/api/export/pages/by-code/{code}", "EXPORT_PAGE_1"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JSONAssert.assertEquals(expected, actual, JSONCompareMode.STRICT);
    }

    @Test
    void exportByIdMatchesExportByCode() throws Exception {
        Element page = elementService.create(null, "PAGE", "EXPORT_PAGE_2", "Export Page 2", null);
        elementService.create(page.getId(), "SECTION", "SEC_B", "Section B", null);

        String byCode = mockMvc.perform(get("/api/export/pages/by-code/{code}", "EXPORT_PAGE_2"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String byId = mockMvc.perform(get("/api/export/pages/by-id/{id}", page.getId()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JSONAssert.assertEquals(byCode, byId, JSONCompareMode.STRICT);
    }

    @Test
    void softDeletedElementsAreExcludedFromExport() throws Exception {
        Element page = elementService.create(null, "PAGE", "EXPORT_PAGE_3", "Export Page 3", null);
        Element keptSection = elementService.create(page.getId(), "SECTION", "SEC_KEEP", "Kept", null);
        Element droppedSection = elementService.create(page.getId(), "SECTION", "SEC_DROP", "Dropped", null);
        // A child of the dropped section: the delete cascade soft-deletes it too, so neither may appear.
        elementService.create(droppedSection.getId(), "FIELD_TEXT", "DROPPED_FIELD", "Dropped Field", null);

        elementService.softDelete(droppedSection.getId());

        String expected = """
                {
                  "id": %d,
                  "code": "EXPORT_PAGE_3",
                  "label": "Export Page 3",
                  "type": "PAGE",
                  "children": [
                    { "id": %d, "code": "SEC_KEEP", "label": "Kept", "type": "SECTION" }
                  ]
                }
                """
                .formatted(page.getId(), keptSection.getId());

        String actual = mockMvc.perform(get("/api/export/pages/by-code/{code}", "EXPORT_PAGE_3"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JSONAssert.assertEquals(expected, actual, JSONCompareMode.STRICT);
    }

    @Test
    void unknownPageCodeReturns404() throws Exception {
        mockMvc.perform(get("/api/export/pages/by-code/{code}", "NO_SUCH_PAGE"))
                .andExpect(status().isNotFound());
    }

    @Test
    void nonPageElementIdReturns404() throws Exception {
        Element page = elementService.create(null, "PAGE", "EXPORT_PAGE_4", "Export Page 4", null);
        Element section = elementService.create(page.getId(), "SECTION", "SEC_C", "Section C", null);

        // A section id is not a page id: exporting it must 404 rather than emit a partial tree.
        mockMvc.perform(get("/api/export/pages/by-id/{id}", section.getId()))
                .andExpect(status().isNotFound());
    }
}
