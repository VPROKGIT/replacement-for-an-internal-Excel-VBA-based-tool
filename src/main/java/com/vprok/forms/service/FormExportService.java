package com.vprok.forms.service;

import com.vprok.forms.entity.AttributeDataType;
import com.vprok.forms.entity.Element;
import com.vprok.forms.entity.ElementAttributeValue;
import com.vprok.forms.entity.ElementListOption;
import com.vprok.forms.repository.ElementAttributeValueRepository;
import com.vprok.forms.repository.ElementListOptionRepository;
import com.vprok.forms.repository.ElementRepository;
import com.vprok.forms.web.dto.export.ExportNode;
import com.vprok.forms.web.dto.export.ExportOption;
import com.vprok.forms.web.error.ResourceNotFoundException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the frontend-facing JSON structure for a whole page. See docs/json-export-schema.md for
 * the documented contract.
 *
 * <p>Reads go exclusively through the soft-delete-filtered repository methods, so a deleted
 * element (or any of its descendants, which the delete cascade also soft-deletes) can never
 * appear in an export.
 */
@Service
public class FormExportService {

    private static final String PAGE_TYPE = "PAGE";

    private final ElementRepository elementRepository;
    private final ElementAttributeValueRepository elementAttributeValueRepository;
    private final ElementListOptionRepository elementListOptionRepository;

    public FormExportService(
            ElementRepository elementRepository,
            ElementAttributeValueRepository elementAttributeValueRepository,
            ElementListOptionRepository elementListOptionRepository) {
        this.elementRepository = elementRepository;
        this.elementListOptionRepository = elementListOptionRepository;
        this.elementAttributeValueRepository = elementAttributeValueRepository;
    }

    // A template page is deliberately indistinguishable from a missing one here (404, not 403 or a
    // flagged payload): it is not a form, and must never be served as though it were.
    @Transactional(readOnly = true)
    public ExportNode exportByCode(String pageCode) {
        Element page = elementRepository.findByElementTypeAndCodeAndDeletedAtIsNull(PAGE_TYPE, pageCode)
                .filter(e -> !e.isTemplate())
                .orElseThrow(() -> new ResourceNotFoundException("Page '" + pageCode + "' not found"));
        return export(page);
    }

    @Transactional(readOnly = true)
    public ExportNode exportById(Long pageId) {
        Element page = elementRepository.findByIdAndDeletedAtIsNull(pageId)
                .filter(e -> PAGE_TYPE.equals(e.getElementType()) && !e.isTemplate())
                .orElseThrow(() -> new ResourceNotFoundException("Page " + pageId + " not found"));
        return export(page);
    }

    /**
     * Loads the whole page in a fixed number of queries (elements, attribute values, list options)
     * and assembles the tree in memory, rather than querying per node while recursing.
     */
    private ExportNode export(Element page) {
        // Every non-PAGE element carries page_id; the PAGE row itself has page_id NULL, hence the
        // separate lookup above. Already ordered by display_order, which grouping preserves.
        List<Element> descendants = elementRepository.findByPageIdAndDeletedAtIsNullOrderByDisplayOrderAsc(page.getId());

        List<Long> allIds = new ArrayList<>();
        allIds.add(page.getId());
        descendants.forEach(e -> allIds.add(e.getId()));

        Map<Long, List<Element>> childrenByParentId = descendants.stream()
                .collect(Collectors.groupingBy(e -> e.getParentElement().getId()));

        Map<Long, Map<String, Object>> attributesByElementId = loadAttributes(allIds);
        Map<Long, List<ExportOption>> optionsByElementId = loadOptions(allIds);

        return toNode(page, childrenByParentId, attributesByElementId, optionsByElementId);
    }

    private ExportNode toNode(
            Element element,
            Map<Long, List<Element>> childrenByParentId,
            Map<Long, Map<String, Object>> attributesByElementId,
            Map<Long, List<ExportOption>> optionsByElementId) {

        List<ExportNode> children = childrenByParentId.getOrDefault(element.getId(), List.of()).stream()
                .map(child -> toNode(child, childrenByParentId, attributesByElementId, optionsByElementId))
                .toList();

        return new ExportNode(
                element.getId(),
                element.getCode(),
                element.getLabel(),
                element.getElementType(),
                attributesByElementId.getOrDefault(element.getId(), Map.of()),
                optionsByElementId.getOrDefault(element.getId(), List.of()),
                children);
    }

    private Map<Long, Map<String, Object>> loadAttributes(List<Long> elementIds) {
        Map<Long, Map<String, Object>> byElementId = new LinkedHashMap<>();
        for (ElementAttributeValue value : elementAttributeValueRepository.findByElementIdIn(elementIds)) {
            // Sorted by key so the payload is byte-stable across runs and diffs cleanly.
            byElementId
                    .computeIfAbsent(value.getElement().getId(), id -> new TreeMap<>())
                    .put(
                            toCamelCase(value.getAttributeDefinition().getCode()),
                            typedValue(value.getValue(), value.getAttributeDefinition().getDataType()));
        }
        return byElementId;
    }

    private Map<Long, List<ExportOption>> loadOptions(List<Long> elementIds) {
        Map<Long, List<ExportOption>> byElementId = new LinkedHashMap<>();
        for (ElementListOption option : elementListOptionRepository
                .findByElementIdInAndActiveTrueOrderByDisplayOrderAsc(elementIds)) {
            byElementId
                    .computeIfAbsent(option.getElement().getId(), id -> new ArrayList<>())
                    .add(ExportOption.from(option));
        }
        return Collections.unmodifiableMap(byElementId);
    }

    /**
     * MAX_LENGTH -> maxLength. A deterministic rule rather than a lookup table, so an attribute
     * added as a seed row gets a sensible JSON key without a code change.
     */
    private static String toCamelCase(String attributeCode) {
        String[] parts = attributeCode.toLowerCase(Locale.ROOT).split("_");
        StringBuilder camel = new StringBuilder(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            if (parts[i].isEmpty()) {
                continue;
            }
            camel.append(Character.toUpperCase(parts[i].charAt(0))).append(parts[i].substring(1));
        }
        return camel.toString();
    }

    /**
     * Values are stored as text and validated against their data_type on write (FORMS-5), so this
     * normally always succeeds. A value that slipped in another way (direct SQL) falls back to the
     * raw string rather than failing the whole export.
     */
    private static Object typedValue(String rawValue, AttributeDataType dataType) {
        try {
            return switch (dataType) {
                case BOOLEAN -> Boolean.parseBoolean(rawValue);
                case INTEGER -> Long.valueOf(rawValue);
                case DECIMAL -> new BigDecimal(rawValue);
                // Kept as an ISO-8601 (yyyy-MM-dd) string; JSON has no date type.
                case DATE, STRING -> rawValue;
            };
        } catch (NumberFormatException e) {
            return rawValue;
        }
    }
}
