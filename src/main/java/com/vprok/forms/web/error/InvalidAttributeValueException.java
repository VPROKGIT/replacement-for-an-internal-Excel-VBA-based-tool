package com.vprok.forms.web.error;

/** Thrown when a value doesn't match its attribute's data_type, or the attribute isn't applicable to the element_type. */
public class InvalidAttributeValueException extends RuntimeException {

    public InvalidAttributeValueException(String message) {
        super(message);
    }
}
