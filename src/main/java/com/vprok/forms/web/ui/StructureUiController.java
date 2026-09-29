package com.vprok.forms.web.ui;

import com.vprok.forms.entity.AttributeDefinition;
import com.vprok.forms.entity.Element;
import com.vprok.forms.entity.ElementAttributeValue;
import com.vprok.forms.entity.GridPosition;
import com.vprok.forms.service.AttributeDefinitionService;
import com.vprok.forms.service.ElementAttributeValueService;
import com.vprok.forms.service.ElementListOptionService;
import com.vprok.forms.service.ElementService;
import com.vprok.forms.service.GridLayoutService;
import com.vprok.forms.web.dto.ElementResponse;
import com.vprok.forms.web.dto.ListOptionResponse;
import com.vprok.forms.web.error.DataIntegrityMessage;
import com.vprok.forms.web.error.InvalidAttributeValueException;
import com.vprok.forms.web.error.InvalidElementHierarchyException;
import com.vprok.forms.web.error.ResourceNotFoundException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Server-rendered structure editor for backend developers. Calls the same services as the REST
 * API (FORMS-5) directly rather than looping back over HTTP.
 *
 * <p>The page editor (FORMS-17, redesigned in FORMS-18) has three columns: the page's top-level
 * sections on the left, the selected section's subtree in the middle, and - when an element is
 * selected - an inspector on the right with its label, attributes and list options. Everything
 * shown is in the URL - {@code /ui/pages/{page}/sections/{section}[/elements/{element}]} - so
 * refresh, back and bookmarks work. Nothing here is type-specific beyond PAGE: which children an
 * element may have and which attributes it carries come from seed data, so a new container type
 * needs no change here.
 *
 * <p>Every POST redirects straight to its final URL: flash attributes (error/notice banners)
 * survive exactly one redirect, so a second hop would silently drop them. Editor forms send what
 * was on screen ({@code section}, {@code selected}) so an action returns there when it still
 * exists - see {@link #backTo}.
 */
@Controller
@RequestMapping("/ui")
public class StructureUiController {

    private static final String PAGE_TYPE = "PAGE";

    private final ElementService elementService;
    private final AttributeDefinitionService attributeDefinitionService;
    private final ElementAttributeValueService elementAttributeValueService;
    private final ElementListOptionService elementListOptionService;
    private final GridLayoutService gridLayoutService;

    public StructureUiController(
            ElementService elementService,
            AttributeDefinitionService attributeDefinitionService,
            ElementAttributeValueService elementAttributeValueService,
            ElementListOptionService elementListOptionService,
            GridLayoutService gridLayoutService) {
        this.elementService = elementService;
        this.attributeDefinitionService = attributeDefinitionService;
        this.elementAttributeValueService = elementAttributeValueService;
        this.elementListOptionService = elementListOptionService;
        this.gridLayoutService = gridLayoutService;
    }

    // --- page list --------------------------------------------------------------------------

    @GetMapping("/pages")
    public String listPages(Model model) {
        model.addAttribute("pages", elementService.getPages().stream().map(ElementResponse::from).toList());
        return "pages-list";
    }

    /** From the page list, or the editor's "New page": either way the new page opens. */
    @PostMapping("/pages")
    public String createPage(
            @RequestParam String code,
            @RequestParam String label,
            @RequestParam(required = false) Long section,
            @RequestParam(required = false) Long selected,
            RedirectAttributes redirectAttributes) {
        String failureUrl = backTo(selected, section, () -> "/ui/pages");
        return tryThenRedirect(redirectAttributes, failureUrl,
                () -> pageViewUrl(elementService.create(null, PAGE_TYPE, code, label, null).getId()));
    }

    /** Target of the left pane's page switcher (a plain GET form, no JavaScript). */
    @GetMapping("/pages/switch")
    public String switchPage(@RequestParam(required = false) Long id) {
        return "redirect:" + (id == null ? "/ui/pages" : "/ui/pages/" + id);
    }

    // --- page editor views ------------------------------------------------------------------

    /** A page with no section in the URL: show its first section, so the URL says what is shown. */
    @GetMapping("/pages/{id}")
    public String pageEditor(@PathVariable Long id, Model model) {
        Element page = elementService.getActiveOrThrow(id);
        if (!PAGE_TYPE.equals(page.getElementType())) {
            return "redirect:" + sectionViewUrl(page);
        }
        String firstSectionUrl = pageViewUrl(id);
        if (!firstSectionUrl.equals("/ui/pages/" + id)) {
            return "redirect:" + firstSectionUrl;
        }
        populateEditorFrame(model, page, null, null);
        return "page-detail";
    }

    @GetMapping("/pages/{pageId}/sections/{sectionId}")
    public String sectionView(@PathVariable Long pageId, @PathVariable Long sectionId, Model model) {
        Element section = elementService.getActiveOrThrow(sectionId);
        if (PAGE_TYPE.equals(section.getElementType())) {
            return "redirect:" + pageViewUrl(sectionId);
        }
        // Anything but "this page's own top-level section" is sent to the URL that really shows it.
        String canonical = sectionViewUrl(section);
        if (!canonical.equals(sectionUrl(pageId, sectionId))) {
            return "redirect:" + canonical;
        }
        populateEditorFrame(model, elementService.getActiveOrThrow(pageId), sectionId, null);
        model.addAttribute("sectionTree", buildTree(section, false));
        return "page-detail";
    }

    /**
     * The section view with one element open in the inspector: its label, attributes, and list
     * options where it has them. The section stays visible beside it.
     */
    @GetMapping("/pages/{pageId}/sections/{sectionId}/elements/{elementId}")
    public String elementDetail(
            @PathVariable Long pageId,
            @PathVariable Long sectionId,
            @PathVariable Long elementId,
            @RequestParam(defaultValue = "false") boolean includeInactive,
            Model model) {
        Element element = elementService.getActiveOrThrow(elementId);
        if (PAGE_TYPE.equals(element.getElementType())) {
            return "redirect:" + pageViewUrl(elementId);
        }
        String canonical = detailViewUrl(element);
        if (!canonical.equals(detailUrl(pageId, sectionId, elementId))) {
            return "redirect:" + canonical;
        }
        populateEditorFrame(model, elementService.getActiveOrThrow(pageId), sectionId, elementId);
        model.addAttribute("sectionTree", buildTree(elementService.getActiveOrThrow(sectionId), false));
        model.addAttribute("detail", ElementResponse.from(element));
        // A child of a grid: the inspector offers its cell, bounded by the grid's width.
        GridPosition layout = element.getGridPosition();
        model.addAttribute("detailLayout", layout);
        if (layout != null) {
            model.addAttribute("detailGridColumns", gridLayoutService.columnCount(element.getParentElement().getId()));
        }
        model.addAttribute("detailTypeLabel", ElementTreeNode.typeLabel(element.getElementType()));
        model.addAttribute("rows", attributeRows(element));
        boolean hasListOptions = "FIELD_LIST".equals(element.getElementType());
        model.addAttribute("hasListOptions", hasListOptions);
        if (hasListOptions) {
            model.addAttribute("includeInactive", includeInactive);
            model.addAttribute("options",
                    elementListOptionService.list(elementId, includeInactive).stream().map(ListOptionResponse::from).toList());
        }
        return "page-detail";
    }

    // --- old detail URLs: kept so existing links and bookmarks still land in the right place ---

    @GetMapping("/elements/{id}/attributes")
    public String attributeEditor(@PathVariable Long id) {
        return "redirect:" + detailViewUrl(elementService.getActiveOrThrow(id));
    }

    @GetMapping("/elements/{id}/list-options")
    public String listOptions(@PathVariable Long id, @RequestParam(defaultValue = "false") boolean includeInactive) {
        String url = detailViewUrl(elementService.getActiveOrThrow(id));
        return "redirect:" + url + (includeInactive ? "?includeInactive=true" : "") + "#list-options";
    }

    // --- structure actions ------------------------------------------------------------------

    /**
     * A new section opens in the middle pane; anything else opens in the inspector, ready for its
     * attributes. A rejected add returns to what was on screen, with the error. {@code row} and
     * {@code column} come from an empty cell of a grid (MATRIX) and put the new child there.
     */
    @PostMapping("/elements/{parentId}/children")
    public String createChild(
            @PathVariable Long parentId,
            @RequestParam String elementType,
            @RequestParam String code,
            @RequestParam String label,
            @RequestParam(required = false) Integer row,
            @RequestParam(required = false) Integer column,
            @RequestParam(required = false) Long section,
            @RequestParam(required = false) Long selected,
            RedirectAttributes redirectAttributes) {
        Element parent = elementService.getActiveOrThrow(parentId);
        String failureUrl = backTo(selected, section, () -> sectionViewUrl(parent));
        GridPosition cell = row != null && column != null ? GridPosition.cell(row, column) : null;
        return tryThenRedirect(redirectAttributes, failureUrl, () -> {
            Element created = elementService.create(parentId, elementType, code, label, null, cell);
            return PAGE_TYPE.equals(parent.getElementType())
                    ? sectionUrl(parentId, created.getId())
                    : detailViewUrl(created) + anchor(created);
        });
    }

    /** Renames any element, the page included (from the sidebar) - everything else from the inspector. */
    @PostMapping("/elements/{id}/edit")
    public String editLabel(
            @PathVariable Long id,
            @RequestParam String label,
            @RequestParam(required = false) Long section,
            @RequestParam(required = false) Long selected,
            RedirectAttributes redirectAttributes) {
        Element element = elementService.getActiveOrThrow(id);
        String redirectUrl = backTo(selected, section, () -> detailViewUrl(element));
        return tryOrRedirect(redirectAttributes, redirectUrl, () -> elementService.updateLabel(id, label));
    }

    @PostMapping("/elements/{id}/delete")
    public String delete(
            @PathVariable Long id,
            @RequestParam(required = false) Long section,
            @RequestParam(required = false) Long selected,
            RedirectAttributes redirectAttributes) {
        Element element = elementService.getActiveOrThrow(id);
        if (PAGE_TYPE.equals(element.getElementType())) {
            return deletePage(element, section, selected, redirectAttributes);
        }
        Long pageId = element.getPage().getId();
        Long topSectionId = elementService.getTopLevelSection(id).getId();
        boolean deletingSection = topSectionId.equals(id);
        String failureUrl = backTo(selected, section, () -> sectionUrl(pageId, topSectionId));
        return tryThenRedirect(redirectAttributes, failureUrl, () -> {
            elementService.softDelete(id);
            // What was on screen may have gone with it (the element in the inspector, or the whole
            // section): then the element's own section, or the page's new first section.
            return backTo(selected, section, () -> deletingSection ? pageViewUrl(pageId) : sectionUrl(pageId, topSectionId));
        });
    }

    @PostMapping("/elements/{id}/move-up")
    public String moveUp(
            @PathVariable Long id,
            @RequestParam(required = false) Long section,
            @RequestParam(required = false) Long selected,
            RedirectAttributes redirectAttributes) {
        return moveWithinSiblings(id, -1, section, selected, redirectAttributes);
    }

    @PostMapping("/elements/{id}/move-down")
    public String moveDown(
            @PathVariable Long id,
            @RequestParam(required = false) Long section,
            @RequestParam(required = false) Long selected,
            RedirectAttributes redirectAttributes) {
        return moveWithinSiblings(id, 1, section, selected, redirectAttributes);
    }

    /**
     * Moves and/or resizes a child of a grid (MATRIX): from the inspector's position form, or from
     * a drag and drop in the middle pane, which posts the same form.
     */
    @PostMapping("/elements/{id}/position")
    public String position(
            @PathVariable Long id,
            @RequestParam(required = false) Integer row,
            @RequestParam(required = false) Integer column,
            @RequestParam(required = false) Integer rowSpan,
            @RequestParam(required = false) Integer columnSpan,
            @RequestParam(required = false) Long section,
            @RequestParam(required = false) Long selected,
            RedirectAttributes redirectAttributes) {
        Element element = elementService.getActiveOrThrow(id);
        String redirectUrl = backTo(selected, section, () -> sectionViewUrl(element)) + anchor(element);
        return tryOrRedirect(redirectAttributes, redirectUrl,
                () -> elementService.place(id, new GridPosition(row, column, rowSpan, columnSpan)));
    }

    // --- detail-pane actions ----------------------------------------------------------------

    @PostMapping("/elements/{id}/attributes")
    public String saveAttributes(@PathVariable Long id, @RequestParam Map<String, String> allParams, RedirectAttributes redirectAttributes) {
        Element element = elementService.getActiveOrThrow(id);
        List<String> applicableCodes = attributeDefinitionService.listApplicableToElementType(element.getElementType()).stream()
                .map(AttributeDefinition::getCode)
                .toList();
        // One call for the whole form: saved atomically, and cross-attribute rules (e.g. a
        // confidential document needing its non-confidential version) are judged on the final
        // state rather than on whichever field happens to be written first. A blank value clears
        // the attribute rather than setting it to the empty string.
        Map<String, String> submitted = new LinkedHashMap<>();
        for (String code : applicableCodes) {
            submitted.put(code, allParams.get(code));
        }
        return tryOrRedirect(redirectAttributes, detailViewUrl(element), () -> elementAttributeValueService.applyValues(id, submitted));
    }

    @PostMapping("/elements/{id}/list-options")
    public String addListOption(
            @PathVariable Long id, @RequestParam String code, @RequestParam String label, RedirectAttributes redirectAttributes) {
        String redirectUrl = detailViewUrl(elementService.getActiveOrThrow(id)) + "#list-options";
        return tryOrRedirect(redirectAttributes, redirectUrl, () -> elementListOptionService.create(id, code, label, null, false));
    }

    @PostMapping("/elements/{id}/list-options/{optionId}/deactivate")
    public String deactivateOption(@PathVariable Long id, @PathVariable Long optionId, RedirectAttributes redirectAttributes) {
        String redirectUrl = detailViewUrl(elementService.getActiveOrThrow(id)) + "#list-options";
        return tryOrRedirect(redirectAttributes, redirectUrl, () -> elementListOptionService.deactivate(id, optionId));
    }

    // --- helpers ----------------------------------------------------------------------------

    /**
     * The page switcher's delete: a soft delete like every other (a permanent delete is
     * deliberately not offered here), landing on the page that takes its place in the list.
     */
    private String deletePage(Element page, Long section, Long selected, RedirectAttributes redirectAttributes) {
        String failureUrl = backTo(selected, section, () -> pageViewUrl(page.getId()));
        return tryThenRedirect(redirectAttributes, failureUrl, () -> {
            int index = elementService.getPages().stream().map(Element::getId).toList().indexOf(page.getId());
            elementService.softDelete(page.getId());
            redirectAttributes.addFlashAttribute("notice", "Deleted page \"%s\" (%s).".formatted(page.getLabel(), page.getCode()));
            List<Element> remaining = elementService.getPages();
            if (remaining.isEmpty()) {
                return "/ui/pages";
            }
            // The next page moves up into the deleted one's place; after the last page, the one before it.
            int next = Math.min(Math.max(index, 0), remaining.size() - 1);
            return pageViewUrl(remaining.get(next).getId());
        });
    }

    private String moveWithinSiblings(Long id, int direction, Long section, Long selected, RedirectAttributes redirectAttributes) {
        Element element = elementService.getActiveOrThrow(id);
        String redirectUrl = backTo(selected, section, () -> sectionViewUrl(element)) + anchor(element);
        Element parent = element.getParentElement();
        if (parent == null) {
            return "redirect:" + redirectUrl;
        }
        Long parentId = parent.getId();
        return tryOrRedirect(redirectAttributes, redirectUrl, () -> {
            List<Long> ids = new ArrayList<>(elementService.getChildren(parentId).stream().map(Element::getId).toList());
            int index = ids.indexOf(id);
            int swapWith = index + direction;
            if (index >= 0 && swapWith >= 0 && swapWith < ids.size()) {
                Collections.swap(ids, index, swapWith);
                elementService.reorderChildren(parentId, ids);
            }
        });
    }

    /**
     * Where an editor action returns to: what was on screen - the element open in the inspector,
     * else the selected section - if it still exists, otherwise {@code fallback}. Always a
     * canonical URL, so there is never a second redirect to lose the banner on.
     */
    private String backTo(Long selected, Long section, Supplier<String> fallback) {
        Optional<Element> inInspector = elementService.findActive(selected).filter(e -> !PAGE_TYPE.equals(e.getElementType()));
        if (inInspector.isPresent()) {
            return detailViewUrl(inInspector.get());
        }
        return elementService.findActive(section)
                .filter(e -> !PAGE_TYPE.equals(e.getElementType()))
                .map(this::sectionViewUrl)
                .orElseGet(fallback);
    }

    /** Scrolls the section view back to the element; a top-level section is the view itself. */
    private static String anchor(Element element) {
        Element parent = element.getParentElement();
        // Ids only: parent and page are lazy proxies, and open-in-view is off.
        boolean topLevel = parent == null || parent.getId().equals(element.getPage().getId());
        return topLevel ? "" : "#el-" + element.getId();
    }

    /** Everything the sidebar and inspector need, whatever the middle pane shows. */
    private void populateEditorFrame(Model model, Element page, Long selectedSectionId, Long selectedElementId) {
        List<ElementResponse> switcherPages = new ArrayList<>(elementService.getPages().stream().map(ElementResponse::from).toList());
        if (switcherPages.stream().noneMatch(p -> p.id().equals(page.getId()))) {
            switcherPages.add(0, ElementResponse.from(page));
        }
        model.addAttribute("page", ElementResponse.from(page));
        model.addAttribute("pageElementCount", elementService.countElementsOnPage(page.getId()));
        model.addAttribute("switcherPages", switcherPages);
        model.addAttribute("sections", elementService.getChildren(page.getId()).stream().map(ElementResponse::from).toList());
        model.addAttribute("sectionAddGroups", ElementTreeNode.addGroups(elementService.getAllowedChildTypes(PAGE_TYPE)));
        model.addAttribute("selectedSectionId", selectedSectionId);
        model.addAttribute("selectedElementId", selectedElementId);
    }

    private List<AttributeValueRow> attributeRows(Element element) {
        Map<String, String> currentValues = elementAttributeValueService.list(element.getId()).stream()
                .collect(Collectors.toMap(v -> v.getAttributeDefinition().getCode(), ElementAttributeValue::getValue));
        return attributeDefinitionService.listApplicableToElementType(element.getElementType()).stream()
                .map(def -> new AttributeValueRow(
                        def.getCode(), def.getName(), def.getDescription(), def.getDataType(), currentValues.getOrDefault(def.getCode(), "")))
                .toList();
    }

    private ElementTreeNode buildTree(Element element, boolean inGrid) {
        boolean isGrid = gridLayoutService.isGridType(element.getElementType());
        List<Element> childElements = elementService.getChildren(element.getId());
        List<ElementTreeNode> children = childElements.stream().map(child -> buildTree(child, isGrid)).toList();
        List<String> allowedChildTypes = elementService.getAllowedChildTypes(element.getElementType());
        GridView grid = isGrid
                ? GridView.of(gridLayoutService.columnCount(element.getId()), childElements.stream().map(Element::getGridPosition).toList())
                : null;
        return new ElementTreeNode(ElementResponse.from(element), children, allowedChildTypes, inGrid, grid);
    }

    private static String sectionUrl(Long pageId, Long sectionId) {
        return "/ui/pages/" + pageId + "/sections/" + sectionId;
    }

    private static String detailUrl(Long pageId, Long sectionId, Long elementId) {
        return sectionUrl(pageId, sectionId) + "/elements/" + elementId;
    }

    /** A page's default view: its first section, or the bare page when it has none yet. */
    private String pageViewUrl(Long pageId) {
        List<Element> sections = elementService.getChildren(pageId);
        return sections.isEmpty() ? "/ui/pages/" + pageId : sectionUrl(pageId, sections.get(0).getId());
    }

    /** Where the editor shows this element: its page, with its top-level section selected. */
    private String sectionViewUrl(Element element) {
        if (PAGE_TYPE.equals(element.getElementType())) {
            return pageViewUrl(element.getId());
        }
        return sectionUrl(element.getPage().getId(), elementService.getTopLevelSection(element.getId()).getId());
    }

    private String detailViewUrl(Element element) {
        if (PAGE_TYPE.equals(element.getElementType())) {
            return pageViewUrl(element.getId());
        }
        Long sectionId = elementService.getTopLevelSection(element.getId()).getId();
        return detailUrl(element.getPage().getId(), sectionId, element.getId());
    }

    private String tryOrRedirect(RedirectAttributes redirectAttributes, String redirectUrl, Runnable action) {
        return tryThenRedirect(redirectAttributes, redirectUrl, () -> {
            action.run();
            return redirectUrl;
        });
    }

    /** Runs the action; on success redirects where it says, on a user-facing failure to {@code failureUrl} with the error. */
    private String tryThenRedirect(RedirectAttributes redirectAttributes, String failureUrl, Supplier<String> action) {
        try {
            return "redirect:" + action.get();
        } catch (InvalidElementHierarchyException | InvalidAttributeValueException | ResourceNotFoundException ex) {
            redirectAttributes.addFlashAttribute("error", ex.getMessage());
        } catch (DataIntegrityViolationException ex) {
            redirectAttributes.addFlashAttribute("error", DataIntegrityMessage.from(ex).message());
        }
        return "redirect:" + failureUrl;
    }
}
