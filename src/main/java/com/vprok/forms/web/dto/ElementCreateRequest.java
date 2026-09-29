package com.vprok.forms.web.dto;

import com.vprok.forms.entity.GridPosition;
import jakarta.validation.constraints.NotBlank;

/**
 * parentElementId is null only when creating a PAGE. displayOrder defaults to "append to end" if
 * omitted. layout places a child of a grid (MATRIX) in a given cell; omitted, it takes the first
 * free one. It is rejected anywhere else.
 */
public record ElementCreateRequest(
        Long parentElementId,
        @NotBlank String elementType,
        @NotBlank String code,
        @NotBlank String label,
        Integer displayOrder,
        GridPosition layout) {
}
