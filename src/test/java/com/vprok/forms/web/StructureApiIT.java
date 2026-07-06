package com.vprok.forms.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vprok.forms.web.dto.AttributeValueRequest;
import com.vprok.forms.web.dto.AttributeValueResponse;
import com.vprok.forms.web.dto.ElementCreateRequest;
import com.vprok.forms.web.dto.ElementMoveRequest;
import com.vprok.forms.web.dto.ElementResponse;
import com.vprok.forms.web.dto.ListOptionCreateRequest;
import com.vprok.forms.web.dto.ListOptionResponse;
import com.vprok.forms.web.dto.ReorderRequest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * End-to-end tests for the FORMS-5 structure REST API, run against a real
 * PostgreSQL via Testcontainers (Flyway applies V1+V2 first). Uses MockMvc
 * (in-process, no real HTTP server/sockets) rather than TestRestTemplate.
 * Skipped automatically without Docker. Each test uses its own uniquely-coded
 * page since the whole class shares one database across methods.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class StructureApiIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private Long createElement(Long parentId, String elementType, String code, String label) throws Exception {
        ElementCreateRequest request = new ElementCreateRequest(parentId, elementType, code, label, null);
        MvcResult result = mockMvc.perform(post("/api/elements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), ElementResponse.class).id();
    }

    @Test
    void createsHierarchyAndRetrievesThroughApi() throws Exception {
        Long pageId = createElement(null, "PAGE", "API_PAGE_1", "API Page 1");
        Long sectionId = createElement(pageId, "SECTION", "SEC_1", "Section 1");

        mockMvc.perform(get("/api/elements/{id}", sectionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.label").value("Section 1"))
                .andExpect(jsonPath("$.parentElementId").value(pageId))
                .andExpect(jsonPath("$.pageId").value(pageId));

        mockMvc.perform(get("/api/elements/{id}/children", pageId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(sectionId));
    }

    @Test
    void rejectsInvalidParentChildCombination() throws Exception {
        Long pageId = createElement(null, "PAGE", "API_PAGE_2", "API Page 2");

        ElementCreateRequest badRequest = new ElementCreateRequest(pageId, "FIELD_TEXT", "F1", "Bad field", null);
        mockMvc.perform(post("/api/elements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(badRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("PAGE")))
                .andExpect(jsonPath("$.detail", containsString("FIELD_TEXT")));
    }

    @Test
    void rejectsAttributeNotApplicableToElementType() throws Exception {
        Long pageId = createElement(null, "PAGE", "API_PAGE_3", "API Page 3");
        Long sectionId = createElement(pageId, "SECTION", "SEC_3", "Section 3");
        Long fieldId = createElement(sectionId, "FIELD_NUMBER", "FLD_NUM", "Number Field");

        // REGEX_PATTERN is only applicable to FIELD_TEXT per V2 seed data, not FIELD_NUMBER.
        mockMvc.perform(put("/api/elements/{id}/attribute-values/REGEX_PATTERN", fieldId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AttributeValueRequest("^[0-9]+$"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("REGEX_PATTERN")))
                .andExpect(jsonPath("$.detail", containsString("not applicable")));
    }

    @Test
    void rejectsAttributeValueNotMatchingDataType() throws Exception {
        Long pageId = createElement(null, "PAGE", "API_PAGE_4", "API Page 4");
        Long sectionId = createElement(pageId, "SECTION", "SEC_4", "Section 4");
        Long fieldId = createElement(sectionId, "FIELD_NUMBER", "FLD_NUM_2", "Number Field 2");

        // MAX_VALUE is DECIMAL-typed; "not-a-number" cannot parse as one.
        mockMvc.perform(put("/api/elements/{id}/attribute-values/MAX_VALUE", fieldId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AttributeValueRequest("not-a-number"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("not-a-number")));
    }

    @Test
    void setsAndListsValidAttributeValue() throws Exception {
        Long pageId = createElement(null, "PAGE", "API_PAGE_5", "API Page 5");
        Long sectionId = createElement(pageId, "SECTION", "SEC_5", "Section 5");
        Long fieldId = createElement(sectionId, "FIELD_TEXT", "FLD_TXT", "Text Field");

        mockMvc.perform(put("/api/elements/{id}/attribute-values/MANDATORY", fieldId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AttributeValueRequest("true"))))
                .andExpect(status().isOk());

        MvcResult result = mockMvc.perform(get("/api/elements/{id}/attribute-values", fieldId))
                .andExpect(status().isOk())
                .andReturn();
        AttributeValueResponse[] values = objectMapper.readValue(result.getResponse().getContentAsString(), AttributeValueResponse[].class);

        assertThat(values).hasSize(1);
        assertThat(values[0].attributeCode()).isEqualTo("MANDATORY");
        assertThat(values[0].value()).isEqualTo("true");
    }

    @Test
    void softDeletedElementNeverReturnedByApi() throws Exception {
        Long pageId = createElement(null, "PAGE", "API_PAGE_6", "API Page 6");
        Long sectionId = createElement(pageId, "SECTION", "SEC_6", "Section 6");

        mockMvc.perform(delete("/api/elements/{id}", sectionId)).andExpect(status().isNoContent());

        mockMvc.perform(get("/api/elements/{id}", sectionId)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/elements/{id}/children", pageId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void listOptionsCanBeCreatedListedAndDeactivated() throws Exception {
        Long pageId = createElement(null, "PAGE", "API_PAGE_7", "API Page 7");
        Long sectionId = createElement(pageId, "SECTION", "SEC_7", "Section 7");
        Long fieldId = createElement(sectionId, "FIELD_LIST", "FLD_LIST", "List Field");

        MvcResult created = mockMvc.perform(post("/api/elements/{id}/list-options", fieldId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ListOptionCreateRequest("OPT_A", "Option A", null, null))))
                .andExpect(status().isCreated())
                .andReturn();
        Long optionId = objectMapper.readValue(created.getResponse().getContentAsString(), ListOptionResponse.class).id();

        mockMvc.perform(get("/api/elements/{id}/list-options", fieldId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("OPT_A"));

        mockMvc.perform(delete("/api/elements/{elementId}/list-options/{optionId}", fieldId, optionId))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/elements/{id}/list-options", fieldId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        mockMvc.perform(get("/api/elements/{id}/list-options", fieldId).param("includeInactive", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("OPT_A"));
    }

    @Test
    void reordersSiblingsByDisplayOrder() throws Exception {
        Long pageId = createElement(null, "PAGE", "API_PAGE_8", "API Page 8");
        Long sectionX = createElement(pageId, "SECTION", "SEC_X", "Section X");
        Long sectionY = createElement(pageId, "SECTION", "SEC_Y", "Section Y");

        mockMvc.perform(patch("/api/elements/{id}/children/reorder", pageId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ReorderRequest(List.of(sectionY, sectionX)))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/elements/{id}/children", pageId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(sectionY))
                .andExpect(jsonPath("$[1].id").value(sectionX));
    }

    @Test
    void movesElementAndPropagatesPageIdRejectsInvalidMoves() throws Exception {
        Long pageA = createElement(null, "PAGE", "API_PAGE_9A", "API Page 9A");
        Long sectionA = createElement(pageA, "SECTION", "SEC_9A", "Section 9A");
        Long pageB = createElement(null, "PAGE", "API_PAGE_9B", "API Page 9B");

        mockMvc.perform(patch("/api/elements/{id}/move", sectionA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ElementMoveRequest(pageB))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parentElementId").value(pageB))
                .andExpect(jsonPath("$.pageId").value(pageB));

        // Moving a SECTION under a FIELD_TEXT isn't an allowed combination.
        Long sectionUnderB = createElement(pageB, "SECTION", "SEC_9B", "Section 9B");
        Long fieldTextUnderB = createElement(sectionUnderB, "FIELD_TEXT", "FLD_9B", "Field 9B");
        mockMvc.perform(patch("/api/elements/{id}/move", sectionA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ElementMoveRequest(fieldTextUnderB))))
                .andExpect(status().isBadRequest());
    }
}
