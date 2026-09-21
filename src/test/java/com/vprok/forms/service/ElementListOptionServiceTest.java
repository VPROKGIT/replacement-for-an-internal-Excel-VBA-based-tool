package com.vprok.forms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vprok.forms.entity.Element;
import com.vprok.forms.entity.ElementListOption;
import com.vprok.forms.repository.ElementListOptionRepository;
import com.vprok.forms.repository.ElementRepository;
import com.vprok.forms.web.error.InvalidElementHierarchyException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for ElementListOptionService logic not exercised through the HTTP layer.
 * StructureApiIT covers create/list/deactivate but never calls the PUT update endpoint, so the
 * partial-update semantics (a null field means "leave unchanged") are pinned down here instead.
 */
class ElementListOptionServiceTest {

    private ElementRepository elementRepository;
    private ElementListOptionRepository elementListOptionRepository;
    private ElementListOptionService service;

    @BeforeEach
    void setUp() {
        elementRepository = mock(ElementRepository.class);
        elementListOptionRepository = mock(ElementListOptionRepository.class);
        service = new ElementListOptionService(elementRepository, elementListOptionRepository);
        when(elementListOptionRepository.save(any(ElementListOption.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void create_onAnElementThatIsNotFieldList_isRejected() {
        Element section = mock(Element.class);
        when(section.getId()).thenReturn(1L);
        when(section.getElementType()).thenReturn("SECTION");
        when(elementRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(section));

        assertThatThrownBy(() -> service.create(1L, "OPT_A", "Option A", null, false))
                .isInstanceOf(InvalidElementHierarchyException.class)
                .hasMessageContaining("FIELD_LIST");
    }

    @Test
    void create_withoutExplicitDisplayOrder_appendsAfterLastExistingOption() {
        Element field = mock(Element.class);
        when(field.getId()).thenReturn(2L);
        when(field.getElementType()).thenReturn("FIELD_LIST");
        when(elementRepository.findByIdAndDeletedAtIsNull(2L)).thenReturn(Optional.of(field));

        ElementListOption existing = new ElementListOption(field, "OPT_A", "Option A");
        existing.setDisplayOrder(5);
        when(elementListOptionRepository.findByElementIdOrderByDisplayOrderAsc(2L)).thenReturn(List.of(existing));

        ElementListOption created = service.create(2L, "OPT_B", "Option B", null, false);

        assertThat(created.getDisplayOrder()).isEqualTo(6);
    }

    @Test
    void update_withOnlyLabelProvided_leavesDisplayOrderAndDefaultFlagUnchanged() {
        Element field = mock(Element.class);
        ElementListOption option = mock(ElementListOption.class);
        when(option.getId()).thenReturn(10L);
        when(option.getElement()).thenReturn(field);
        when(field.getId()).thenReturn(2L);
        when(elementListOptionRepository.findById(10L)).thenReturn(Optional.of(option));

        service.update(2L, 10L, "New Label", null, null);

        verify(option).setLabel("New Label");
        verify(option, never()).setDisplayOrder(any());
        verify(option, never()).setDefault(org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void update_withAllFieldsProvided_appliesEveryOne() {
        Element field = mock(Element.class);
        ElementListOption option = mock(ElementListOption.class);
        when(option.getId()).thenReturn(10L);
        when(option.getElement()).thenReturn(field);
        when(field.getId()).thenReturn(2L);
        when(elementListOptionRepository.findById(10L)).thenReturn(Optional.of(option));

        service.update(2L, 10L, "New Label", 7, true);

        verify(option).setLabel("New Label");
        verify(option).setDisplayOrder(7);
        verify(option).setDefault(true);
    }

    @Test
    void deactivate_setsActiveFalseRatherThanDeletingTheRow() {
        Element field = mock(Element.class);
        ElementListOption option = mock(ElementListOption.class);
        when(option.getId()).thenReturn(10L);
        when(option.getElement()).thenReturn(field);
        when(field.getId()).thenReturn(2L);
        when(elementListOptionRepository.findById(10L)).thenReturn(Optional.of(option));

        service.deactivate(2L, 10L);

        verify(option).setActive(false);
        verify(elementListOptionRepository).save(option);
    }
}
