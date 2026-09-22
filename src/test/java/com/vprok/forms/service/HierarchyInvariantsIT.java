package com.vprok.forms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vprok.forms.entity.Element;
import com.vprok.forms.repository.ElementRepository;
import com.vprok.forms.repository.ElementTypeRuleRepository;
import com.vprok.forms.web.error.InvalidElementHierarchyException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Cross-cutting invariants that FORMS-4 through FORMS-7's per-ticket tests never exercised
 * end-to-end, because each of those tests only ever touched a single page or a single level:
 *
 * <ul>
 *   <li>page-scoped code uniqueness holding when an element actually moves across pages, not just
 *       within one - and confirming the partial unique index itself is what rejects the collision
 *       (ElementService has no application-level pre-check for this; the schema comment in
 *       V1__init_schema.sql says as much, so this is the only thing standing guard);</li>
 *   <li>soft-delete cascading through a full four-level subtree (page/section/subsection/field),
 *       not just a childless one-level delete;</li>
 *   <li>element_type_rule rejecting every combination genuinely absent from the V2 seed data, not
 *       only the one or two invalid combinations existing tests happen to try.</li>
 * </ul>
 *
 * Runs against real PostgreSQL via Testcontainers (Flyway applies V1+V2 first); skipped
 * automatically without Docker.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class HierarchyInvariantsIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private ElementService elementService;

    @Autowired
    private ElementRepository elementRepository;

    @Autowired
    private ElementTypeRuleRepository elementTypeRuleRepository;

    // --- page-scoped code uniqueness across pages --------------------------------------------

    @Test
    void sameCodeIsAllowedOnTwoDifferentPages() {
        Element pageA = elementService.create(null, "PAGE", "INV_PAGE_A", "Page A", null);
        Element pageB = elementService.create(null, "PAGE", "INV_PAGE_B", "Page B", null);

        // Page-scoped uniqueness means this must succeed on both pages: the index is
        // (page_id, code), not code alone.
        assertThatCode(() -> {
            elementService.create(pageA.getId(), "SECTION", "SHARED_CODE", "Section on A", null);
            elementService.create(pageB.getId(), "SECTION", "SHARED_CODE", "Section on B", null);
        }).doesNotThrowAnyException();
    }

    @Test
    void movingAnElementIntoAPageWithAConflictingCodeIsRejectedByTheDatabaseConstraint() {
        Element pageA = elementService.create(null, "PAGE", "INV_PAGE_C", "Page C", null);
        Element pageB = elementService.create(null, "PAGE", "INV_PAGE_D", "Page D", null);

        // Both pages independently have a "DUPE_MOVE"-coded section - allowed, since uniqueness is
        // per-page (see sameCodeIsAllowedOnTwoDifferentPages above).
        Element sectionOnA = elementService.create(pageA.getId(), "SECTION", "DUPE_MOVE", "On A", null);
        elementService.create(pageB.getId(), "SECTION", "DUPE_MOVE", "On B", null);

        // Moving A's "DUPE_MOVE" section to become a direct child of page B collides with B's own
        // "DUPE_MOVE" section once both share page_id=B. ElementService.move() performs no
        // application-level uniqueness pre-check (unlike, say, hierarchy compatibility, which is
        // checked explicitly) - so this exercises the partial unique index itself, not a service
        // guard rail catching it first.
        assertThatThrownBy(() -> elementService.move(sectionOnA.getId(), pageB.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void duplicatePageCodeIsRejectedByTheDatabaseConstraint() {
        elementService.create(null, "PAGE", "INV_DUP_ROOT", "First", null);

        // uq_element_root_code is a *separate* partial index from uq_element_page_code (PAGE rows
        // have page_id NULL, so the page-scoped index doesn't apply to them at all).
        assertThatThrownBy(() -> elementService.create(null, "PAGE", "INV_DUP_ROOT", "Second", null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // --- multi-level soft-delete cascade ------------------------------------------------------

    @Test
    void softDeleteCascadesThroughAFourLevelSubtree() {
        Element page = elementService.create(null, "PAGE", "INV_CASCADE_PAGE", "Cascade Page", null);
        Element section = elementService.create(page.getId(), "SECTION", "CASC_SEC", "Section", null);
        Element subsection = elementService.create(section.getId(), "SUBSECTION", "CASC_SUB", "Subsection", null);
        Element field = elementService.create(subsection.getId(), "FIELD_TEXT", "CASC_FLD", "Field", null);

        elementService.softDelete(section.getId());

        // findById (plain, deleted-inclusive) rather than findByIdAndDeletedAtIsNull, since the
        // whole point is checking deletedAt on rows a normal read would now hide.
        assertThat(elementRepository.findById(section.getId()).orElseThrow().getDeletedAt()).isNotNull();
        assertThat(elementRepository.findById(subsection.getId()).orElseThrow().getDeletedAt()).isNotNull();
        assertThat(elementRepository.findById(field.getId()).orElseThrow().getDeletedAt()).isNotNull();
        // The page itself is the ancestor, not part of the deleted subtree - must stay active.
        assertThat(elementRepository.findById(page.getId()).orElseThrow().getDeletedAt()).isNull();
    }

    @Test
    void softDeletingALeafWithNoChildrenMarksOnlyItself() {
        Element page = elementService.create(null, "PAGE", "INV_LEAF_PAGE", "Leaf Page", null);
        Element section = elementService.create(page.getId(), "SECTION", "LEAF_SEC", "Section", null);
        Element leafField = elementService.create(section.getId(), "FIELD_TEXT", "LEAF_FLD", "Field", null);

        elementService.softDelete(leafField.getId());

        assertThat(elementRepository.findById(leafField.getId()).orElseThrow().getDeletedAt()).isNotNull();
        assertThat(elementRepository.findById(section.getId()).orElseThrow().getDeletedAt()).isNull();
        assertThat(elementRepository.findById(page.getId()).orElseThrow().getDeletedAt()).isNull();
    }

    // --- MAP composite field type (FORMS-13) --------------------------------------------------

    @Test
    void aMapAcceptsAnyMixOfOrdinaryFieldChildrenButNoContainers() {
        Element page = elementService.create(null, "PAGE", "MAP_MIX_PAGE", "Map Mix Page", null);
        Element section = elementService.create(page.getId(), "SECTION", "MIX_SEC", "Section", null);
        Element map = elementService.create(section.getId(), "MAP", "MIX_MAP", "Address Map", null);

        // The "5 text boxes, 1 list, 1 text area" case: any mix, any number, any field subtype.
        elementService.create(map.getId(), "FIELD_TEXT", "MIX_T1", "Text 1", null);
        elementService.create(map.getId(), "FIELD_TEXT", "MIX_T2", "Text 2", null);
        elementService.create(map.getId(), "FIELD_TEXTAREA", "MIX_TA", "Text Area", null);
        elementService.create(map.getId(), "FIELD_NUMBER", "MIX_NUM", "Number", null);
        elementService.create(map.getId(), "FIELD_DATE", "MIX_DATE", "Date", null);
        elementService.create(map.getId(), "FIELD_BOOLEAN", "MIX_BOOL", "Boolean", null);
        elementService.create(map.getId(), "FIELD_LIST", "MIX_LIST", "List", null);

        assertThat(elementService.getChildren(map.getId()))
                .extracting(Element::getCode)
                .containsExactly("MIX_T1", "MIX_T2", "MIX_TA", "MIX_NUM", "MIX_DATE", "MIX_BOOL", "MIX_LIST");

        // A map holds fields only - no nested maps, no sections/subsections inside it.
        assertThatThrownBy(() -> elementService.create(map.getId(), "MAP", "NESTED_MAP", "Nested", null))
                .isInstanceOf(InvalidElementHierarchyException.class);
        assertThatThrownBy(() -> elementService.create(map.getId(), "SUBSECTION", "BAD_SUB", "Bad", null))
                .isInstanceOf(InvalidElementHierarchyException.class);
    }

    @Test
    void aMapIsValidWhereverAnOrdinaryFieldIsValid() {
        Element page = elementService.create(null, "PAGE", "MAP_PLACEMENT_PAGE", "Placement", null);
        Element section = elementService.create(page.getId(), "SECTION", "PLACE_SEC", "Section", null);
        Element subsection = elementService.create(section.getId(), "SUBSECTION", "PLACE_SUB", "Subsection", null);

        // Under a section and under a subsection - the two places a FIELD_* may sit today.
        assertThatCode(() -> {
            elementService.create(section.getId(), "MAP", "MAP_IN_SEC", "Map in section", null);
            elementService.create(subsection.getId(), "MAP", "MAP_IN_SUB", "Map in subsection", null);
        }).doesNotThrowAnyException();

        // ...and nowhere a field may not: directly under a PAGE.
        assertThatThrownBy(() -> elementService.create(page.getId(), "MAP", "MAP_ON_PAGE", "Map on page", null))
                .isInstanceOf(InvalidElementHierarchyException.class);
    }

    @Test
    void softDeletingAMapCascadesThroughItsFieldChildren() {
        Element page = elementService.create(null, "PAGE", "MAP_CASCADE_PAGE", "Cascade", null);
        Element section = elementService.create(page.getId(), "SECTION", "MC_SEC", "Section", null);
        Element map = elementService.create(section.getId(), "MAP", "MC_MAP", "Map", null);
        Element fieldA = elementService.create(map.getId(), "FIELD_TEXT", "MC_F1", "Field 1", null);
        Element fieldB = elementService.create(map.getId(), "FIELD_LIST", "MC_F2", "Field 2", null);
        Element siblingField = elementService.create(section.getId(), "FIELD_TEXT", "MC_SIBLING", "Sibling", null);

        elementService.softDelete(map.getId());

        assertThat(elementRepository.findById(map.getId()).orElseThrow().getDeletedAt()).isNotNull();
        assertThat(elementRepository.findById(fieldA.getId()).orElseThrow().getDeletedAt()).isNotNull();
        assertThat(elementRepository.findById(fieldB.getId()).orElseThrow().getDeletedAt()).isNotNull();
        // Everything outside the map's own subtree is untouched.
        assertThat(elementRepository.findById(siblingField.getId()).orElseThrow().getDeletedAt()).isNull();
        assertThat(elementRepository.findById(section.getId()).orElseThrow().getDeletedAt()).isNull();
        assertThat(elementService.getChildren(section.getId()))
                .extracting(Element::getCode)
                .containsExactly("MC_SIBLING");
    }

    // --- element_type_rule: exhaustive matrix -------------------------------------------------

    private static final List<String> ALL_ELEMENT_TYPES = List.of(
            "PAGE", "SECTION", "SUBSECTION", "MAP",
            "FIELD_TEXT", "FIELD_TEXTAREA", "FIELD_NUMBER", "FIELD_DATE", "FIELD_BOOLEAN", "FIELD_LIST");

    /**
     * Exactly the pairs seeded across V2__seed_data.sql (14) and V3__add_map_element_type.sql (8).
     * If a future migration adds or removes a hierarchy rule, this set must be updated in the same
     * change - deliberately coupled, the same way FormExportIT is deliberately coupled to
     * docs/json-export-schema.md, so the two can't silently drift apart. (FORMS-13 is the first
     * time that coupling actually fired: adding MAP broke this test until the rows were added
     * here, which is the intended behaviour.)
     */
    private static final Set<String> VALID_PAIRS = Set.of(
            "PAGE->SECTION",
            "SECTION->SUBSECTION",
            "SECTION->FIELD_TEXT", "SECTION->FIELD_TEXTAREA", "SECTION->FIELD_NUMBER",
            "SECTION->FIELD_DATE", "SECTION->FIELD_BOOLEAN", "SECTION->FIELD_LIST",
            "SUBSECTION->FIELD_TEXT", "SUBSECTION->FIELD_TEXTAREA", "SUBSECTION->FIELD_NUMBER",
            "SUBSECTION->FIELD_DATE", "SUBSECTION->FIELD_BOOLEAN", "SUBSECTION->FIELD_LIST",
            // V3: a MAP sits where a field sits, and holds any mix of ordinary fields.
            // Deliberately no PAGE->MAP and no MAP->MAP.
            "SECTION->MAP", "SUBSECTION->MAP",
            "MAP->FIELD_TEXT", "MAP->FIELD_TEXTAREA", "MAP->FIELD_NUMBER",
            "MAP->FIELD_DATE", "MAP->FIELD_BOOLEAN", "MAP->FIELD_LIST");

    @Test
    void elementTypeRuleAcceptsExactlyTheSeededPairsAndRejectsEveryOtherCombination() {
        Set<String> wronglyAccepted = new LinkedHashSet<>();
        Set<String> wronglyRejected = new LinkedHashSet<>();

        for (String parentType : ALL_ELEMENT_TYPES) {
            for (String childType : ALL_ELEMENT_TYPES) {
                String pair = parentType + "->" + childType;
                boolean actuallyAllowed = elementTypeRuleRepository.existsByIdParentTypeAndIdChildType(parentType, childType);
                boolean expectedAllowed = VALID_PAIRS.contains(pair);
                if (actuallyAllowed && !expectedAllowed) {
                    wronglyAccepted.add(pair);
                } else if (!actuallyAllowed && expectedAllowed) {
                    wronglyRejected.add(pair);
                }
            }
        }

        assertThat(wronglyAccepted).as("parent/child pairs allowed but not in the seeded rule set").isEmpty();
        assertThat(wronglyRejected).as("seeded parent/child pairs the rule table failed to accept").isEmpty();
    }
}
