package com.vprok.forms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vprok.forms.entity.Element;
import com.vprok.forms.entity.UiAttributeDefinition;
import com.vprok.forms.entity.UiAttributeEntry;
import com.vprok.forms.repository.ElementRepository;
import com.vprok.forms.web.error.InvalidAttributeValueException;
import com.vprok.forms.web.error.ResourceNotFoundException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * UI attribute entries (FORMS-22) against real PostgreSQL: the seeded kinds and element types,
 * what an entry may hold, and keeping the entries in order through add, update, delete and move.
 * Every test builds its own page, since the class shares one database and page codes are unique.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class UiAttributeIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private UiAttributeService uiAttributeService;

    @Autowired
    private ElementService elementService;

    @Autowired
    private MapTemplateService mapTemplateService;

    @Autowired
    private ElementRepository elementRepository;

    /** A page with one section; returns the section. */
    private Element section(String pageCode) {
        Element page = elementService.create(null, "PAGE", pageCode, pageCode, null);
        return elementService.create(page.getId(), "SECTION", pageCode + "_SEC", "Section", null);
    }

    private List<String> summaries(Long elementId) {
        return uiAttributeService.list(elementId).stream().map(UiAttributeEntryView::summary).toList();
    }

    @Test
    void theSevenKindsAreSeededInTheSpecifiedOrderAndApplyToFieldsOnly() {
        assertThat(uiAttributeService.definitions()).extracting(UiAttributeDefinition::getCode).containsExactly(
                "UI_PARAMETER", "TARGET_VALUE", "TARGET_NODE_DEF_ID", "UI_LABEL", "ACTION_TYPE",
                "TARGET_DATA_NODE_FIELD", "TARGET_AVAILABLE_FIELD_INDEX");

        for (String fieldType : List.of("FIELD_TEXT", "FIELD_TEXTAREA", "FIELD_NUMBER", "FIELD_DATE",
                "FIELD_BOOLEAN", "FIELD_LIST", "FIELD_DOCUMENT")) {
            assertThat(uiAttributeService.appliesTo(fieldType)).as(fieldType).isTrue();
        }
        for (String containerType : List.of("PAGE", "SECTION", "SUBSECTION", "MAP", "MATRIX")) {
            assertThat(uiAttributeService.appliesTo(containerType)).as(containerType).isFalse();
        }
    }

    @Test
    void anEntryKeepsOnlyItsFilledInValuesTrimmedAndInTheKindsOrder() {
        Element field = elementService.create(section("UIA_VALUES").getId(), "FIELD_TEXT", "F", "Field", null);
        Map<String, String> values = new HashMap<>();
        values.put("ACTION_TYPE", " VALIDATE ");
        values.put("TARGET_VALUE", "email");
        values.put("UI_LABEL", "   ");
        values.put("UI_PARAMETER", null);

        uiAttributeService.add(field.getId(), values);

        List<UiAttributeEntryView> entries = uiAttributeService.list(field.getId());
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).valuesByCode()).containsExactly(
                Map.entry("TARGET_VALUE", "email"), Map.entry("ACTION_TYPE", "VALIDATE"));
        assertThat(entries.get(0).summary()).isEqualTo("TAR_VAL \"email\" ; ACT_TYP \"VALIDATE\"");
    }

    @Test
    void anEntryWithNoValueAnUnknownKindOrANonFieldElementIsRefused() {
        Element section = section("UIA_REFUSED");
        Element field = elementService.create(section.getId(), "FIELD_TEXT", "F", "Field", null);

        assertThatThrownBy(() -> uiAttributeService.add(field.getId(), Map.of("TARGET_VALUE", " ", "UI_LABEL", "")))
                .isInstanceOf(InvalidAttributeValueException.class)
                .hasMessageContaining("at least one value");
        assertThatThrownBy(() -> uiAttributeService.add(field.getId(), Map.of("NO_SUCH_KIND", "x")))
                .isInstanceOf(InvalidAttributeValueException.class)
                .hasMessageContaining("NO_SUCH_KIND");
        assertThatThrownBy(() -> uiAttributeService.add(section.getId(), Map.of("TARGET_VALUE", "x")))
                .isInstanceOf(InvalidAttributeValueException.class)
                .hasMessageContaining("SECTION");
        assertThat(uiAttributeService.list(field.getId())).isEmpty();

        // SAVE with an emptied box is refused too, and leaves the entry as it was.
        UiAttributeEntry entry = uiAttributeService.add(field.getId(), Map.of("TARGET_VALUE", "x"));
        assertThatThrownBy(() -> uiAttributeService.update(field.getId(), entry.getId(), Map.of("TARGET_VALUE", "")))
                .isInstanceOf(InvalidAttributeValueException.class);
        assertThat(summaries(field.getId())).containsExactly("TAR_VAL \"x\"");
    }

    @Test
    void updateReplacesTheValuesAndKeepsThePlaceInTheList() {
        Element field = elementService.create(section("UIA_UPDATE").getId(), "FIELD_TEXT", "F", "Field", null);
        uiAttributeService.add(field.getId(), Map.of("UI_LABEL", "first"));
        UiAttributeEntry second = uiAttributeService.add(field.getId(), Map.of("UI_LABEL", "second", "ACTION_TYPE", "SHOW"));
        uiAttributeService.add(field.getId(), Map.of("UI_LABEL", "third"));

        uiAttributeService.update(field.getId(), second.getId(), Map.of("TARGET_VALUE", "changed"));

        assertThat(summaries(field.getId())).containsExactly("UI_LAB \"first\"", "TAR_VAL \"changed\"", "UI_LAB \"third\"");
    }

    @Test
    void deleteRemovesTheEntryAndMovingSwapsNeighboursButNotPastEitherEnd() {
        Element field = elementService.create(section("UIA_ORDER").getId(), "FIELD_TEXT", "F", "Field", null);
        UiAttributeEntry a = uiAttributeService.add(field.getId(), Map.of("UI_LABEL", "a"));
        UiAttributeEntry b = uiAttributeService.add(field.getId(), Map.of("UI_LABEL", "b"));
        UiAttributeEntry c = uiAttributeService.add(field.getId(), Map.of("UI_LABEL", "c"));

        uiAttributeService.move(field.getId(), c.getId(), -1);
        assertThat(summaries(field.getId())).containsExactly("UI_LAB \"a\"", "UI_LAB \"c\"", "UI_LAB \"b\"");

        uiAttributeService.move(field.getId(), a.getId(), -1);
        uiAttributeService.move(field.getId(), b.getId(), 1);
        assertThat(summaries(field.getId())).containsExactly("UI_LAB \"a\"", "UI_LAB \"c\"", "UI_LAB \"b\"");

        uiAttributeService.delete(field.getId(), c.getId());
        assertThat(summaries(field.getId())).containsExactly("UI_LAB \"a\"", "UI_LAB \"b\"");

        // The order stays contiguous after a delete, so a move right after it still swaps neighbours.
        uiAttributeService.move(field.getId(), b.getId(), -1);
        assertThat(summaries(field.getId())).containsExactly("UI_LAB \"b\"", "UI_LAB \"a\"");
    }

    @Test
    void entriesBelongToTheirElementAndGoWithItWhenItIsDeleted() {
        Element section = section("UIA_OWNER");
        Element one = elementService.create(section.getId(), "FIELD_TEXT", "ONE", "One", null);
        Element two = elementService.create(section.getId(), "FIELD_TEXT", "TWO", "Two", null);
        UiAttributeEntry entry = uiAttributeService.add(one.getId(), Map.of("UI_LABEL", "mine"));

        // An entry can't be reached through another element.
        assertThatThrownBy(() -> uiAttributeService.update(two.getId(), entry.getId(), Map.of("UI_LABEL", "x")))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> uiAttributeService.delete(two.getId(), entry.getId()))
                .isInstanceOf(ResourceNotFoundException.class);

        elementService.softDelete(one.getId());
        assertThatThrownBy(() -> uiAttributeService.list(one.getId())).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void cloningATemplateMapCopiesItsFieldsEntriesInOrder() {
        Element template = mapTemplateService.createTemplatePage("UIA_TPL", "UI template");
        Element templateSection = elementService.create(template.getId(), "SECTION", "TPL_SEC", "Section", null);
        Element map = elementService.create(templateSection.getId(), "MAP", "ADDR", "Address", null);
        Element street = elementService.create(map.getId(), "FIELD_TEXT", "STREET", "Street", null);
        uiAttributeService.add(street.getId(), Map.of("UI_LABEL", "first"));
        uiAttributeService.add(street.getId(), Map.of("TARGET_VALUE", "second", "ACTION_TYPE", "SHOW"));
        Element target = section("UIA_CLONE_TARGET");

        MapCloneResult result = mapTemplateService.cloneMapInto(map.getId(), target.getId());

        Element copiedStreet = elementRepository.findByParentElementIdAndDeletedAtIsNullOrderByDisplayOrderAsc(result.clonedMap().getId()).get(0);
        assertThat(summaries(copiedStreet.getId())).containsExactly("UI_LAB \"first\"", "TAR_VAL \"second\" ; ACT_TYP \"SHOW\"");
        // A copy, not a link: changing the template afterwards leaves it alone.
        uiAttributeService.add(street.getId(), Map.of("UI_LABEL", "later"));
        assertThat(uiAttributeService.list(copiedStreet.getId())).hasSize(2);
    }
}
