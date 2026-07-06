package com.vprok.forms.web;

import com.vprok.forms.service.ElementService;
import com.vprok.forms.web.dto.ElementCreateRequest;
import com.vprok.forms.web.dto.ElementMoveRequest;
import com.vprok.forms.web.dto.ElementResponse;
import com.vprok.forms.web.dto.ElementUpdateRequest;
import com.vprok.forms.web.dto.ReorderRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ElementController {

    private final ElementService elementService;

    public ElementController(ElementService elementService) {
        this.elementService = elementService;
    }

    @GetMapping("/pages")
    public List<ElementResponse> getPages() {
        return elementService.getPages().stream().map(ElementResponse::from).toList();
    }

    @PostMapping("/elements")
    @ResponseStatus(HttpStatus.CREATED)
    public ElementResponse create(@Valid @RequestBody ElementCreateRequest request) {
        return ElementResponse.from(elementService.create(
                request.parentElementId(), request.elementType(), request.code(), request.label(), request.displayOrder()));
    }

    @GetMapping("/elements/{id}")
    public ElementResponse getById(@PathVariable Long id) {
        return ElementResponse.from(elementService.getActiveOrThrow(id));
    }

    @GetMapping("/elements/{id}/children")
    public List<ElementResponse> getChildren(@PathVariable Long id) {
        return elementService.getChildren(id).stream().map(ElementResponse::from).toList();
    }

    @PutMapping("/elements/{id}")
    public ElementResponse update(@PathVariable Long id, @Valid @RequestBody ElementUpdateRequest request) {
        return ElementResponse.from(elementService.updateLabel(id, request.label()));
    }

    @PatchMapping("/elements/{id}/move")
    public ElementResponse move(@PathVariable Long id, @Valid @RequestBody ElementMoveRequest request) {
        return ElementResponse.from(elementService.move(id, request.newParentElementId()));
    }

    @PatchMapping("/elements/{id}/children/reorder")
    public List<ElementResponse> reorderChildren(@PathVariable Long id, @Valid @RequestBody ReorderRequest request) {
        return elementService.reorderChildren(id, request.orderedElementIds()).stream().map(ElementResponse::from).toList();
    }

    @DeleteMapping("/elements/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        elementService.softDelete(id);
    }
}
