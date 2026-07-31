package com.vprok.forms.web.dto.export;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/**
 * One node of the exported form structure. The shape is uniform and recursive at every level
 * (page, section, subsection, field) rather than a fixed page/sections/subsections/fields
 * nesting, because the hierarchy itself is data-driven via element_type_rule: fields may sit
 * directly under a section, and new parent/child combinations are a seed row rather than a code
 * change. A fixed shape would re-introduce exactly the rigidity that table exists to avoid.
 *
 * <p>Empty collections are omitted entirely (NON_EMPTY): a missing {@code attributes},
 * {@code options} or {@code children} means "none", never "unknown".
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ExportNode(
        Long id,
        String code,
        String label,
        String type,
        Map<String, Object> attributes,
        List<ExportOption> options,
        List<ExportNode> children) {
}
