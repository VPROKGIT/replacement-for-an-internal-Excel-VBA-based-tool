package com.vprok.forms.web;

import com.vprok.forms.service.ElementListOptionService;
import com.vprok.forms.web.dto.ListOptionCreateRequest;
import com.vprok.forms.web.dto.ListOptionResponse;
import com.vprok.forms.web.dto.ListOptionUpdateRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/elements/{elementId}/list-options")
public class ElementListOptionController {

    private final ElementListOptionService elementListOptionService;

    public ElementListOptionController(ElementListOptionService elementListOptionService) {
        this.elementListOptionService = elementListOptionService;
    }

    @GetMapping
    public List<ListOptionResponse> list(
            @PathVariable Long elementId, @RequestParam(defaultValue = "false") boolean includeInactive) {
        return elementListOptionService.list(elementId, includeInactive).stream().map(ListOptionResponse::from).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ListOptionResponse create(@PathVariable Long elementId, @Valid @RequestBody ListOptionCreateRequest request) {
        return ListOptionResponse.from(elementListOptionService.create(
                elementId, request.code(), request.label(), request.displayOrder(), Boolean.TRUE.equals(request.isDefault())));
    }

    @PutMapping("/{optionId}")
    public ListOptionResponse update(
            @PathVariable Long elementId, @PathVariable Long optionId, @RequestBody ListOptionUpdateRequest request) {
        return ListOptionResponse.from(elementListOptionService.update(
                elementId, optionId, request.label(), request.displayOrder(), request.isDefault()));
    }

    @DeleteMapping("/{optionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deactivate(@PathVariable Long elementId, @PathVariable Long optionId) {
        elementListOptionService.deactivate(elementId, optionId);
    }
}
