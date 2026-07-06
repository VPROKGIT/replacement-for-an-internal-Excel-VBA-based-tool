package com.vprok.forms.repository;

import com.vprok.forms.entity.ElementTypeRule;
import com.vprok.forms.entity.ElementTypeRuleId;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ElementTypeRuleRepository extends JpaRepository<ElementTypeRule, ElementTypeRuleId> {

    List<ElementTypeRule> findByIdParentType(String parentType);

    boolean existsByIdParentTypeAndIdChildType(String parentType, String childType);
}
