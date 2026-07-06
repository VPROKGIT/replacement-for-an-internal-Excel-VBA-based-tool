package com.vprok.forms.repository;

import com.vprok.forms.entity.AttributeApplicability;
import com.vprok.forms.entity.AttributeApplicabilityId;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AttributeApplicabilityRepository extends JpaRepository<AttributeApplicability, AttributeApplicabilityId> {

    // Fetches attributeDefinition eagerly since open-in-view is disabled and callers need its fields.
    @Query("select a from AttributeApplicability a join fetch a.attributeDefinition where a.id.elementType = :elementType")
    List<AttributeApplicability> findByIdElementType(@Param("elementType") String elementType);

    boolean existsByIdAttributeDefinitionIdAndIdElementType(Long attributeDefinitionId, String elementType);
}
