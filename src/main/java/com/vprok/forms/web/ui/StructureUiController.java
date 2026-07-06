package com.vprok.forms.web.ui;

import com.vprok.forms.entity.AttributeDefinition;
import com.vprok.forms.entity.Element;
import com.vprok.forms.entity.ElementAttributeValue;
import com.vprok.forms.service.AttributeDefinitionService;
import com.vprok.forms.service.ElementAttributeValueService;
import com.vprok.forms.service.ElementListOptionService;
import com.vprok.forms.service.ElementService;
import com.vprok.forms.web.dto.ElementResponse;
import com.vprok.forms.web.dto.ListOptionResponse;
import com.vprok.forms.web.error.DataIntegrityMessage;
import com.vprok.forms.web.error.InvalidAttributeValueException;
import com.vprok.forms.web.error.InvalidElementHierarchyException;
import com.vprok.forms.web.error.ResourceNotFoundException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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
 * Server-rendered tree editor for backend developers building form structures. Calls the same
 * services as the REST API (FORMS-5) directly rather than looping back over HTTP.
 */
@Controller
@RequestMapping("/ui")
public class StructureUiController {

    private final ElementService elementService;
    private final AttributeDefinitionService attributeDefinitionService;
    private final ElementAttributeValueService elementAttributeValueService;
    private final ElementListOptionService elementListOptionService;

    public StructureUiController(
            ElementService elementService,
            AttributeDefinitionService attributeDefinitionService,
            ElementAttributeValueService elementAttributeValueService,
            ElementListOptionService elementListOptionService) {
        this.elementService = elementService;
        this.attributeDefinitionService = attributeDefinitionService;
        this.elementAttributeValueService = elementAttributeValueService;
        this.elementListOptionService = elementListOptionService;
    }

    @GetMapping("/pages")
    public String listPages(Model model) {
        model.addAttribute("pages", elementService.getPages().stream().map(ElementResponse::from).toList());
        return "pages-list";
    }

    @PostMapping("/pages")
    public String createPage(@RequestParam String code, @RequestParam String label, RedirectAttributes redirectAttributes) {
        return tryOrRedirect(redirectAttributes, "/ui/pages", () -> elementService.create(null, "PAGE", code, label, null));
    }

    @GetMapping("/pages/{id}")
    public String pageDetail(@PathVariable Long id, Model model) {
        Element page = elementService.getActiveOrThrow(id);
        model.addAttribute("page", ElementResponse.from(page));
        model.addAttribute("tree", buildTree(page));
        return "page-detail";
    }

    @PostMapping("/elements/{parentId}/children")
    public String createChild(
            @PathVariable Long parentId,
            @RequestParam String elementType,
            @RequestParam String code,
            @RequestParam String label,
            RedirectAttributes redirectAttributes) {
        Element parent = elementService.getActiveOrThrow(parentId);
        String redirectUrl = "/ui/pages/" + resolvePageId(parent);
        return tryOrRedirect(redirectAttributes, redirectUrl, () -> elementService.create(parentId, elementType, code, label, null));
    }

    @PostMapping("/elements/{id}/edit")
    public String editLabel(@PathVariable Long id, @RequestParam String label, RedirectAttributes redirectAttributes) {
        Element element = elementService.getActiveOrThrow(id);
        String redirectUrl = "/ui/pages/" + resolvePageId(element);
        return tryOrRedirect(redirectAttributes, redirectUrl, () -> elementService.updateLabel(id, label));
    }

    @PostMapping("/elements/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        Element element = elementService.getActiveOrThrow(id);
        String redirectUrl = "PAGE".equals(element.getElementType()) ? "/ui/pages" : "/ui/pages/" + resolvePageId(element);
        return tryOrRedirect(redirectAttributes, redirectUrl, () -> elementService.softDelete(id));
    }

    @PostMapping("/elements/{id}/move-up")
    public String moveUp(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        return moveWithinSiblings(id, -1, redirectAttributes);
    }

    @PostMapping("/elements/{id}/move-down")
    public String moveDown(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        return moveWithinSiblings(id, 1, redirectAttributes);
    }

    @GetMapping("/elements/{id}/attributes")
    public String attributeEditor(@PathVariable Long id, Model model) {
        Element element = elementService.getActiveOrThrow(id);
        Map<String, String> currentValues = elementAttributeValueService.list(id).stream()
                .collect(Collectors.toMap(v -> v.getAttributeDefinition().getCode(), ElementAttributeValue::getValue));
        List<AttributeValueRow> rows = attributeDefinitionService.listApplicableToElementType(element.getElementType()).stream()
                .map(def -> new AttributeValueRow(def.getCode(), def.getName(), def.getDataType(), currentValues.getOrDefault(def.getCode(), "")))
                .toList();
        model.addAttribute("element", ElementResponse.from(element));
        model.addAttribute("pageId", resolvePageId(element));
        model.addAttribute("rows", rows);
        return "attribute-editor";
    }

    @PostMapping("/elements/{id}/attributes")
    public String saveAttributes(@PathVariable Long id, @RequestParam Map<String, String> allParams, RedirectAttributes redirectAttributes) {
        Element element = elementService.getActiveOrThrow(id);
        List<String> applicableCodes = attributeDefinitionService.listApplicableToElementType(element.getElementType()).stream()
                .map(AttributeDefinition::getCode)
                .toList();
        String redirectUrl = "/ui/elements/" + id + "/attributes";
        return tryOrRedirect(redirectAttributes, redirectUrl, () -> {
            // A blank submitted value clears any existing value rather than being treated as
            // "explicitly set to the empty string" (which the API otherwise allows for STRING
            // attributes) - simpler and more intuitive for a form-based editor.
            for (String code : applicableCodes) {
                String value = allParams.get(code);
                if (value == null || value.isBlank()) {
                    elementAttributeValueService.deleteValue(id, code);
                } else {
                    elementAttributeValueService.setValue(id, code, value);
                }
            }
        });
    }

    @GetMapping("/elements/{id}/list-options")
    public String listOptions(
            @PathVariable Long id, @RequestParam(defaultValue = "false") boolean includeInactive, Model model) {
        Element element = elementService.getActiveOrThrow(id);
        model.addAttribute("element", ElementResponse.from(element));
        model.addAttribute("pageId", resolvePageId(element));
        model.addAttribute("includeInactive", includeInactive);
        model.addAttribute("options", elementListOptionService.list(id, includeInactive).stream().map(ListOptionResponse::from).toList());
        return "list-options";
    }

    @PostMapping("/elements/{id}/list-options")
    public String addListOption(
            @PathVariable Long id, @RequestParam String code, @RequestParam String label, RedirectAttributes redirectAttributes) {
        String redirectUrl = "/ui/elements/" + id + "/list-options";
        return tryOrRedirect(redirectAttributes, redirectUrl, () -> elementListOptionService.create(id, code, label, null, false));
    }

    @PostMapping("/elements/{id}/list-options/{optionId}/deactivate")
    public String deactivateOption(@PathVariable Long id, @PathVariable Long optionId, RedirectAttributes redirectAttributes) {
        String redirectUrl = "/ui/elements/" + id + "/list-options";
        return tryOrRedirect(redirectAttributes, redirectUrl, () -> elementListOptionService.deactivate(id, optionId));
    }

    private String moveWithinSiblings(Long id, int direction, RedirectAttributes redirectAttributes) {
        Element element = elementService.getActiveOrThrow(id);
        String redirectUrl = "/ui/pages/" + resolvePageId(element);
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

    private ElementTreeNode buildTree(Element element) {
        List<ElementTreeNode> children = elementService.getChildren(element.getId()).stream().map(this::buildTree).toList();
        List<String> allowedChildTypes = elementService.getAllowedChildTypes(element.getElementType());
        return new ElementTreeNode(ElementResponse.from(element), children, allowedChildTypes);
    }

    private Long resolvePageId(Element element) {
        return "PAGE".equals(element.getElementType()) ? element.getId() : element.getPage().getId();
    }

    private String tryOrRedirect(RedirectAttributes redirectAttributes, String redirectUrl, Runnable action) {
        try {
            action.run();
        } catch (InvalidElementHierarchyException | InvalidAttributeValueException | ResourceNotFoundException ex) {
            redirectAttributes.addFlashAttribute("error", ex.getMessage());
        } catch (DataIntegrityViolationException ex) {
            redirectAttributes.addFlashAttribute("error", DataIntegrityMessage.from(ex).message());
        }
        return "redirect:" + redirectUrl;
    }
}
