package com.vprok.forms.service;

import com.vprok.forms.entity.Element;
import com.vprok.forms.entity.ElementListOption;
import com.vprok.forms.repository.ElementListOptionRepository;
import com.vprok.forms.repository.ElementRepository;
import com.vprok.forms.web.error.InvalidElementHierarchyException;
import com.vprok.forms.web.error.ResourceNotFoundException;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ElementListOptionService {

    private final ElementRepository elementRepository;
    private final ElementListOptionRepository elementListOptionRepository;

    public ElementListOptionService(ElementRepository elementRepository, ElementListOptionRepository elementListOptionRepository) {
        this.elementRepository = elementRepository;
        this.elementListOptionRepository = elementListOptionRepository;
    }

    public List<ElementListOption> list(Long elementId, boolean includeInactive) {
        getActiveElementOrThrow(elementId);
        return includeInactive
                ? elementListOptionRepository.findByElementIdOrderByDisplayOrderAsc(elementId)
                : elementListOptionRepository.findByElementIdAndActiveTrueOrderByDisplayOrderAsc(elementId);
    }

    @Transactional
    public ElementListOption create(Long elementId, String code, String label, Integer displayOrder, boolean isDefault) {
        Element element = getActiveElementOrThrow(elementId);
        if (!"FIELD_LIST".equals(element.getElementType())) {
            throw new InvalidElementHierarchyException("List options can only be added to a FIELD_LIST element");
        }
        ElementListOption option = new ElementListOption(element, code, label);
        option.setDisplayOrder(displayOrder != null ? displayOrder : nextDisplayOrder(elementId));
        option.setDefault(isDefault);
        return elementListOptionRepository.save(option);
    }

    @Transactional
    public ElementListOption update(Long elementId, Long optionId, String label, Integer displayOrder, Boolean isDefault) {
        ElementListOption option = getOptionOrThrow(elementId, optionId);
        if (label != null) {
            option.setLabel(label);
        }
        if (displayOrder != null) {
            option.setDisplayOrder(displayOrder);
        }
        if (isDefault != null) {
            option.setDefault(isDefault);
        }
        return elementListOptionRepository.save(option);
    }

    /** Deactivates rather than deletes the row, so historical answers referencing this option stay valid. */
    @Transactional
    public void deactivate(Long elementId, Long optionId) {
        ElementListOption option = getOptionOrThrow(elementId, optionId);
        option.setActive(false);
        elementListOptionRepository.save(option);
    }

    private ElementListOption getOptionOrThrow(Long elementId, Long optionId) {
        ElementListOption option = elementListOptionRepository.findById(optionId)
                .orElseThrow(() -> new ResourceNotFoundException("List option " + optionId + " not found"));
        if (!option.getElement().getId().equals(elementId)) {
            throw new ResourceNotFoundException("List option " + optionId + " not found for element " + elementId);
        }
        return option;
    }

    private int nextDisplayOrder(Long elementId) {
        List<ElementListOption> existing = elementListOptionRepository.findByElementIdOrderByDisplayOrderAsc(elementId);
        return existing.isEmpty() ? 0 : existing.get(existing.size() - 1).getDisplayOrder() + 1;
    }

    private Element getActiveElementOrThrow(Long elementId) {
        return elementRepository.findByIdAndDeletedAtIsNull(elementId)
                .orElseThrow(() -> new ResourceNotFoundException("Element " + elementId + " not found"));
    }
}
