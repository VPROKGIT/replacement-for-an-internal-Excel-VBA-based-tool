package com.vprok.forms.web.dto;

/** Partial update: null fields are left unchanged. */
public record ListOptionUpdateRequest(String label, Integer displayOrder, Boolean isDefault) {
}
