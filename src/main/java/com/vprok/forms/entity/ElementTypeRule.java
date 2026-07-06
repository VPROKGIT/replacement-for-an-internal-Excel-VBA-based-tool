package com.vprok.forms.entity;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.Objects;

/**
 * Data-driven parent/child element_type compatibility (which types a parent
 * type may contain). The service layer must consult this table rather than
 * hardcoding allowed combinations in Java, so the hierarchy stays flexible.
 */
@Entity
@Table(name = "element_type_rule")
public class ElementTypeRule {

    @EmbeddedId
    private ElementTypeRuleId id;

    protected ElementTypeRule() {
    }

    public ElementTypeRule(String parentType, String childType) {
        this.id = new ElementTypeRuleId(parentType, childType);
    }

    public ElementTypeRuleId getId() {
        return id;
    }

    public String getParentType() {
        return id.getParentType();
    }

    public String getChildType() {
        return id.getChildType();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ElementTypeRule other)) {
            return false;
        }
        return Objects.equals(id, other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
