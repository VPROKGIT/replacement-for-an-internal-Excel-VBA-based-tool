package com.vprok.forms.service;

import com.vprok.forms.entity.Element;
import com.vprok.forms.entity.ElementAttributeValue;
import com.vprok.forms.entity.ElementListOption;
import com.vprok.forms.repository.ElementAttributeValueRepository;
import com.vprok.forms.repository.ElementListOptionRepository;
import com.vprok.forms.repository.ElementRepository;
import com.vprok.forms.web.error.InvalidElementHierarchyException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Template pages and cloning MAPs out of them.
 *
 * <p>A clone is a one-time deep copy: fresh rows, fresh ids, no reference back to the source. Once
 * made, the copy and the template are unrelated - editing or deleting either never touches the
 * other. There is deliberately no "linked" or "synced" mode.
 */
@Service
public class MapTemplateService {

    private static final String PAGE_TYPE = "PAGE";
    private static final String MAP_TYPE = "MAP";

    private final ElementService elementService;
    private final ElementRepository elementRepository;
    private final ElementAttributeValueRepository elementAttributeValueRepository;
    private final ElementListOptionRepository elementListOptionRepository;

    public MapTemplateService(
            ElementService elementService,
            ElementRepository elementRepository,
            ElementAttributeValueRepository elementAttributeValueRepository,
            ElementListOptionRepository elementListOptionRepository) {
        this.elementService = elementService;
        this.elementRepository = elementRepository;
        this.elementAttributeValueRepository = elementAttributeValueRepository;
        this.elementListOptionRepository = elementListOptionRepository;
    }

    public List<Element> getTemplatePages() {
        return elementRepository.findByPageIdIsNullAndTemplateTrueAndDeletedAtIsNullOrderByCodeAsc();
    }

    /** Every active MAP on an active template page, with its page loaded. */
    public List<Element> getTemplateMaps() {
        return elementRepository.findActiveByElementTypeOnTemplatePages(MAP_TYPE);
    }

    @Transactional
    public Element createTemplatePage(String code, String label) {
        Element page = elementService.create(null, PAGE_TYPE, code, label, null);
        page.setTemplate(true);
        return elementRepository.save(page);
    }

    @Transactional
    public Element setTemplate(Long pageId, boolean template) {
        Element page = elementService.getActiveOrThrow(pageId);
        if (!PAGE_TYPE.equals(page.getElementType())) {
            throw new InvalidElementHierarchyException("Only a PAGE can be marked as a template");
        }
        page.setTemplate(template);
        return elementRepository.save(page);
    }

    /**
     * Deep-copies a template MAP - the MAP, every descendant, their attribute values and all list
     * options (active and inactive, flags preserved) - under {@code targetParentId}.
     *
     * <p>Codes are page-scoped, so any copied code already used anywhere on the target page is
     * suffixed ({@code _2}, {@code _3}, ...) instead of failing the whole clone. The renames are
     * reported back so the caller can tell the user.
     */
    @Transactional
    public MapCloneResult cloneMapInto(Long sourceMapId, Long targetParentId) {
        Element source = elementService.getActiveOrThrow(sourceMapId);
        if (!MAP_TYPE.equals(source.getElementType())) {
            throw new InvalidElementHierarchyException(
                    "Element %s is a %s, not a MAP".formatted(source.getCode(), source.getElementType()));
        }
        if (!source.getPage().isTemplate()) {
            throw new InvalidElementHierarchyException(
                    "MAP %s is not on a template page; only template MAPs can be cloned".formatted(source.getCode()));
        }

        Element targetParent = elementService.getActiveOrThrow(targetParentId);
        Element targetPage = PAGE_TYPE.equals(targetParent.getElementType()) ? targetParent : targetParent.getPage();
        Set<String> takenCodes = elementRepository.findByPageIdAndDeletedAtIsNullOrderByDisplayOrderAsc(targetPage.getId())
                .stream()
                .map(Element::getCode)
                .collect(Collectors.toCollection(HashSet::new));

        List<String> renamedCodes = new ArrayList<>();
        Element clonedMap = copySubtree(source, targetParentId, null, takenCodes, renamedCodes);
        return new MapCloneResult(clonedMap, renamedCodes);
    }

    private Element copySubtree(
            Element source, Long newParentId, Integer displayOrder, Set<String> takenCodes, List<String> renamedCodes) {
        String code = uniqueCode(source.getCode(), takenCodes);
        if (!code.equals(source.getCode())) {
            renamedCodes.add(source.getCode() + " → " + code);
        }
        // Through ElementService.create, so every copied node gets the same hierarchy validation,
        // page_id resolution and ordering as a hand-built one.
        Element copy = elementService.create(newParentId, source.getElementType(), code, source.getLabel(), displayOrder);

        for (ElementAttributeValue value : elementAttributeValueRepository.findByElementId(source.getId())) {
            elementAttributeValueRepository.save(new ElementAttributeValue(copy, value.getAttributeDefinition(), value.getValue()));
        }
        for (ElementListOption option : elementListOptionRepository.findByElementIdOrderByDisplayOrderAsc(source.getId())) {
            ElementListOption optionCopy = new ElementListOption(copy, option.getCode(), option.getLabel());
            optionCopy.setDisplayOrder(option.getDisplayOrder());
            optionCopy.setDefault(option.isDefault());
            optionCopy.setActive(option.isActive());
            elementListOptionRepository.save(optionCopy);
        }
        for (Element child : elementRepository.findByParentElementIdAndDeletedAtIsNullOrderByDisplayOrderAsc(source.getId())) {
            copySubtree(child, copy.getId(), child.getDisplayOrder(), takenCodes, renamedCodes);
        }
        return copy;
    }

    private static String uniqueCode(String base, Set<String> takenCodes) {
        String candidate = base;
        for (int n = 2; takenCodes.contains(candidate); n++) {
            candidate = base + "_" + n;
        }
        takenCodes.add(candidate);
        return candidate;
    }
}
