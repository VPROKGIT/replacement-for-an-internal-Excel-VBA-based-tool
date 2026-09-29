package com.vprok.forms.service;

import com.vprok.forms.entity.Element;
import com.vprok.forms.entity.ElementTypeRule;
import com.vprok.forms.entity.GridPosition;
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
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ElementService {

    private static final String PAGE_TYPE = "PAGE";

    private final ElementRepository elementRepository;
    private final ElementTypeRuleRepository elementTypeRuleRepository;
    private final GridLayoutService gridLayoutService;

    public ElementService(
            ElementRepository elementRepository,
            ElementTypeRuleRepository elementTypeRuleRepository,
            GridLayoutService gridLayoutService) {
        this.elementRepository = elementRepository;
        this.elementTypeRuleRepository = elementTypeRuleRepository;
        this.gridLayoutService = gridLayoutService;
    }

    /**
     * The only way this service reads a single element by id. Always filters out soft-deleted
     * rows, so a deleted element can never surface through a client-facing endpoint.
     */
    public Element getActiveOrThrow(Long id) {
        return findActive(id).orElseThrow(() -> new ResourceNotFoundException("Element " + id + " not found"));
    }

    /** The same soft-delete-filtered read, for callers where "gone" is an expected answer. */
    public Optional<Element> findActive(Long id) {
        return id == null ? Optional.empty() : elementRepository.findByIdAndDeletedAtIsNull(id);
    }

    /** Real form pages only; template pages (currently unused, see MapTemplateService) are left out. */
    public List<Element> getPages() {
        return elementRepository.findByPageIdIsNullAndTemplateFalseAndDeletedAtIsNullOrderByCodeAsc();
    }

    /** How many elements a page holds at any depth, not counting the page itself. */
    public long countElementsOnPage(Long pageId) {
        return elementRepository.countByPageIdAndDeletedAtIsNull(pageId);
    }

    public List<Element> getChildren(Long parentId) {
        getActiveOrThrow(parentId);
        return elementRepository.findByParentElementIdAndDeletedAtIsNullOrderByDisplayOrderAsc(parentId);
    }

    /**
     * The element's top-level ancestor - the direct child of its PAGE, which the page editor
     * selects as "the section" - or the element itself if it already is one. Walks the lazy parent
     * chain inside this transaction, which callers outside one (the UI) cannot do.
     */
    @Transactional(readOnly = true)
    public Element getTopLevelSection(Long elementId) {
        Element current = getActiveOrThrow(elementId);
        if (PAGE_TYPE.equals(current.getElementType())) {
            throw new InvalidElementHierarchyException("A PAGE is not inside a section");
        }
        while (!PAGE_TYPE.equals(current.getParentElement().getElementType())) {
            current = current.getParentElement();
        }
        return current;
    }

    /** Data-driven from element_type_rule, so "add child" UI never hardcodes the allowed types. */
    public List<String> getAllowedChildTypes(String parentType) {
        return elementTypeRuleRepository.findByIdParentType(parentType).stream()
                .map(ElementTypeRule::getChildType)
                .toList();
    }

    @Transactional
    public Element create(Long parentElementId, String elementType, String code, String label, Integer displayOrder) {
        return create(parentElementId, elementType, code, label, displayOrder, null);
    }

    /**
     * Creates an element. Under a grid (MATRIX) it gets {@code position}, or the first free cell
     * when that is null, and the grid keeps its children in reading order - so displayOrder is
     * ignored there. Anywhere else a position is an error.
     */
    @Transactional
    public Element create(
            Long parentElementId, String elementType, String code, String label, Integer displayOrder, GridPosition position) {
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

        boolean inGrid = parent != null && gridLayoutService.isGridType(parent.getElementType());
        if (position != null && !inGrid) {
            throw new InvalidElementHierarchyException("A position only applies to an element inside a grid (MATRIX)");
        }

        Element element = new Element(parent, page, elementType, code, label);
        element.setDisplayOrder(displayOrder != null ? displayOrder : nextDisplayOrder(parentElementId));
        if (inGrid) {
            element.setGridPosition(gridLayoutService.place(parent, position, null));
        }
        Element saved = elementRepository.save(element);
        if (inGrid) {
            gridLayoutService.resequence(parent.getId());
        }
        if (gridLayoutService.isGridType(elementType)) {
            gridLayoutService.initialise(saved);
        }
        return saved;
    }

    /** Moves a grid child to another cell (and/or resizes it); the grid's rules decide whether it fits. */
    @Transactional
    public Element place(Long elementId, GridPosition position) {
        Element element = getActiveOrThrow(elementId);
        Element parent = element.getParentElement();
        if (parent == null || !gridLayoutService.isGridType(parent.getElementType())) {
            throw new InvalidElementHierarchyException("Only an element inside a grid (MATRIX) has a position");
        }
        element.setGridPosition(gridLayoutService.place(parent, position, element.getId()));
        elementRepository.save(element);
        gridLayoutService.resequence(parent.getId());
        return element;
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
        boolean sameParent = element.getParentElement() != null && element.getParentElement().getId().equals(newParent.getId());
        element.setParentElement(newParent);
        // Into a grid: the first free cell. Out of one: no position. Within the same grid: unchanged.
        boolean intoGrid = gridLayoutService.isGridType(newParent.getElementType());
        if (!intoGrid) {
            element.setGridPosition(null);
        } else if (!sameParent) {
            element.setGridPosition(gridLayoutService.place(newParent, null, element.getId()));
        }

        List<Element> subtree = collectSubtree(element);
        for (Element e : subtree) {
            e.setPage(newPage);
        }
        elementRepository.saveAll(subtree);
        if (intoGrid) {
            gridLayoutService.resequence(newParent.getId());
        }
        return element;
    }

    @Transactional
    public List<Element> reorderChildren(Long parentId, List<Long> orderedElementIds) {
        Element parent = getActiveOrThrow(parentId);
        if (gridLayoutService.isGridType(parent.getElementType())) {
            throw new InvalidElementHierarchyException(
                    "The children of a %s are ordered by their position in the grid: move them to another cell instead"
                            .formatted(parent.getElementType()));
        }
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
