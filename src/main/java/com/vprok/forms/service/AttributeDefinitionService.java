package com.vprok.forms.service;

import com.vprok.forms.entity.AttributeDataType;
import com.vprok.forms.entity.AttributeDefinition;
import com.vprok.forms.repository.AttributeApplicabilityRepository;
import com.vprok.forms.repository.AttributeDefinitionRepository;
import com.vprok.forms.web.error.ResourceNotFoundException;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AttributeDefinitionService {

    private final AttributeDefinitionRepository attributeDefinitionRepository;
    private final AttributeApplicabilityRepository attributeApplicabilityRepository;

    public AttributeDefinitionService(
            AttributeDefinitionRepository attributeDefinitionRepository,
            AttributeApplicabilityRepository attributeApplicabilityRepository) {
        this.attributeDefinitionRepository = attributeDefinitionRepository;
        this.attributeApplicabilityRepository = attributeApplicabilityRepository;
    }

    public List<AttributeDefinition> list() {
        return attributeDefinitionRepository.findAll();
    }

    public AttributeDefinition getByIdOrThrow(Long id) {
        return attributeDefinitionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Attribute definition " + id + " not found"));
    }

    public List<AttributeDefinition> listApplicableToElementType(String elementType) {
        return attributeApplicabilityRepository.findByIdElementType(elementType).stream()
                .map(a -> a.getAttributeDefinition())
                .toList();
    }

    @Transactional
    public AttributeDefinition create(String code, String name, AttributeDataType dataType, String description) {
        return attributeDefinitionRepository.save(new AttributeDefinition(code, name, dataType, description));
    }

    @Transactional
    public AttributeDefinition update(Long id, String name, String description) {
        AttributeDefinition definition = getByIdOrThrow(id);
        definition.setName(name);
        definition.setDescription(description);
        return attributeDefinitionRepository.save(definition);
    }

    @Transactional
    public void delete(Long id) {
        attributeDefinitionRepository.delete(getByIdOrThrow(id));
    }
}
