package com.vprok.forms.entity;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.Objects;

/** Which element_types a given attribute_definition may be attached to. */
@Entity
@Table(name = "attribute_applicability")
public class AttributeApplicability {

    @EmbeddedId
    private AttributeApplicabilityId id;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("attributeDefinitionId")
    @JoinColumn(name = "attribute_definition_id")
    private AttributeDefinition attributeDefinition;

    protected AttributeApplicability() {
    }

    public AttributeApplicability(AttributeDefinition attributeDefinition, String elementType) {
        this.attributeDefinition = attributeDefinition;
        this.id = new AttributeApplicabilityId(attributeDefinition.getId(), elementType);
    }

    public AttributeApplicabilityId getId() {
        return id;
    }

    public AttributeDefinition getAttributeDefinition() {
        return attributeDefinition;
    }

    public String getElementType() {
        return id.getElementType();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AttributeApplicability other)) {
            return false;
        }
        return Objects.equals(id, other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
