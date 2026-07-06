package com.vprok.forms.web.error;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

/**
 * Translates a DataIntegrityViolationException into a status/message pair by matching known
 * constraint names, so both the REST API (ProblemDetail) and the server-rendered UI (flash
 * message) show the same clear text instead of a raw SQL error.
 */
public record DataIntegrityMessage(HttpStatus status, String message) {

    public static DataIntegrityMessage from(DataIntegrityViolationException ex) {
        String cause = ex.getMostSpecificCause().getMessage();
        if (cause == null) {
            return new DataIntegrityMessage(HttpStatus.CONFLICT, "Data integrity violation");
        }
        if (cause.contains("chk_element_type")) {
            return new DataIntegrityMessage(HttpStatus.BAD_REQUEST, "Unknown element_type");
        }
        if (cause.contains("chk_page_root")) {
            return new DataIntegrityMessage(HttpStatus.BAD_REQUEST,
                    "PAGE elements must have no parent/page; non-PAGE elements must have both");
        }
        if (cause.contains("uq_element_page_code") || cause.contains("uq_element_root_code")
                || cause.contains("uq_eav_element_attr") || cause.contains("uq_list_option_code")) {
            return new DataIntegrityMessage(HttpStatus.CONFLICT, "A row with this code already exists");
        }
        return new DataIntegrityMessage(HttpStatus.CONFLICT, "Data integrity violation");
    }
}
