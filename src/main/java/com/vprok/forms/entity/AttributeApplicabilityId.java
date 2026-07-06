package com.vprok.forms.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

@Embeddable
public class AttributeApplicabilityId implements Serializable {

    @Column(name = "attribute_definition_id", nullable = false)
    private Long attributeDefinitionId;

    @Column(name = "element_type", nullable = false, length = 30)
    private String elementType;

    protected AttributeApplicabilityId() {
    }

    public AttributeApplicabilityId(Long attributeDefinitionId, String elementType) {
        this.attributeDefinitionId = attributeDefinitionId;
        this.elementType = elementType;
    }

    public Long getAttributeDefinitionId() {
        return attributeDefinitionId;
    }

    public String getElementType() {
        return elementType;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AttributeApplicabilityId other)) {
            return false;
        }
        return Objects.equals(attributeDefinitionId, other.attributeDefinitionId)
                && Objects.equals(elementType, other.elementType);
    }

    @Override
    public int hashCode() {
        return Objects.hash(attributeDefinitionId, elementType);
    }
}
