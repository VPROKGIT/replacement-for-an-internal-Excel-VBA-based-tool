package com.vprok.forms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vprok.forms.entity.Element;
import com.vprok.forms.entity.ElementAttributeValue;
import com.vprok.forms.web.error.InvalidAttributeValueException;
import com.vprok.forms.web.error.InvalidElementHierarchyException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * FIELD_DOCUMENT (FORMS-15) against real PostgreSQL: where it may sit, which attributes apply to
 * it, and the one v1 rule - CONFIDENTIAL = true requires a non-blank NON_CONFIDENTIAL_VERSION_CODE.
 * Each test uses its own page, since the class shares one database.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class DocumentFieldIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private ElementService elementService;

    @Autowired
    private ElementAttributeValueService attributeValues;

    /** A fresh page with one section holding one FIELD_DOCUMENT; returns the document. */
    private Element documentOnNewPage(String pageCode) {
        Element page = elementService.create(null, "PAGE", pageCode, pageCode, null);
        Element section = elementService.create(page.getId(), "SECTION", "SEC", "Section", null);
        return elementService.create(section.getId(), "FIELD_DOCUMENT", "DOC", "Document", null);
    }

    private Map<String, String> valuesOf(Element element) {
        return attributeValues.list(element.getId()).stream()
                .collect(Collectors.toMap(v -> v.getAttributeDefinition().getCode(), ElementAttributeValue::getValue));
    }

    // --- the field type ---------------------------------------------------------------------

    @Test
    void aDocumentFieldSitsWhereverOtherFieldsDo() {
        Element page = elementService.create(null, "PAGE", "DOC_PLACEMENT", "Placement", null);
        Element section = elementService.create(page.getId(), "SECTION", "SEC", "Section", null);
        Element subsection = elementService.create(section.getId(), "SUBSECTION", "SUB", "Subsection", null);
        Element map = elementService.create(section.getId(), "MAP", "MAP", "Map", null);

        assertThatCode(() -> {
            elementService.create(section.getId(), "FIELD_DOCUMENT", "DOC_IN_SECTION", "In section", null);
            elementService.create(subsection.getId(), "FIELD_DOCUMENT", "DOC_IN_SUBSECTION", "In subsection", null);
            elementService.create(map.getId(), "FIELD_DOCUMENT", "DOC_IN_MAP", "In map", null);
        }).doesNotThrowAnyException();

        // ...and nowhere a field may not: not directly on a page, and it is a leaf.
        assertThatThrownBy(() -> elementService.create(page.getId(), "FIELD_DOCUMENT", "DOC_ON_PAGE", "On page", null))
                .isInstanceOf(InvalidElementHierarchyException.class);
        Element document = elementService.getChildren(section.getId()).stream()
                .filter(e -> "DOC_IN_SECTION".equals(e.getCode())).findFirst().orElseThrow();
        assertThatThrownBy(() -> elementService.create(document.getId(), "FIELD_TEXT", "INSIDE_DOC", "Inside", null))
                .isInstanceOf(InvalidElementHierarchyException.class);
    }

    @Test
    void theConfidentialityAttributesApplyToDocumentsOnly() {
        Element page = elementService.create(null, "PAGE", "DOC_APPLICABILITY", "Applicability", null);
        Element section = elementService.create(page.getId(), "SECTION", "SEC", "Section", null);
        Element textField = elementService.create(section.getId(), "FIELD_TEXT", "TXT", "Text", null);

        assertThatThrownBy(() -> attributeValues.setValue(textField.getId(), "CONFIDENTIAL", "true"))
                .isInstanceOf(InvalidAttributeValueException.class)
                .hasMessageContaining("not applicable");
        assertThatThrownBy(() -> attributeValues.setValue(textField.getId(), "NON_CONFIDENTIAL_VERSION_CODE", "X"))
                .isInstanceOf(InvalidAttributeValueException.class)
                .hasMessageContaining("not applicable");
    }

    // --- the conditional-required rule, one attribute at a time (the REST API's path) -----------

    @Test
    void confidentialWithoutACounterpartIsRejectedAndNothingIsSaved() {
        Element document = documentOnNewPage("DOC_RULE_1");

        assertThatThrownBy(() -> attributeValues.setValue(document.getId(), "CONFIDENTIAL", "true"))
                .isInstanceOf(InvalidAttributeValueException.class)
                .hasMessageContaining("NON_CONFIDENTIAL_VERSION_CODE");
        assertThat(valuesOf(document)).isEmpty();
    }

    @Test
    void confidentialIsAcceptedOnceTheCounterpartIsNamed() {
        Element document = documentOnNewPage("DOC_RULE_2");

        attributeValues.setValue(document.getId(), "NON_CONFIDENTIAL_VERSION_CODE", "DOC_PUBLIC");
        attributeValues.setValue(document.getId(), "CONFIDENTIAL", "true");

        assertThat(valuesOf(document))
                .isEqualTo(Map.of("CONFIDENTIAL", "true", "NON_CONFIDENTIAL_VERSION_CODE", "DOC_PUBLIC"));
    }

    @Test
    void theCounterpartCannotBeRemovedOrBlankedWhileTheDocumentIsConfidential() {
        Element document = documentOnNewPage("DOC_RULE_3");
        attributeValues.setValue(document.getId(), "NON_CONFIDENTIAL_VERSION_CODE", "DOC_PUBLIC");
        attributeValues.setValue(document.getId(), "CONFIDENTIAL", "true");

        assertThatThrownBy(() -> attributeValues.deleteValue(document.getId(), "NON_CONFIDENTIAL_VERSION_CODE"))
                .isInstanceOf(InvalidAttributeValueException.class);
        assertThatThrownBy(() -> attributeValues.setValue(document.getId(), "NON_CONFIDENTIAL_VERSION_CODE", ""))
                .isInstanceOf(InvalidAttributeValueException.class);
        assertThatThrownBy(() -> attributeValues.setValue(document.getId(), "NON_CONFIDENTIAL_VERSION_CODE", "   "))
                .isInstanceOf(InvalidAttributeValueException.class);

        // Every rejected change rolled back: the counterpart is still there.
        assertThat(valuesOf(document)).containsEntry("NON_CONFIDENTIAL_VERSION_CODE", "DOC_PUBLIC");

        // The legal order to undo it: un-mark confidential first, then the counterpart may go.
        attributeValues.setValue(document.getId(), "CONFIDENTIAL", "false");
        attributeValues.deleteValue(document.getId(), "NON_CONFIDENTIAL_VERSION_CODE");
        assertThat(valuesOf(document)).isEqualTo(Map.of("CONFIDENTIAL", "false"));
    }

    @Test
    void theRuleOnlyBitesWhenConfidentialIsTrue() {
        Element notConfidential = documentOnNewPage("DOC_RULE_4");
        Element unset = documentOnNewPage("DOC_RULE_5");

        assertThatCode(() -> attributeValues.setValue(notConfidential.getId(), "CONFIDENTIAL", "false"))
                .doesNotThrowAnyException();
        // A counterpart with CONFIDENTIAL unset is allowed; the rule is one-directional.
        assertThatCode(() -> attributeValues.setValue(unset.getId(), "NON_CONFIDENTIAL_VERSION_CODE", "DOC_PUBLIC"))
                .doesNotThrowAnyException();
    }

    // --- the whole form at once (the admin UI's path) --------------------------------------------

    @Test
    void savingBothTogetherWorksWhicheverOrderTheyArriveIn() {
        Element first = documentOnNewPage("DOC_FORM_1");
        Element second = documentOnNewPage("DOC_FORM_2");

        Map<String, String> confidentialFirst = new LinkedHashMap<>();
        confidentialFirst.put("CONFIDENTIAL", "true");
        confidentialFirst.put("NON_CONFIDENTIAL_VERSION_CODE", "DOC_PUBLIC");
        Map<String, String> counterpartFirst = new LinkedHashMap<>();
        counterpartFirst.put("NON_CONFIDENTIAL_VERSION_CODE", "DOC_PUBLIC");
        counterpartFirst.put("CONFIDENTIAL", "true");

        attributeValues.applyValues(first.getId(), confidentialFirst);
        attributeValues.applyValues(second.getId(), counterpartFirst);

        assertThat(valuesOf(first)).containsEntry("CONFIDENTIAL", "true");
        assertThat(valuesOf(second)).containsEntry("CONFIDENTIAL", "true");
    }

    @Test
    void aRejectedFormSaveChangesNothing() {
        Element document = documentOnNewPage("DOC_FORM_3");
        attributeValues.setValue(document.getId(), "CONFIDENTIAL", "false");

        Map<String, String> form = new LinkedHashMap<>();
        form.put("CONFIDENTIAL", "true");
        form.put("NON_CONFIDENTIAL_VERSION_CODE", "");

        assertThatThrownBy(() -> attributeValues.applyValues(document.getId(), form))
                .isInstanceOf(InvalidAttributeValueException.class);
        assertThat(valuesOf(document)).isEqualTo(Map.of("CONFIDENTIAL", "false"));
    }

    // --- the v1 scope cut, pinned on purpose -------------------------------------------------

    /**
     * Deliberately NOT validated in v1 (FORMS-15): whether the named counterpart exists, or is a
     * FIELD_DOCUMENT. This test pins that as intended behaviour, so adding the check later is a
     * visible decision that updates this test - not an accidental "fix".
     */
    @Test
    void theCounterpartReferenceIsDeliberatelyNotCheckedInV1() {
        // A code that exists nowhere on the page.
        Element dangling = documentOnNewPage("DOC_SCOPE_CUT_1");
        assertThatCode(() -> {
            attributeValues.setValue(dangling.getId(), "NON_CONFIDENTIAL_VERSION_CODE", "NO_SUCH_CODE");
            attributeValues.setValue(dangling.getId(), "CONFIDENTIAL", "true");
        }).doesNotThrowAnyException();

        // A code that names a field on the same page which is not a document.
        Element wrongType = documentOnNewPage("DOC_SCOPE_CUT_2");
        elementService.create(wrongType.getParentElement().getId(), "FIELD_TEXT", "A_TEXT_FIELD", "Not a document", null);
        assertThatCode(() -> {
            attributeValues.setValue(wrongType.getId(), "NON_CONFIDENTIAL_VERSION_CODE", "A_TEXT_FIELD");
            attributeValues.setValue(wrongType.getId(), "CONFIDENTIAL", "true");
        }).doesNotThrowAnyException();
    }
}
