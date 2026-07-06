package com.vprok.forms.web.error;

/** Thrown when a parent/child element_type combination is not allowed by element_type_rule. */
public class InvalidElementHierarchyException extends RuntimeException {

    public InvalidElementHierarchyException(String message) {
        super(message);
    }
}
