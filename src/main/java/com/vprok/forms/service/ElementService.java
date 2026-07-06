package com.vprok.forms.service;

import com.vprok.forms.entity.Element;
import com.vprok.forms.repository.ElementRepository;
import com.vprok.forms.repository.ElementTypeRuleRepository;
import com.vprok.forms.web.error.InvalidElementHierarchyException;
import com.vprok.forms.web.error.ResourceNotFoundException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ElementService {

    private static final String PAGE_TYPE = "PAGE";

    private final ElementRepository elementRepository;
    private final ElementTypeRuleRepository elementTypeRuleRepository;

    public ElementService(ElementRepository elementRepository, ElementTypeRuleRepository elementTypeRuleRepository) {
        this.elementRepository = elementRepository;
        this.elementTypeRuleRepository = elementTypeRuleRepository;
    }

    /**
     * The only way this service reads a single element by id. Always filters out soft-deleted
     * rows, so a deleted element can never surface through a client-facing endpoint.
     */
    public Element getActiveOrThrow(Long id) {
        return elementRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResourceNotFoundException("Element " + id + " not found"));
    }

    public List<Element> getPages() {
        return elementRepository.findByPageIdIsNullAndDeletedAtIsNullOrderByCodeAsc();
    }

    public List<Element> getChildren(Long parentId) {
        getActiveOrThrow(parentId);
        return elementRepository.findByParentElementIdAndDeletedAtIsNullOrderByDisplayOrderAsc(parentId);
    }

    @Transactional
    public Element create(Long parentElementId, String elementType, String code, String label, Integer displayOrder) {
        Element parent = null;
        Element page;
        if (parentElementId == null) {
            if (!PAGE_TYPE.equals(elementType)) {
                throw new InvalidElementHierarchyException("Only a PAGE element may be created without a parentElementId");
            }
            page = null;
        } else {
            parent = getActiveOrThrow(parentElementId);
            requireCompatible(parent.getElementType(), elementType);
            page = PAGE_TYPE.equals(parent.getElementType()) ? parent : parent.getPage();
        }

        Element element = new Element(parent, page, elementType, code, label);
        element.setDisplayOrder(displayOrder != null ? displayOrder : nextDisplayOrder(parentElementId));
        return elementRepository.save(element);
    }

    @Transactional
    public Element updateLabel(Long id, String label) {
        Element element = getActiveOrThrow(id);
        element.setLabel(label);
        return elementRepository.save(element);
    }

    @Transactional
    public Element move(Long elementId, Long newParentElementId) {
        Element element = getActiveOrThrow(elementId);
        if (PAGE_TYPE.equals(element.getElementType())) {
            throw new InvalidElementHierarchyException("PAGE elements have no parent and cannot be moved");
        }
        Element newParent = getActiveOrThrow(newParentElementId);
        requireCompatible(newParent.getElementType(), element.getElementType());
        if (isSameOrAncestorOf(element, newParent)) {
            throw new InvalidElementHierarchyException("Cannot move an element into its own subtree");
        }

        Element newPage = PAGE_TYPE.equals(newParent.getElementType()) ? newParent : newParent.getPage();
        element.setParentElement(newParent);

        List<Element> subtree = collectSubtree(element);
        for (Element e : subtree) {
            e.setPage(newPage);
        }
        elementRepository.saveAll(subtree);
        return element;
    }

    @Transactional
    public List<Element> reorderChildren(Long parentId, List<Long> orderedElementIds) {
        getActiveOrThrow(parentId);
        List<Element> children = elementRepository.findByParentElementIdAndDeletedAtIsNullOrderByDisplayOrderAsc(parentId);
        Set<Long> currentIds = children.stream().map(Element::getId).collect(Collectors.toSet());
        Set<Long> requestedIds = new HashSet<>(orderedElementIds);
        if (orderedElementIds.size() != children.size() || !currentIds.equals(requestedIds)) {
            throw new InvalidElementHierarchyException(
                    "orderedElementIds must contain exactly the current children of the parent, each once");
        }
        Map<Long, Element> byId = children.stream().collect(Collectors.toMap(Element::getId, e -> e));
        for (int i = 0; i < orderedElementIds.size(); i++) {
            byId.get(orderedElementIds.get(i)).setDisplayOrder(i);
        }
        return elementRepository.saveAll(children);
    }

    @Transactional
    public void softDelete(Long id) {
        Element root = getActiveOrThrow(id);
        Instant now = Instant.now();
        List<Element> subtree = collectSubtree(root);
        for (Element e : subtree) {
            e.setDeletedAt(now);
        }
        elementRepository.saveAll(subtree);
    }

    private void requireCompatible(String parentType, String childType) {
        if (!elementTypeRuleRepository.existsByIdParentTypeAndIdChildType(parentType, childType)) {
            throw new InvalidElementHierarchyException("%s may not contain %s".formatted(parentType, childType));
        }
    }

    /** True if newParent is elementItself or one of its descendants (would create a cycle). */
    private boolean isSameOrAncestorOf(Element elementItself, Element newParent) {
        Element current = newParent;
        while (current != null) {
            if (current.getId().equals(elementItself.getId())) {
                return true;
            }
            current = current.getParentElement();
        }
        return false;
    }

    private List<Element> collectSubtree(Element root) {
        List<Element> all = new ArrayList<>();
        Deque<Element> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            Element current = queue.poll();
            all.add(current);
            queue.addAll(elementRepository.findByParentElementIdAndDeletedAtIsNullOrderByDisplayOrderAsc(current.getId()));
        }
        return all;
    }

    private int nextDisplayOrder(Long parentElementId) {
        if (parentElementId == null) {
            return 0;
        }
        List<Element> siblings = elementRepository.findByParentElementIdAndDeletedAtIsNullOrderByDisplayOrderAsc(parentElementId);
        return siblings.isEmpty() ? 0 : siblings.get(siblings.size() - 1).getDisplayOrder() + 1;
    }
}
