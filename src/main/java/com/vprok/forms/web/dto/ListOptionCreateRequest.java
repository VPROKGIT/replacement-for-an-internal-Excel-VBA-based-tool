package com.vprok.forms.web.dto;

import jakarta.validation.constraints.NotBlank;

public record ListOptionCreateRequest(
        @NotBlank String code,
        @NotBlank String label,
        Integer displayOrder,
        Boolean isDefault) {
}
