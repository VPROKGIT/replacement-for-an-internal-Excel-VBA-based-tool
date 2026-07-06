package com.vprok.forms.web.error;

import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    public ProblemDetail handleNotFound(ResourceNotFoundException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler({InvalidElementHierarchyException.class, InvalidAttributeValueException.class})
    public ProblemDetail handleBadRequest(RuntimeException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail.isEmpty() ? "Validation failed" : detail);
    }

    /**
     * Catches CHECK/unique constraint violations that slip past service-layer validation (defense
     * in depth, not the primary path) and maps known constraint names to a clearer status/message
     * than a bare 500 or generic conflict.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        String cause = ex.getMostSpecificCause().getMessage();
        if (cause == null) {
            return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Data integrity violation");
        }
        if (cause.contains("chk_element_type")) {
            return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Unknown element_type");
        }
        if (cause.contains("chk_page_root")) {
            return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                    "PAGE elements must have no parent/page; non-PAGE elements must have both");
        }
        if (cause.contains("uq_element_page_code") || cause.contains("uq_element_root_code")
                || cause.contains("uq_eav_element_attr") || cause.contains("uq_list_option_code")) {
            return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "A row with this code already exists");
        }
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Data integrity violation");
    }
}
