package com.vprok.forms.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.vprok.forms.entity.AttributeDataType;
import com.vprok.forms.entity.AttributeDefinition;
import com.vprok.forms.entity.Element;
import com.vprok.forms.entity.ElementAttributeValue;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Exercises the entities/repositories against real PostgreSQL so the JPA
 * mappings are proven against the actual V1/V2 Flyway migrations, not just
 * against an in-memory database. Skipped automatically without Docker.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
class ElementRepositoryIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private ElementRepository elementRepository;

    @Autowired
    private AttributeDefinitionRepository attributeDefinitionRepository;

    @Autowired
    private ElementAttributeValueRepository elementAttributeValueRepository;

    @Test
    void seedDataIsLoadedByFlyway() {
        List<AttributeDefinition> mandatory = attributeDefinitionRepository.findByCode("MANDATORY").map(List::of).orElse(List.of());
        assertThat(mandatory).hasSize(1);
        assertThat(mandatory.get(0).getDataType()).isEqualTo(AttributeDataType.BOOLEAN);
    }

    @Test
    void childrenAreOrderedByDisplayOrderAndSoftDeletedRowsAreExcluded() {
        Element page = new Element(null, null, "PAGE", "IT_PAGE", "Integration Test Page");
        elementRepository.save(page);

        Element sectionB = new Element(page, page, "SECTION", "SEC_B", "Section B");
        sectionB.setDisplayOrder(2);
        Element sectionA = new Element(page, page, "SECTION", "SEC_A", "Section A");
        sectionA.setDisplayOrder(1);
        Element sectionDeleted = new Element(page, page, "SECTION", "SEC_DEL", "Deleted Section");
        sectionDeleted.setDisplayOrder(0);
        elementRepository.saveAll(List.of(sectionB, sectionA, sectionDeleted));

        sectionDeleted.setDeletedAt(java.time.Instant.now());
        elementRepository.save(sectionDeleted);

        List<Element> children = elementRepository.findByParentElementIdAndDeletedAtIsNullOrderByDisplayOrderAsc(page.getId());

        assertThat(children).extracting(Element::getCode).containsExactly("SEC_A", "SEC_B");
    }

    @Test
    void attributeValueLookupByElementWorks() {
        Element page = new Element(null, null, "PAGE", "IT_PAGE_2", "Page 2");
        elementRepository.save(page);

        Element field = new Element(page, page, "FIELD_TEXT", "FLD_1", "Field 1");
        elementRepository.save(field);

        AttributeDefinition mandatory = attributeDefinitionRepository.findByCode("MANDATORY").orElseThrow();
        elementAttributeValueRepository.save(new ElementAttributeValue(field, mandatory, "true"));

        List<ElementAttributeValue> values = elementAttributeValueRepository.findByElementId(field.getId());

        assertThat(values).hasSize(1);
        assertThat(values.get(0).getValue()).isEqualTo("true");
    }
}
