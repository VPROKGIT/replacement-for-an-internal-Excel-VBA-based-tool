package com.vprok.forms.web.dto.export;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.vprok.forms.entity.ElementListOption;

/**
 * One selectable option of a FIELD_LIST element. Only active options are exported: deactivated
 * ones are kept in the database so historical answers still resolve, but must not be offered
 * for new input.
 *
 * <p>The JSON key is pinned explicitly because Jackson otherwise strips the {@code is} prefix
 * from a boolean accessor and would silently publish this as {@code default}.
 */
public record ExportOption(String code, String label, @JsonProperty("isDefault") boolean isDefault) {

    public static ExportOption from(ElementListOption option) {
        return new ExportOption(option.getCode(), option.getLabel(), option.isDefault());
    }
}
