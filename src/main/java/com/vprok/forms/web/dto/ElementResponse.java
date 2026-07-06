package com.vprok.forms.web.dto;

import com.vprok.forms.entity.Element;
import java.time.Instant;

public record ElementResponse(
        Long id,
        Long parentElementId,
        Long pageId,
        String elementType,
        String code,
        String label,
        Integer displayOrder,
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
                element.getCreatedAt(),
                element.getUpdatedAt());
    }
}
