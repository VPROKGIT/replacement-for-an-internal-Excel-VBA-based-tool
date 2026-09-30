package com.vprok.forms.repository;

import com.vprok.forms.entity.UiAttributeDefinition;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UiAttributeDefinitionRepository extends JpaRepository<UiAttributeDefinition, Long> {

    List<UiAttributeDefinition> findAllByOrderByDisplayOrderAscIdAsc();

    /** Whether elements of this type carry UI attribute entries (seed data in ui_attribute_element_type). */
    @Query(value = "SELECT EXISTS (SELECT 1 FROM ui_attribute_element_type WHERE element_type = :elementType)", nativeQuery = true)
    boolean isApplicableTo(@Param("elementType") String elementType);
}
