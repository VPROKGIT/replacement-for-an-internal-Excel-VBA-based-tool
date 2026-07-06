package com.vprok.forms.web;

import com.vprok.forms.entity.ElementAttributeValue;
import com.vprok.forms.service.ElementAttributeValueService;
import com.vprok.forms.web.dto.AttributeValueRequest;
import com.vprok.forms.web.dto.AttributeValueResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/elements/{elementId}/attribute-values")
public class ElementAttributeValueController {

    private final ElementAttributeValueService elementAttributeValueService;

    public ElementAttributeValueController(ElementAttributeValueService elementAttributeValueService) {
        this.elementAttributeValueService = elementAttributeValueService;
    }

    @GetMapping
    public List<AttributeValueResponse> list(@PathVariable Long elementId) {
        return elementAttributeValueService.list(elementId).stream()
                .map(v -> new AttributeValueResponse(elementId, v.getAttributeDefinition().getCode(), v.getValue()))
                .toList();
    }

    @PutMapping("/{attributeCode}")
    public AttributeValueResponse set(
            @PathVariable Long elementId, @PathVariable String attributeCode, @Valid @RequestBody AttributeValueRequest request) {
        ElementAttributeValue saved = elementAttributeValueService.setValue(elementId, attributeCode, request.value());
        return new AttributeValueResponse(elementId, attributeCode, saved.getValue());
    }

    @DeleteMapping("/{attributeCode}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long elementId, @PathVariable String attributeCode) {
        elementAttributeValueService.deleteValue(elementId, attributeCode);
    }
}
