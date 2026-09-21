package com.vprok.forms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vprok.forms.entity.Element;
import com.vprok.forms.repository.ElementRepository;
import com.vprok.forms.repository.ElementTypeRuleRepository;
import com.vprok.forms.web.error.InvalidElementHierarchyException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for ElementService algorithmic logic that FORMS-5/6/7's HTTP-layer tests only
 * exercise shallowly (a single-level move, a childless delete): cycle prevention on move,
 * multi-level page-id propagation, reorder's exact-match validation, and the soft-delete
 * cascade's BFS traversal. Repositories are mocked, so no database is needed.
 */
class ElementServiceTest {

    private ElementRepository elementRepository;
    private ElementTypeRuleRepository elementTypeRuleRepository;
    private ElementService service;

    @BeforeEach
    void setUp() {
        elementRepository = mock(ElementRepository.class);
        elementTypeRuleRepository = mock(ElementTypeRuleRepository.class);
        service = new ElementService(elementRepository, elementTypeRuleRepository);
        when(elementRepository.save(any(Element.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private static Element mockElement(Long id, String type) {
        Element element = mock(Element.class);
        when(element.getId()).thenReturn(id);
        when(element.getElementType()).thenReturn(type);
        return element;
    }

    // --- create() -------------------------------------------------------

    @Test
    void create_withoutParent_nonPageType_isRejected() {
        assertThatThrownBy(() -> service.create(null, "SECTION", "S1", "Section", null))
                .isInstanceOf(InvalidElementHierarchyException.class)
                .hasMessageContaining("PAGE");
    }

    @Test
    void create_withIncompatibleParentChildTypes_isRejected() {
        Element page = mockElement(1L, "PAGE");
        when(elementRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(java.util.Optional.of(page));
        when(elementTypeRuleRepository.existsByIdParentTypeAndIdChildType("PAGE", "FIELD_TEXT")).thenReturn(false);

        assertThatThrownBy(() -> service.create(1L, "FIELD_TEXT", "F1", "Field", null))
                .isInstanceOf(InvalidElementHierarchyException.class)
                .hasMessageContaining("PAGE")
                .hasMessageContaining("FIELD_TEXT");
    }

    @Test
    void create_withoutExplicitDisplayOrder_appendsAfterLastSibling() {
        Element page = mockElement(1L, "PAGE");
        when(elementRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(java.util.Optional.of(page));
        when(elementTypeRuleRepository.existsByIdParentTypeAndIdChildType("PAGE", "SECTION")).thenReturn(true);

        Element existingSibling = new Element(null, null, "SECTION", "S1", "Existing");
        existingSibling.setDisplayOrder(3);
        when(elementRepository.findByParentElementIdAndDeletedAtIsNullOrderByDisplayOrderAsc(1L))
                .thenReturn(List.of(existingSibling));

        Element created = service.create(1L, "SECTION", "S2", "New", null);

        assertThat(created.getDisplayOrder()).isEqualTo(4);
    }

    // --- move() -----------------------------------------------------------

    @Test
    void move_ofPageElement_isRejected() {
        Element page = mockElement(5L, "PAGE");
        when(elementRepository.findByIdAndDeletedAtIsNull(5L)).thenReturn(java.util.Optional.of(page));

        assertThatThrownBy(() -> service.move(5L, 10L))
                .isInstanceOf(InvalidElementHierarchyException.class)
                .hasMessageContaining("PAGE elements have no parent");
    }

    @Test
    void move_intoOwnDescendant_isRejectedAsACycle() {
        // elementItself (2) <- newParent (3), i.e. newParent is actually a child of elementItself:
        // moving elementItself under newParent would create a cycle.
        Element elementItself = mockElement(2L, "SECTION");
        Element newParent = mockElement(3L, "SUBSECTION");
        when(newParent.getParentElement()).thenReturn(elementItself);
        when(elementItself.getParentElement()).thenReturn(null);

        when(elementRepository.findByIdAndDeletedAtIsNull(2L)).thenReturn(java.util.Optional.of(elementItself));
        when(elementRepository.findByIdAndDeletedAtIsNull(3L)).thenReturn(java.util.Optional.of(newParent));
        when(elementTypeRuleRepository.existsByIdParentTypeAndIdChildType("SUBSECTION", "SECTION")).thenReturn(true);

        assertThatThrownBy(() -> service.move(2L, 3L))
                .isInstanceOf(InvalidElementHierarchyException.class)
                .hasMessageContaining("own subtree");
    }

    @Test
    void move_propagatesNewPageToEveryLevelOfTheSubtree() {
        // element(10) -> child(11) -> grandchild(12); moving element(10) under a PAGE newParent(20)
        // must update page on all three, not just the element being moved directly.
        Element element = mockElement(10L, "SECTION");
        Element child = mockElement(11L, "SUBSECTION");
        Element grandchild = mockElement(12L, "FIELD_TEXT");
        Element newParent = mockElement(20L, "PAGE");
        when(newParent.getParentElement()).thenReturn(null);

        when(elementRepository.findByIdAndDeletedAtIsNull(10L)).thenReturn(java.util.Optional.of(element));
        when(elementRepository.findByIdAndDeletedAtIsNull(20L)).thenReturn(java.util.Optional.of(newParent));
        when(elementTypeRuleRepository.existsByIdParentTypeAndIdChildType("PAGE", "SECTION")).thenReturn(true);

        when(elementRepository.findByParentElementIdAndDeletedAtIsNullOrderByDisplayOrderAsc(10L))
                .thenReturn(List.of(child));
        when(elementRepository.findByParentElementIdAndDeletedAtIsNullOrderByDisplayOrderAsc(11L))
                .thenReturn(List.of(grandchild));
        when(elementRepository.findByParentElementIdAndDeletedAtIsNullOrderByDisplayOrderAsc(12L))
                .thenReturn(List.of());

        service.move(10L, 20L);

        verify(element).setParentElement(newParent);
        verify(element).setPage(newParent);
        verify(child).setPage(newParent);
        verify(grandchild).setPage(newParent);
        verify(elementRepository).saveAll(List.of(element, child, grandchild));
    }

    // --- reorderChildren() --------------------------------------------------

    @Test
    void reorderChildren_rejectsFewerIdsThanCurrentChildren() {
        Element parent = mockElement(1L, "PAGE");
        when(elementRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(java.util.Optional.of(parent));
        Element c1 = mockElement(100L, "SECTION");
        Element c2 = mockElement(200L, "SECTION");
        when(elementRepository.findByParentElementIdAndDeletedAtIsNullOrderByDisplayOrderAsc(1L))
                .thenReturn(List.of(c1, c2));

        assertThatThrownBy(() -> service.reorderChildren(1L, List.of(100L)))
                .isInstanceOf(InvalidElementHierarchyException.class);
    }

    @Test
    void reorderChildren_rejectsAnIdThatIsNotAChildOfTheParent() {
        Element parent = mockElement(1L, "PAGE");
        when(elementRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(java.util.Optional.of(parent));
        Element c1 = mockElement(100L, "SECTION");
        Element c2 = mockElement(200L, "SECTION");
        when(elementRepository.findByParentElementIdAndDeletedAtIsNullOrderByDisplayOrderAsc(1L))
                .thenReturn(List.of(c1, c2));

        assertThatThrownBy(() -> service.reorderChildren(1L, List.of(100L, 999L)))
                .isInstanceOf(InvalidElementHierarchyException.class);
    }

    @Test
    void reorderChildren_rejectsADuplicateIdEvenIfCountMatches() {
        Element parent = mockElement(1L, "PAGE");
        when(elementRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(java.util.Optional.of(parent));
        Element c1 = mockElement(100L, "SECTION");
        Element c2 = mockElement(200L, "SECTION");
        when(elementRepository.findByParentElementIdAndDeletedAtIsNullOrderByDisplayOrderAsc(1L))
                .thenReturn(List.of(c1, c2));

        // Same size as the real children (2), but "100" repeated instead of including "200".
        assertThatThrownBy(() -> service.reorderChildren(1L, List.of(100L, 100L)))
                .isInstanceOf(InvalidElementHierarchyException.class);
    }

    // --- softDelete() -----------------------------------------------------

    @Test
    void softDelete_ofALeafWithNoChildren_marksOnlyItself() {
        Element leaf = mockElement(5L, "FIELD_TEXT");
        when(elementRepository.findByIdAndDeletedAtIsNull(5L)).thenReturn(java.util.Optional.of(leaf));
        when(elementRepository.findByParentElementIdAndDeletedAtIsNullOrderByDisplayOrderAsc(5L))
                .thenReturn(List.of());

        service.softDelete(5L);

        verify(leaf, times(1)).setDeletedAt(any(Instant.class));
        verify(elementRepository).saveAll(List.of(leaf));
    }

    @Test
    void softDelete_cascadesThroughEveryLevelOfAFourLevelSubtree() {
        // page(1) -> section(2) -> subsection(3) -> field(4); soft-deleting the section must mark
        // the subsection and the field too, not just the section itself.
        Element section = mockElement(2L, "SECTION");
        Element subsection = mockElement(3L, "SUBSECTION");
        Element field = mockElement(4L, "FIELD_TEXT");

        when(elementRepository.findByIdAndDeletedAtIsNull(2L)).thenReturn(java.util.Optional.of(section));
        when(elementRepository.findByParentElementIdAndDeletedAtIsNullOrderByDisplayOrderAsc(2L))
                .thenReturn(List.of(subsection));
        when(elementRepository.findByParentElementIdAndDeletedAtIsNullOrderByDisplayOrderAsc(3L))
                .thenReturn(List.of(field));
        when(elementRepository.findByParentElementIdAndDeletedAtIsNullOrderByDisplayOrderAsc(4L))
                .thenReturn(List.of());

        service.softDelete(2L);

        verify(section).setDeletedAt(any(Instant.class));
        verify(subsection).setDeletedAt(any(Instant.class));
        verify(field).setDeletedAt(any(Instant.class));
        verify(elementRepository).saveAll(List.of(section, subsection, field));
    }
}
