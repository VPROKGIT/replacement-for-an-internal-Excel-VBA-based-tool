package com.vprok.forms.web.dto;

import com.vprok.forms.entity.Element;
import com.vprok.forms.entity.GridPosition;
import java.time.Instant;

/** layout is the element's cell when its parent is a grid (MATRIX), null otherwise. */
public record ElementResponse(
        Long id,
        Long parentElementId,
        Long pageId,
        String elementType,
        String code,
        String label,
        Integer displayOrder,
        GridPosition layout,
        Instant createdAt,
        Instant updatedAt) {

    public static ElementResponse from(Element element) {
        return new ElementResponse(
                element.getId(),
                element.getParentElement() != null ? element.getParentElement().getId() : null,
                element.getPage() != null ? element.getPage().getId() : null,
                element.getElementType(),
                element.getCode(),
                element.getLabel(),
                element.getDisplayOrder(),
                element.getGridPosition(),
                element.getCreatedAt(),
                element.getUpdatedAt());
    }
}
