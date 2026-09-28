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
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ElementAttributeValueService {

    static final String CONFIDENTIAL = "CONFIDENTIAL";
    static final String NON_CONFIDENTIAL_VERSION_CODE = "NON_CONFIDENTIAL_VERSION_CODE";

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
        ElementAttributeValue saved = upsert(element, attributeCode, rawValue);
        enforceConfidentialDocumentRule(elementId);
        return saved;
    }

    @Transactional
    public void deleteValue(Long elementId, String attributeCode) {
        getActiveElementOrThrow(elementId);
        remove(elementId, attributeCode);
        enforceConfidentialDocumentRule(elementId);
    }

    /**
     * Applies a whole form's worth of values at once; a null or blank value clears that attribute.
     * Cross-attribute rules are checked once, against the final state, so the outcome never depends
     * on the order values arrive in - and a rejection saves none of them.
     */
    @Transactional
    public void applyValues(Long elementId, Map<String, String> valuesByCode) {
        Element element = getActiveElementOrThrow(elementId);
        valuesByCode.forEach((code, value) -> {
            if (value == null || value.isBlank()) {
                remove(elementId, code);
            } else {
                upsert(element, code, value);
            }
        });
        enforceConfidentialDocumentRule(elementId);
    }

    private ElementAttributeValue upsert(Element element, String attributeCode, String rawValue) {
        AttributeDefinition attributeDefinition = getAttributeDefinitionOrThrow(attributeCode);
        if (!attributeApplicabilityRepository.existsByIdAttributeDefinitionIdAndIdElementType(
                attributeDefinition.getId(), element.getElementType())) {
            throw new InvalidAttributeValueException(
                    "Attribute '%s' is not applicable to element_type %s".formatted(attributeCode, element.getElementType()));
        }
        validateValueMatchesDataType(rawValue, attributeDefinition.getDataType());

        ElementAttributeValue value = elementAttributeValueRepository
                .findByElementIdAndAttributeDefinitionId(element.getId(), attributeDefinition.getId())
                .orElseGet(() -> new ElementAttributeValue(element, attributeDefinition, rawValue));
        value.setValue(rawValue);
        return elementAttributeValueRepository.save(value);
    }

    private void remove(Long elementId, String attributeCode) {
        AttributeDefinition attributeDefinition = getAttributeDefinitionOrThrow(attributeCode);
        elementAttributeValueRepository.deleteByElementIdAndAttributeDefinitionId(elementId, attributeDefinition.getId());
    }

    /**
     * v1 rule for FIELD_DOCUMENT: a confidential document must name its non-confidential version.
     *
     * <p>Deliberately presence-only. It does NOT check that the named code exists on the page, nor
     * that it belongs to a FIELD_DOCUMENT. That is a conscious v1 scope cut (FORMS-15), not an
     * oversight: a dangling or wrong-type reference can be saved, and export consumers must
     * tolerate one.
     */
    private void enforceConfidentialDocumentRule(Long elementId) {
        Map<String, String> values = elementAttributeValueRepository.findByElementId(elementId).stream()
                .collect(Collectors.toMap(v -> v.getAttributeDefinition().getCode(), ElementAttributeValue::getValue));
        boolean confidential = "true".equalsIgnoreCase(values.get(CONFIDENTIAL));
        String counterpart = values.get(NON_CONFIDENTIAL_VERSION_CODE);
        if (confidential && (counterpart == null || counterpart.isBlank())) {
            throw new InvalidAttributeValueException(
                    "A confidential document must name its non-confidential version: set NON_CONFIDENTIAL_VERSION_CODE");
        }
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
