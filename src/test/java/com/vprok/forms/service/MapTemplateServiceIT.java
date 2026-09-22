package com.vprok.forms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vprok.forms.entity.Element;
import com.vprok.forms.entity.ElementAttributeValue;
import com.vprok.forms.entity.ElementListOption;
import com.vprok.forms.repository.ElementRepository;
import com.vprok.forms.web.dto.export.ExportNode;
import com.vprok.forms.web.error.InvalidElementHierarchyException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Template pages and cloning MAPs out of them (FORMS-14), against real PostgreSQL. Every test
 * builds its own template page + real page with unique page codes, since the class shares one
 * database and page codes are globally unique.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class MapTemplateServiceIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private MapTemplateService mapTemplateService;

    @Autowired
    private ElementService elementService;

    @Autowired
    private ElementAttributeValueService elementAttributeValueService;

    @Autowired
    private ElementListOptionService elementListOptionService;

    @Autowired
    private FormExportService formExportService;

    @Autowired
    private ElementRepository elementRepository;

    /** A template page holding one mixed MAP, plus a separate real page with an empty target section. */
    private record Fixture(
            Element templatePage,
            Element sourceMap,
            Element street,
            Element houseNo,
            Element country,
            Element notes,
            Element realPage,
            Element targetSection) {
    }

    private Fixture buildFixture(String suffix) {
        Element templatePage = mapTemplateService.createTemplatePage("TPL_" + suffix, "Template " + suffix);
        Element templateSection = elementService.create(templatePage.getId(), "SECTION", "TPL_SEC", "Template section", null);
        Element map = elementService.create(templateSection.getId(), "MAP", "ADDRESS", "Address", null);

        Element street = elementService.create(map.getId(), "FIELD_TEXT", "STREET", "Street", null);
        Element houseNo = elementService.create(map.getId(), "FIELD_NUMBER", "HOUSE_NO", "House number", null);
        Element country = elementService.create(map.getId(), "FIELD_LIST", "COUNTRY", "Country", null);
        Element notes = elementService.create(map.getId(), "FIELD_TEXTAREA", "NOTES", "Notes", null);

        elementAttributeValueService.setValue(street.getId(), "MANDATORY", "true");
        elementAttributeValueService.setValue(street.getId(), "MAX_LENGTH", "80");
        elementAttributeValueService.setValue(houseNo.getId(), "MIN_VALUE", "1");

        elementListOptionService.create(country.getId(), "BE", "Belgium", null, true);
        elementListOptionService.create(country.getId(), "NL", "Netherlands", null, false);
        ElementListOption retired = elementListOptionService.create(country.getId(), "LU", "Luxembourg", null, false);
        elementListOptionService.deactivate(country.getId(), retired.getId());

        Element realPage = elementService.create(null, "PAGE", "REAL_" + suffix, "Real " + suffix, null);
        Element targetSection = elementService.create(realPage.getId(), "SECTION", "REAL_SEC", "Real section", null);

        return new Fixture(templatePage, map, street, houseNo, country, notes, realPage, targetSection);
    }

    private Map<String, String> attributeValuesByCode(Long elementId) {
        return elementAttributeValueService.list(elementId).stream()
                .collect(Collectors.toMap(v -> v.getAttributeDefinition().getCode(), ElementAttributeValue::getValue));
    }

    // --- deep copy ----------------------------------------------------------------------------

    @Test
    void cloningAMixedMapProducesAFullDeepCopyWithFreshIds() {
        Fixture f = buildFixture("COPY");

        MapCloneResult result = mapTemplateService.cloneMapInto(f.sourceMap().getId(), f.targetSection().getId());
        Element clone = result.clonedMap();

        assertThat(result.renamedCodes()).isEmpty();
        assertThat(clone.getId()).isNotEqualTo(f.sourceMap().getId());
        assertThat(clone.getElementType()).isEqualTo("MAP");
        assertThat(clone.getCode()).isEqualTo("ADDRESS");
        assertThat(clone.getLabel()).isEqualTo("Address");
        assertThat(clone.getParentElement().getId()).isEqualTo(f.targetSection().getId());
        assertThat(clone.getPage().getId()).isEqualTo(f.realPage().getId());

        List<Element> copiedFields = elementService.getChildren(clone.getId());
        assertThat(copiedFields).extracting(Element::getCode)
                .containsExactly("STREET", "HOUSE_NO", "COUNTRY", "NOTES");
        assertThat(copiedFields).extracting(Element::getElementType)
                .containsExactly("FIELD_TEXT", "FIELD_NUMBER", "FIELD_LIST", "FIELD_TEXTAREA");
        assertThat(copiedFields).extracting(Element::getLabel)
                .containsExactly("Street", "House number", "Country", "Notes");
        assertThat(copiedFields).extracting(Element::getId)
                .doesNotContainAnyElementsOf(List.of(
                        f.street().getId(), f.houseNo().getId(), f.country().getId(), f.notes().getId()));
        // Every copied row belongs to the real page - not left pointing at the template page.
        assertThat(copiedFields).allSatisfy(e -> assertThat(e.getPage().getId()).isEqualTo(f.realPage().getId()));

        Element streetCopy = copiedFields.get(0);
        Element houseNoCopy = copiedFields.get(1);
        Element countryCopy = copiedFields.get(2);
        assertThat(attributeValuesByCode(streetCopy.getId())).isEqualTo(Map.of("MANDATORY", "true", "MAX_LENGTH", "80"));
        assertThat(attributeValuesByCode(houseNoCopy.getId())).isEqualTo(Map.of("MIN_VALUE", "1"));

        // All options come across - including the deactivated one, still deactivated - as new rows.
        List<ElementListOption> copiedOptions = elementListOptionService.list(countryCopy.getId(), true);
        assertThat(copiedOptions).extracting(ElementListOption::getCode).containsExactly("BE", "NL", "LU");
        assertThat(copiedOptions).extracting(ElementListOption::isDefault).containsExactly(true, false, false);
        assertThat(copiedOptions).extracting(ElementListOption::isActive).containsExactly(true, true, false);
        List<Long> sourceOptionIds = elementListOptionService.list(f.country().getId(), true).stream()
                .map(ElementListOption::getId).toList();
        assertThat(copiedOptions).extracting(ElementListOption::getId).doesNotContainAnyElementsOf(sourceOptionIds);
    }

    // --- independence: a one-time copy, never a live link --------------------------------------

    @Test
    void editingOrDeletingTheSourceTemplateAfterwardsNeverChangesTheClone() {
        Fixture f = buildFixture("INDEP");
        mapTemplateService.cloneMapInto(f.sourceMap().getId(), f.targetSection().getId());

        // The real page's whole export is the snapshot: any leak from template to copy, in any
        // field, attribute or option, would show up as a difference here.
        ExportNode before = formExportService.exportByCode("REAL_INDEP");
        ExportNode clonedMapNode = before.children().get(0).children().get(0);
        assertThat(clonedMapNode.type()).isEqualTo("MAP");
        assertThat(clonedMapNode.children()).hasSize(4);

        elementService.updateLabel(f.sourceMap().getId(), "Renamed in template");
        elementService.updateLabel(f.street().getId(), "Renamed street");
        elementAttributeValueService.setValue(f.street().getId(), "MAX_LENGTH", "10");
        elementAttributeValueService.deleteValue(f.street().getId(), "MANDATORY");
        elementService.create(f.sourceMap().getId(), "FIELD_DATE", "ADDED_LATER", "Added later", null);
        elementListOptionService.create(f.country().getId(), "DE", "Germany", null, false);
        Long belgiumId = elementListOptionService.list(f.country().getId(), false).get(0).getId();
        elementListOptionService.deactivate(f.country().getId(), belgiumId);
        elementService.softDelete(f.notes().getId());
        // ...and finally delete the entire template page, cascading through the source MAP.
        elementService.softDelete(f.templatePage().getId());

        ExportNode after = formExportService.exportByCode("REAL_INDEP");
        assertThat(after).isEqualTo(before);
    }

    @Test
    void editingTheCloneNeverChangesTheSourceTemplate() {
        Fixture f = buildFixture("REVERSE");
        Element clone = mapTemplateService.cloneMapInto(f.sourceMap().getId(), f.targetSection().getId()).clonedMap();
        Element streetCopy = elementService.getChildren(clone.getId()).get(0);

        elementService.updateLabel(streetCopy.getId(), "Changed on the real page");
        elementAttributeValueService.setValue(streetCopy.getId(), "MAX_LENGTH", "5");

        assertThat(elementService.getActiveOrThrow(f.street().getId()).getLabel()).isEqualTo("Street");
        assertThat(attributeValuesByCode(f.street().getId())).containsEntry("MAX_LENGTH", "80");
    }

    // --- code collisions ------------------------------------------------------------------------

    @Test
    void copiedCodesAlreadyUsedAnywhereOnTheTargetPageAreAutoSuffixed() {
        Fixture f = buildFixture("COLLIDE");
        // Codes are page-scoped, so the existing clashes sit in a *different* section from the
        // clone target: a sibling-only check would miss them.
        Element otherSection = elementService.create(f.realPage().getId(), "SECTION", "OTHER_SEC", "Other", null);
        elementService.create(otherSection.getId(), "FIELD_TEXT", "ADDRESS", "Existing address field", null);
        elementService.create(otherSection.getId(), "FIELD_TEXT", "STREET", "Existing street field", null);

        MapCloneResult first = mapTemplateService.cloneMapInto(f.sourceMap().getId(), f.targetSection().getId());

        assertThat(first.clonedMap().getCode()).isEqualTo("ADDRESS_2");
        assertThat(elementService.getChildren(first.clonedMap().getId())).extracting(Element::getCode)
                .containsExactly("STREET_2", "HOUSE_NO", "COUNTRY", "NOTES");
        assertThat(first.renamedCodes()).containsExactly("ADDRESS → ADDRESS_2", "STREET → STREET_2");

        // Using the same template again on the same page: every copied code now collides.
        MapCloneResult second = mapTemplateService.cloneMapInto(f.sourceMap().getId(), f.targetSection().getId());

        assertThat(second.clonedMap().getCode()).isEqualTo("ADDRESS_3");
        assertThat(elementService.getChildren(second.clonedMap().getId())).extracting(Element::getCode)
                .containsExactly("STREET_3", "HOUSE_NO_2", "COUNTRY_2", "NOTES_2");
        // Two copies side by side in the target section, originals untouched.
        assertThat(elementService.getChildren(f.targetSection().getId())).extracting(Element::getCode)
                .containsExactly("ADDRESS_2", "ADDRESS_3");
    }

    // --- what may be cloned, and where ----------------------------------------------------------

    @Test
    void cloneRejectsNonTemplateSourcesNonMapSourcesAndIllegalTargets() {
        Fixture f = buildFixture("REJECT");

        Element mapOnRealPage = elementService.create(f.targetSection().getId(), "MAP", "REAL_MAP", "Real map", null);
        assertThatThrownBy(() -> mapTemplateService.cloneMapInto(mapOnRealPage.getId(), f.targetSection().getId()))
                .isInstanceOf(InvalidElementHierarchyException.class)
                .hasMessageContaining("not on a template page");

        assertThatThrownBy(() -> mapTemplateService.cloneMapInto(f.street().getId(), f.targetSection().getId()))
                .isInstanceOf(InvalidElementHierarchyException.class)
                .hasMessageContaining("not a MAP");

        // A MAP may not sit directly on a PAGE (FORMS-13), so neither may a copy of one.
        assertThatThrownBy(() -> mapTemplateService.cloneMapInto(f.sourceMap().getId(), f.realPage().getId()))
                .isInstanceOf(InvalidElementHierarchyException.class)
                .hasMessageContaining("PAGE may not contain MAP");
    }

    // --- template flag ------------------------------------------------------------------------

    @Test
    void templatePagesAreExcludedFromThePageListingAndMoveBackWhenUnmarked() {
        Element template = mapTemplateService.createTemplatePage("TPL_LISTING", "Listing template");
        elementService.create(null, "PAGE", "REAL_LISTING", "Listing real page", null);

        assertThat(elementService.getPages()).extracting(Element::getCode)
                .contains("REAL_LISTING")
                .doesNotContain("TPL_LISTING");
        assertThat(mapTemplateService.getTemplatePages()).extracting(Element::getCode)
                .contains("TPL_LISTING")
                .doesNotContain("REAL_LISTING");

        mapTemplateService.setTemplate(template.getId(), false);

        assertThat(elementService.getPages()).extracting(Element::getCode).contains("TPL_LISTING");
        assertThat(mapTemplateService.getTemplatePages()).extracting(Element::getCode).doesNotContain("TPL_LISTING");
    }

    @Test
    void onlyPageRowsCanBeTemplatesInTheServiceAndInTheSchema() {
        Element page = elementService.create(null, "PAGE", "TPL_GUARD", "Guard", null);
        Element section = elementService.create(page.getId(), "SECTION", "GUARD_SEC", "Section", null);

        assertThatThrownBy(() -> mapTemplateService.setTemplate(section.getId(), true))
                .isInstanceOf(InvalidElementHierarchyException.class);

        // chk_template_only_on_page holds even if the service check is bypassed.
        section.setTemplate(true);
        assertThatThrownBy(() -> elementRepository.saveAndFlush(section))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
