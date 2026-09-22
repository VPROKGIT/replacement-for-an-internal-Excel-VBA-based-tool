package com.vprok.forms.web.ui;

import com.vprok.forms.web.dto.ElementResponse;
import java.util.List;

/** A MAP on a template page, as offered in the templates browser and the "use this template" picker. */
public record MapTemplateOption(
        Long id,
        String code,
        String label,
        Long templatePageId,
        String templatePageLabel,
        List<ElementResponse> fields) {
}
