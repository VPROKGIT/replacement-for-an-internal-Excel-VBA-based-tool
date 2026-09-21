package com.vprok.forms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vprok.forms.entity.AttributeDataType;
import com.vprok.forms.entity.AttributeDefinition;
import com.vprok.forms.entity.Element;
import com.vprok.forms.entity.ElementAttributeValue;
import com.vprok.forms.repository.AttributeApplicabilityRepository;
import com.vprok.forms.repository.AttributeDefinitionRepository;
import com.vprok.forms.repository.ElementAttributeValueRepository;
import com.vprok.forms.repository.ElementRepository;
import com.vprok.forms.web.error.InvalidAttributeValueException;
import com.vprok.forms.web.error.ResourceNotFoundException;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the data-type validation and upsert logic in ElementAttributeValueService.
 * StructureApiIT only ever exercises this through the HTTP layer for BOOLEAN (valid) and DECIMAL
 * (invalid) - DATE is never touched at all there, and the "set the same attribute twice" upsert
 * path (as opposed to first-time create) is never exercised either. Repositories are mocked.
 */
class ElementAttributeValueServiceTest {

    private ElementRepository elementRepository;
    private AttributeDefinitionRepository attributeDefinitionRepository;
    private AttributeApplicabilityRepository attributeApplicabilityRepository;
    private ElementAttributeValueRepository elementAttributeValueRepository;
    private ElementAttributeValueService service;

    private Element element;
    private AttributeDefinition dateAttr;

    @BeforeEach
    void setUp() {
        elementRepository = mock(ElementRepository.class);
        attributeDefinitionRepository = mock(AttributeDefinitionRepository.class);
        attributeApplicabilityRepository = mock(AttributeApplicabilityRepository.class);
        elementAttributeValueRepository = mock(ElementAttributeValueRepository.class);
        service = new ElementAttributeValueService(
                elementRepository, attributeDefinitionRepository, attributeApplicabilityRepository, elementAttributeValueRepository);

        element = mock(Element.class);
        when(element.getId()).thenReturn(1L);
        when(element.getElementType()).thenReturn("FIELD_DATE");
        when(elementRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(element));

        dateAttr = mock(AttributeDefinition.class);
        when(dateAttr.getId()).thenReturn(99L);
        when(dateAttr.getDataType()).thenReturn(AttributeDataType.DATE);
        when(attributeDefinitionRepository.findByCode("DUE_DATE")).thenReturn(Optional.of(dateAttr));
        when(attributeApplicabilityRepository.existsByIdAttributeDefinitionIdAndIdElementType(99L, "FIELD_DATE"))
                .thenReturn(true);
        when(elementAttributeValueRepository.save(any(ElementAttributeValue.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void setValue_dateAttribute_acceptsAValidIsoDate() {
        when(elementAttributeValueRepository.findByElementIdAndAttributeDefinitionId(1L, 99L)).thenReturn(Optional.empty());

        ElementAttributeValue result = service.setValue(1L, "DUE_DATE", "2026-08-06");

        assertThat(result.getValue()).isEqualTo("2026-08-06");
    }

    @Test
    void setValue_dateAttribute_rejectsANonIsoString() {
        when(elementAttributeValueRepository.findByElementIdAndAttributeDefinitionId(1L, 99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.setValue(1L, "DUE_DATE", "not-a-date"))
                .isInstanceOf(InvalidAttributeValueException.class)
                .hasMessageContaining("not-a-date");
    }

    @Test
    void setValue_booleanAttribute_isCaseInsensitive() {
        AttributeDefinition boolAttr = mock(AttributeDefinition.class);
        when(boolAttr.getId()).thenReturn(50L);
        when(boolAttr.getDataType()).thenReturn(AttributeDataType.BOOLEAN);
        when(attributeDefinitionRepository.findByCode("MANDATORY")).thenReturn(Optional.of(boolAttr));
        when(attributeApplicabilityRepository.existsByIdAttributeDefinitionIdAndIdElementType(50L, "FIELD_DATE"))
                .thenReturn(true);
        when(elementAttributeValueRepository.findByElementIdAndAttributeDefinitionId(1L, 50L)).thenReturn(Optional.empty());

        ElementAttributeValue result = service.setValue(1L, "MANDATORY", "TRUE");

        assertThat(result.getValue()).isEqualTo("TRUE");
    }

    @Test
    void setValue_whenAValueAlreadyExists_updatesItInPlaceRatherThanCreatingAnother() {
        ElementAttributeValue existing = mock(ElementAttributeValue.class);
        when(elementAttributeValueRepository.findByElementIdAndAttributeDefinitionId(1L, 99L))
                .thenReturn(Optional.of(existing));
        when(elementAttributeValueRepository.save(existing)).thenReturn(existing);

        ElementAttributeValue result = service.setValue(1L, "DUE_DATE", "2026-01-01");

        verify(existing).setValue("2026-01-01");
        verify(elementAttributeValueRepository, times(1)).save(any(ElementAttributeValue.class));
        verify(elementAttributeValueRepository).save(existing);
        assertThat(result).isSameAs(existing);
    }

    @Test
    void deleteValue_forAnUnknownAttributeCode_throwsResourceNotFound() {
        when(attributeDefinitionRepository.findByCode("NO_SUCH_ATTR")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteValue(1L, "NO_SUCH_ATTR"))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
