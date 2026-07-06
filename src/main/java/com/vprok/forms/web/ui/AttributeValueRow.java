package com.vprok.forms.web.ui;

import com.vprok.forms.entity.AttributeDataType;

/** One row of the attribute-value editor form: a definition applicable to the element's type, plus its current value (blank if unset). */
public record AttributeValueRow(String code, String name, AttributeDataType dataType, String currentValue) {

    public boolean isBoolean() {
        return dataType == AttributeDataType.BOOLEAN;
    }

    public boolean isDate() {
        return dataType == AttributeDataType.DATE;
    }

    public boolean isNumeric() {
        return dataType == AttributeDataType.INTEGER || dataType == AttributeDataType.DECIMAL;
    }
}
