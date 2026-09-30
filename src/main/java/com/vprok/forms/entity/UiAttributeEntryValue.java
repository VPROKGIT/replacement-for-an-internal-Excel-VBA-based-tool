package com.vprok.forms.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** One non-empty value of a UI attribute entry. */
@Entity
@Table(name = "ui_attribute_entry_value")
public class UiAttributeEntryValue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "entry_id", nullable = false)
    private UiAttributeEntry entry;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ui_attribute_definition_id", nullable = false)
    private UiAttributeDefinition definition;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String value;

    protected UiAttributeEntryValue() {
    }

    public UiAttributeEntryValue(UiAttributeEntry entry, UiAttributeDefinition definition, String value) {
        this.entry = entry;
        this.definition = definition;
        this.value = value;
    }

    public Long getId() {
        return id;
    }

    public UiAttributeEntry getEntry() {
        return entry;
    }

    public UiAttributeDefinition getDefinition() {
        return definition;
    }

    public String getValue() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof UiAttributeEntryValue other)) {
            return false;
        }
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
