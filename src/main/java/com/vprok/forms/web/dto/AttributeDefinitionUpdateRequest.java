package com.vprok.forms.web.dto;

import jakarta.validation.constraints.NotBlank;

/** code and dataType are immutable after creation: attribute_applicability and existing values key off them. */
public record AttributeDefinitionUpdateRequest(@NotBlank String name, String description) {
}
