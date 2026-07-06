package com.vprok.forms.web.dto;

import jakarta.validation.constraints.NotNull;

/** Not @NotBlank: an empty string is a legitimate STRING-typed value (e.g. an empty default/placeholder). */
public record AttributeValueRequest(@NotNull String value) {
}
