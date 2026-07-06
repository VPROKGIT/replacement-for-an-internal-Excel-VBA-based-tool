package com.vprok.forms.web.dto;

import com.vprok.forms.entity.ElementListOption;

public record ListOptionResponse(
        Long id,
        Long elementId,
        String code,
        String label,
        Integer displayOrder,
        boolean isDefault,
        boolean active) {

    public static ListOptionResponse from(ElementListOption option) {
        return new ListOptionResponse(
                option.getId(),
                option.getElement().getId(),
                option.getCode(),
                option.getLabel(),
                option.getDisplayOrder(),
                option.isDefault(),
                option.isActive());
    }
}
