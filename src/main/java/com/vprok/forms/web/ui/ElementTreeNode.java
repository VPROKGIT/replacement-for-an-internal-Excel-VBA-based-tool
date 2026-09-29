package com.vprok.forms.web.ui;

import com.vprok.forms.web.dto.ElementResponse;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * A tree node pre-fetched for rendering: children and allowed-child-types come along so the
 * template never has to call back into services.
 *
 * <p>Everything the editor derives from the type is derived from data, not a list of known types:
 * a node is drawn as a box when the hierarchy rules let it hold children, and its add buttons are
 * its allowed child types, with all {@code FIELD_*} types behind one "Field" button (the naming
 * convention the export contract already relies on) and every other type on its own button.
 */
public record ElementTreeNode(ElementResponse element, List<ElementTreeNode> children, List<String> allowedChildTypes) {

    private static final String FIELD_PREFIX = "FIELD_";

    /** One add button: its caption, and the types its inline form offers (a single one needs no picker). */
    public record AddGroup(String label, List<TypeChoice> types) {
    }

    public record TypeChoice(String value, String label) {
    }

    public boolean isContainer() {
        return !allowedChildTypes.isEmpty();
    }

    public String getTypeLabel() {
        return typeLabel(element.elementType());
    }

    public List<AddGroup> getAddGroups() {
        return addGroups(allowedChildTypes);
    }

    public static List<AddGroup> addGroups(List<String> allowedChildTypes) {
        List<TypeChoice> fields = new ArrayList<>();
        List<AddGroup> groups = new ArrayList<>();
        for (String type : allowedChildTypes) {
            if (type.startsWith(FIELD_PREFIX)) {
                fields.add(new TypeChoice(type, typeLabel(type)));
            } else {
                groups.add(new AddGroup(typeLabel(type), List.of(new TypeChoice(type, typeLabel(type)))));
            }
        }
        // The rule table has no order of its own: sort, so the buttons sit in the same place everywhere.
        fields.sort(Comparator.comparing(TypeChoice::label));
        groups.sort(Comparator.comparing(AddGroup::label));
        if (!fields.isEmpty()) {
            groups.add(0, new AddGroup("Field", fields));
        }
        return groups;
    }

    /** {@code FIELD_TEXTAREA} → "Textarea", {@code SUBSECTION} → "Subsection". */
    public static String typeLabel(String type) {
        String name = (type.startsWith(FIELD_PREFIX) ? type.substring(FIELD_PREFIX.length()) : type)
                .replace('_', ' ')
                .toLowerCase(Locale.ROOT);
        return name.isEmpty() ? type : Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }
}
