package com.vprok.forms.repository;

import com.vprok.forms.entity.AttributeApplicability;
import com.vprok.forms.entity.AttributeApplicabilityId;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AttributeApplicabilityRepository extends JpaRepository<AttributeApplicability, AttributeApplicabilityId> {

    List<AttributeApplicability> findByIdElementType(String elementType);

    boolean existsByIdAttributeDefinitionIdAndIdElementType(Long attributeDefinitionId, String elementType);
}
