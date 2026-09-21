package com.vprok.forms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vprok.forms.entity.Element;
import com.vprok.forms.repository.ElementRepository;
import com.vprok.forms.repository.ElementTypeRuleRepository;
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

    // --- element_type_rule: exhaustive matrix -------------------------------------------------

    private static final List<String> ALL_ELEMENT_TYPES = List.of(
            "PAGE", "SECTION", "SUBSECTION",
            "FIELD_TEXT", "FIELD_TEXTAREA", "FIELD_NUMBER", "FIELD_DATE", "FIELD_BOOLEAN", "FIELD_LIST");

    /**
     * Exactly the 14 pairs seeded in V2__seed_data.sql. If a future migration adds or removes a
     * hierarchy rule, this set must be updated in the same change - deliberately coupled, the same
     * way FormExportIT is deliberately coupled to docs/json-export-schema.md, so the two can't
     * silently drift apart.
     */
    private static final Set<String> VALID_PAIRS = Set.of(
            "PAGE->SECTION",
            "SECTION->SUBSECTION",
            "SECTION->FIELD_TEXT", "SECTION->FIELD_TEXTAREA", "SECTION->FIELD_NUMBER",
            "SECTION->FIELD_DATE", "SECTION->FIELD_BOOLEAN", "SECTION->FIELD_LIST",
            "SUBSECTION->FIELD_TEXT", "SUBSECTION->FIELD_TEXTAREA", "SUBSECTION->FIELD_NUMBER",
            "SUBSECTION->FIELD_DATE", "SUBSECTION->FIELD_BOOLEAN", "SUBSECTION->FIELD_LIST");

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
