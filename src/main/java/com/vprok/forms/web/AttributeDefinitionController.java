package com.vprok.forms.web;

import com.vprok.forms.service.AttributeDefinitionService;
import com.vprok.forms.web.dto.AttributeDefinitionCreateRequest;
import com.vprok.forms.web.dto.AttributeDefinitionResponse;
import com.vprok.forms.web.dto.AttributeDefinitionUpdateRequest;
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
@RequestMapping("/api/attribute-definitions")
public class AttributeDefinitionController {

    private final AttributeDefinitionService attributeDefinitionService;

    public AttributeDefinitionController(AttributeDefinitionService attributeDefinitionService) {
        this.attributeDefinitionService = attributeDefinitionService;
    }

    @GetMapping
    public List<AttributeDefinitionResponse> list(@RequestParam(required = false) String applicableToElementType) {
        List<com.vprok.forms.entity.AttributeDefinition> definitions = applicableToElementType != null
                ? attributeDefinitionService.listApplicableToElementType(applicableToElementType)
                : attributeDefinitionService.list();
        return definitions.stream().map(AttributeDefinitionResponse::from).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AttributeDefinitionResponse create(@Valid @RequestBody AttributeDefinitionCreateRequest request) {
        return AttributeDefinitionResponse.from(attributeDefinitionService.create(
                request.code(), request.name(), request.dataType(), request.description()));
    }

    @GetMapping("/{id}")
    public AttributeDefinitionResponse getById(@PathVariable Long id) {
        return AttributeDefinitionResponse.from(attributeDefinitionService.getByIdOrThrow(id));
    }

    @PutMapping("/{id}")
    public AttributeDefinitionResponse update(@PathVariable Long id, @Valid @RequestBody AttributeDefinitionUpdateRequest request) {
        return AttributeDefinitionResponse.from(attributeDefinitionService.update(id, request.name(), request.description()));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        attributeDefinitionService.delete(id);
    }
}
