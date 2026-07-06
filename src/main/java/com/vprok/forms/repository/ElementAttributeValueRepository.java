package com.vprok.forms.repository;

import com.vprok.forms.entity.ElementAttributeValue;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ElementAttributeValueRepository extends JpaRepository<ElementAttributeValue, Long> {

    // open-in-view is disabled, so callers that read attributeDefinition fields (not just its id)
    // off the result need it fetched eagerly here rather than lazily after the session is closed.
    @Query("select v from ElementAttributeValue v join fetch v.attributeDefinition where v.element.id = :elementId")
    List<ElementAttributeValue> findByElementId(@Param("elementId") Long elementId);

    Optional<ElementAttributeValue> findByElementIdAndAttributeDefinitionId(Long elementId, Long attributeDefinitionId);

    void deleteByElementIdAndAttributeDefinitionId(Long elementId, Long attributeDefinitionId);
}
