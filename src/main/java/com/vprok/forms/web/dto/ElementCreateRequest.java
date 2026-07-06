package com.vprok.forms.web.dto;

import jakarta.validation.constraints.NotBlank;

/** parentElementId is null only when creating a PAGE. displayOrder defaults to "append to end" if omitted. */
public record ElementCreateRequest(
        Long parentElementId,
        @NotBlank String elementType,
        @NotBlank String code,
        @NotBlank String label,
        Integer displayOrder) {
}
