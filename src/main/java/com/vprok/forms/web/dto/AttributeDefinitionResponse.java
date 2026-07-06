package com.vprok.forms.web.dto;

import com.vprok.forms.entity.AttributeDataType;
import com.vprok.forms.entity.AttributeDefinition;

public record AttributeDefinitionResponse(Long id, String code, String name, AttributeDataType dataType, String description) {

    public static AttributeDefinitionResponse from(AttributeDefinition definition) {
        return new AttributeDefinitionResponse(
                definition.getId(),
                definition.getCode(),
                definition.getName(),
                definition.getDataType(),
                definition.getDescription());
    }
}
