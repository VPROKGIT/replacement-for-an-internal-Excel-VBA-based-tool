package com.vprok.forms.repository;

import com.vprok.forms.entity.ElementAttributeValue;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ElementAttributeValueRepository extends JpaRepository<ElementAttributeValue, Long> {

    List<ElementAttributeValue> findByElementId(Long elementId);

    Optional<ElementAttributeValue> findByElementIdAndAttributeDefinitionId(Long elementId, Long attributeDefinitionId);

    void deleteByElementIdAndAttributeDefinitionId(Long elementId, Long attributeDefinitionId);
}
