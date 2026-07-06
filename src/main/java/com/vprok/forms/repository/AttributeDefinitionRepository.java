package com.vprok.forms.repository;

import com.vprok.forms.entity.AttributeDefinition;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AttributeDefinitionRepository extends JpaRepository<AttributeDefinition, Long> {

    Optional<AttributeDefinition> findByCode(String code);
}
