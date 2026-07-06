package com.vprok.forms.web.dto;

import com.vprok.forms.entity.AttributeDataType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record AttributeDefinitionCreateRequest(
        @NotBlank String code,
        @NotBlank String name,
        @NotNull AttributeDataType dataType,
        String description) {
}
