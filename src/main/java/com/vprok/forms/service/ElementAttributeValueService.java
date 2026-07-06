package com.vprok.forms.service;

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
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ElementAttributeValueService {

    private final ElementRepository elementRepository;
    private final AttributeDefinitionRepository attributeDefinitionRepository;
    private final AttributeApplicabilityRepository attributeApplicabilityRepository;
    private final ElementAttributeValueRepository elementAttributeValueRepository;

    public ElementAttributeValueService(
            ElementRepository elementRepository,
            AttributeDefinitionRepository attributeDefinitionRepository,
            AttributeApplicabilityRepository attributeApplicabilityRepository,
            ElementAttributeValueRepository elementAttributeValueRepository) {
        this.elementRepository = elementRepository;
        this.attributeDefinitionRepository = attributeDefinitionRepository;
        this.attributeApplicabilityRepository = attributeApplicabilityRepository;
        this.elementAttributeValueRepository = elementAttributeValueRepository;
    }

    public List<ElementAttributeValue> list(Long elementId) {
        getActiveElementOrThrow(elementId);
        return elementAttributeValueRepository.findByElementId(elementId);
    }

    @Transactional
    public ElementAttributeValue setValue(Long elementId, String attributeCode, String rawValue) {
        Element element = getActiveElementOrThrow(elementId);
        AttributeDefinition attributeDefinition = getAttributeDefinitionOrThrow(attributeCode);

        if (!attributeApplicabilityRepository.existsByIdAttributeDefinitionIdAndIdElementType(
                attributeDefinition.getId(), element.getElementType())) {
            throw new InvalidAttributeValueException(
                    "Attribute '%s' is not applicable to element_type %s".formatted(attributeCode, element.getElementType()));
        }
        validateValueMatchesDataType(rawValue, attributeDefinition.getDataType());

        ElementAttributeValue value = elementAttributeValueRepository
                .findByElementIdAndAttributeDefinitionId(elementId, attributeDefinition.getId())
                .orElseGet(() -> new ElementAttributeValue(element, attributeDefinition, rawValue));
        value.setValue(rawValue);
        return elementAttributeValueRepository.save(value);
    }

    @Transactional
    public void deleteValue(Long elementId, String attributeCode) {
        getActiveElementOrThrow(elementId);
        AttributeDefinition attributeDefinition = getAttributeDefinitionOrThrow(attributeCode);
        elementAttributeValueRepository.deleteByElementIdAndAttributeDefinitionId(elementId, attributeDefinition.getId());
    }

    private Element getActiveElementOrThrow(Long elementId) {
        return elementRepository.findByIdAndDeletedAtIsNull(elementId)
                .orElseThrow(() -> new ResourceNotFoundException("Element " + elementId + " not found"));
    }

    private AttributeDefinition getAttributeDefinitionOrThrow(String code) {
        return attributeDefinitionRepository.findByCode(code)
                .orElseThrow(() -> new ResourceNotFoundException("Attribute definition '" + code + "' not found"));
    }

    private void validateValueMatchesDataType(String value, AttributeDataType dataType) {
        boolean valid = switch (dataType) {
            case BOOLEAN -> "true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value);
            case INTEGER -> isValidLong(value);
            case DECIMAL -> isValidDecimal(value);
            case DATE -> isValidDate(value);
            case STRING -> true;
        };
        if (!valid) {
            throw new InvalidAttributeValueException("Value '%s' is not a valid %s".formatted(value, dataType));
        }
    }

    private boolean isValidLong(String value) {
        try {
            Long.parseLong(value);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private boolean isValidDecimal(String value) {
        try {
            new BigDecimal(value);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private boolean isValidDate(String value) {
        try {
            LocalDate.parse(value);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }
}
