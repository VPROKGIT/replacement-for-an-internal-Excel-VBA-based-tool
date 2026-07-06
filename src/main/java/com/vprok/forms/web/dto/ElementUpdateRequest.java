package com.vprok.forms.web.dto;

import jakarta.validation.constraints.NotBlank;

public record ElementUpdateRequest(@NotBlank String label) {
}
