package com.vprok.forms.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

@Embeddable
public class ElementTypeRuleId implements Serializable {

    @Column(name = "parent_type", nullable = false, length = 30)
    private String parentType;

    @Column(name = "child_type", nullable = false, length = 30)
    private String childType;

    protected ElementTypeRuleId() {
    }

    public ElementTypeRuleId(String parentType, String childType) {
        this.parentType = parentType;
        this.childType = childType;
    }

    public String getParentType() {
        return parentType;
    }

    public String getChildType() {
        return childType;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ElementTypeRuleId other)) {
            return false;
        }
        return Objects.equals(parentType, other.parentType) && Objects.equals(childType, other.childType);
    }

    @Override
    public int hashCode() {
        return Objects.hash(parentType, childType);
    }
}
