package com.vprok.forms.service;

import com.vprok.forms.entity.Element;
import com.vprok.forms.entity.UiAttributeDefinition;
import com.vprok.forms.entity.UiAttributeEntry;
import com.vprok.forms.entity.UiAttributeEntryValue;
import com.vprok.forms.repository.ElementRepository;
import com.vprok.forms.repository.UiAttributeDefinitionRepository;
import com.vprok.forms.repository.UiAttributeEntryRepository;
import com.vprok.forms.repository.UiAttributeEntryValueRepository;
import com.vprok.forms.web.error.InvalidAttributeValueException;
import com.vprok.forms.web.error.ResourceNotFoundException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * UI attributes (FORMS-22): an ordered list of entries per element, each entry a set of free-text
 * values keyed by a seeded kind (ui_attribute_definition). Which element types carry entries is
 * seed data as well (ui_attribute_element_type) - fields only, so far.
 *
 * <p>Only non-empty values are stored, trimmed; an entry with none is refused. Entries are kept in
 * a contiguous display order (0, 1, 2, ...) so moving one is a swap with its neighbour.
 */
@Service
public class UiAttributeService {

    private final ElementRepository elementRepository;
    private final UiAttributeDefinitionRepository definitionRepository;
    private final UiAttributeEntryRepository entryRepository;
    private final UiAttributeEntryValueRepository valueRepository;

    public UiAttributeService(
            ElementRepository elementRepository,
            UiAttributeDefinitionRepository definitionRepository,
            UiAttributeEntryRepository entryRepository,
            UiAttributeEntryValueRepository valueRepository) {
        this.elementRepository = elementRepository;
        this.definitionRepository = definitionRepository;
        this.entryRepository = entryRepository;
        this.valueRepository = valueRepository;
    }

    /** Whether elements of this type get UI attribute entries (and the editor's tab). */
    public boolean appliesTo(String elementType) {
        return definitionRepository.isApplicableTo(elementType);
    }

    /** Every kind, in display order: the fields of the editor's box. */
    public List<UiAttributeDefinition> definitions() {
        return definitionRepository.findAllByOrderByDisplayOrderAscIdAsc();
    }

    @Transactional(readOnly = true)
    public List<UiAttributeEntryView> list(Long elementId) {
        getActiveElementOrThrow(elementId);
        return listByElementIds(List.of(elementId)).getOrDefault(elementId, List.of());
    }

    /**
     * Entries of several elements in one query, keyed by element id, each list in entry order.
     * Does not check the elements: the caller (the JSON export) already read them as non-deleted.
     */
    @Transactional(readOnly = true)
    public Map<Long, List<UiAttributeEntryView>> listByElementIds(Collection<Long> elementIds) {
        // Values arrive grouped by element, then entry, then kind (see the query), so appending in
        // arrival order keeps both orders.
        Map<Long, Map<Long, List<UiAttributeEntryView.Value>>> byElement = new LinkedHashMap<>();
        for (UiAttributeEntryValue value : valueRepository.findByElementIdIn(elementIds)) {
            UiAttributeEntry entry = value.getEntry();
            byElement
                    .computeIfAbsent(entry.getElement().getId(), id -> new LinkedHashMap<>())
                    .computeIfAbsent(entry.getId(), id -> new ArrayList<>())
                    .add(new UiAttributeEntryView.Value(value.getDefinition().getCode(), value.getValue()));
        }
        Map<Long, List<UiAttributeEntryView>> result = new LinkedHashMap<>();
        byElement.forEach((elementId, entries) -> result.put(elementId, entries.entrySet().stream()
                .map(e -> new UiAttributeEntryView(e.getKey(), List.copyOf(e.getValue())))
                .toList()));
        return Collections.unmodifiableMap(result);
    }

    /** Appends a new entry after the element's existing ones. */
    @Transactional
    public UiAttributeEntry add(Long elementId, Map<String, String> valuesByCode) {
        Element element = getActiveElementOrThrow(elementId);
        requireApplicable(element);
        Map<UiAttributeDefinition, String> values = resolveValues(valuesByCode);
        List<UiAttributeEntry> existing = entryRepository.findByElementIdOrderByDisplayOrderAscIdAsc(elementId);
        int nextOrder = existing.isEmpty() ? 0 : existing.get(existing.size() - 1).getDisplayOrder() + 1;
        UiAttributeEntry entry = entryRepository.save(new UiAttributeEntry(element, nextOrder));
        saveValues(entry, values);
        return entry;
    }

    /** Replaces an entry's values with these; its place in the list is kept. */
    @Transactional
    public void update(Long elementId, Long entryId, Map<String, String> valuesByCode) {
        Element element = getActiveElementOrThrow(elementId);
        requireApplicable(element);
        UiAttributeEntry entry = getEntryOrThrow(elementId, entryId);
        Map<UiAttributeDefinition, String> values = resolveValues(valuesByCode);
        valueRepository.deleteByEntryId(entryId);
        saveValues(entry, values);
        entry.touch();
        entryRepository.save(entry);
    }

    @Transactional
    public void delete(Long elementId, Long entryId) {
        getActiveElementOrThrow(elementId);
        UiAttributeEntry entry = getEntryOrThrow(elementId, entryId);
        valueRepository.deleteByEntryId(entryId);
        entryRepository.delete(entry);
        entryRepository.flush();
        List<UiAttributeEntry> remaining = entryRepository.findByElementIdOrderByDisplayOrderAscIdAsc(elementId);
        resequence(remaining);
    }

    /** Swaps the entry with its neighbour ({@code direction} -1 = up, +1 = down); a no-op at either end. */
    @Transactional
    public void move(Long elementId, Long entryId, int direction) {
        getActiveElementOrThrow(elementId);
        getEntryOrThrow(elementId, entryId);
        List<UiAttributeEntry> entries = new ArrayList<>(entryRepository.findByElementIdOrderByDisplayOrderAscIdAsc(elementId));
        int index = entries.stream().map(UiAttributeEntry::getId).toList().indexOf(entryId);
        int swapWith = index + direction;
        if (swapWith >= 0 && swapWith < entries.size()) {
            Collections.swap(entries, index, swapWith);
        }
        resequence(entries);
    }

    /**
     * Copies every entry of {@code source} onto {@code target}, in order - for a deep copy of an
     * element (MAP template cloning). Skips the applicability check: the copy has the source's type.
     */
    @Transactional
    public void copyEntries(Long sourceElementId, Element target) {
        Map<String, UiAttributeDefinition> definitionsByCode = definitions().stream()
                .collect(Collectors.toMap(UiAttributeDefinition::getCode, Function.identity()));
        List<UiAttributeEntryView> entries = listByElementIds(List.of(sourceElementId)).getOrDefault(sourceElementId, List.of());
        for (int i = 0; i < entries.size(); i++) {
            UiAttributeEntry copy = entryRepository.save(new UiAttributeEntry(target, i));
            for (UiAttributeEntryView.Value value : entries.get(i).values()) {
                valueRepository.save(new UiAttributeEntryValue(copy, definitionsByCode.get(value.code()), value.value()));
            }
        }
    }

    // --- helpers ----------------------------------------------------------------------------

    /**
     * The submitted values as kind -> trimmed text, blanks dropped. An unknown code is refused
     * rather than ignored, and so is an entry left with no value at all.
     */
    private Map<UiAttributeDefinition, String> resolveValues(Map<String, String> valuesByCode) {
        Map<String, UiAttributeDefinition> definitionsByCode = definitions().stream()
                .collect(Collectors.toMap(UiAttributeDefinition::getCode, Function.identity()));
        Map<UiAttributeDefinition, String> resolved = new LinkedHashMap<>();
        valuesByCode.forEach((code, value) -> {
            UiAttributeDefinition definition = definitionsByCode.get(code);
            if (definition == null) {
                throw new InvalidAttributeValueException("Unknown UI attribute '%s'".formatted(code));
            }
            if (value != null && !value.isBlank()) {
                resolved.put(definition, value.strip());
            }
        });
        if (resolved.isEmpty()) {
            throw new InvalidAttributeValueException("A UI attribute entry needs at least one value");
        }
        return resolved;
    }

    private void saveValues(UiAttributeEntry entry, Map<UiAttributeDefinition, String> values) {
        values.forEach((definition, value) -> valueRepository.save(new UiAttributeEntryValue(entry, definition, value)));
    }

    private void resequence(List<UiAttributeEntry> entries) {
        for (int i = 0; i < entries.size(); i++) {
            UiAttributeEntry entry = entries.get(i);
            if (entry.getDisplayOrder() != i) {
                entry.setDisplayOrder(i);
                entryRepository.save(entry);
            }
        }
    }

    private void requireApplicable(Element element) {
        if (!appliesTo(element.getElementType())) {
            throw new InvalidAttributeValueException(
                    "UI attributes are not available for element_type %s".formatted(element.getElementType()));
        }
    }

    private UiAttributeEntry getEntryOrThrow(Long elementId, Long entryId) {
        UiAttributeEntry entry = entryRepository.findById(entryId)
                .orElseThrow(() -> new ResourceNotFoundException("UI attribute entry " + entryId + " not found"));
        if (!entry.getElement().getId().equals(elementId)) {
            throw new ResourceNotFoundException("UI attribute entry " + entryId + " not found for element " + elementId);
        }
        return entry;
    }

    private Element getActiveElementOrThrow(Long elementId) {
        return elementRepository.findByIdAndDeletedAtIsNull(elementId)
                .orElseThrow(() -> new ResourceNotFoundException("Element " + elementId + " not found"));
    }
}
