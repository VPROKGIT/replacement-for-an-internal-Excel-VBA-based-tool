package com.vprok.forms.demo;

import static org.assertj.core.api.Assertions.assertThat;

import com.vprok.forms.entity.Element;
import com.vprok.forms.repository.ElementRepository;
import com.vprok.forms.service.GridLayoutService;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The review environment's example page (FORMS-21): built at startup without breaking any
 * hierarchy or attribute rule - a failure there would only be logged in production, so this is
 * where it must show up - and never duplicated by a restart.
 */
@SpringBootTest(properties = "forms.demo.seed=true")
@Testcontainers(disabledWithoutDocker = true)
class DemoPageSeederIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private DemoPageSeeder seeder;

    @Autowired
    private ElementRepository elementRepository;

    @Autowired
    private GridLayoutService gridLayoutService;

    private List<Element> demoPages() {
        return elementRepository.findAll().stream()
                .filter(e -> DemoPageSeeder.DEMO_PAGE_CODE.equals(e.getCode()) && e.getDeletedAt() == null)
                .toList();
    }

    @Test
    void theDemoPageIsBuiltOnceWithEveryKindOfElement() {
        List<Element> pages = demoPages();
        assertThat(pages).hasSize(1);
        List<Element> content = elementRepository.findByPageIdAndDeletedAtIsNullOrderByDisplayOrderAsc(pages.get(0).getId());

        Set<String> types = content.stream().map(Element::getElementType).collect(Collectors.toSet());
        assertThat(types).contains("SECTION", "SUBSECTION", "MAP", "MATRIX",
                "FIELD_TEXT", "FIELD_TEXTAREA", "FIELD_NUMBER", "FIELD_DATE", "FIELD_BOOLEAN", "FIELD_LIST", "FIELD_DOCUMENT");

        Element grid = content.stream().filter(e -> "DEMO_CONTACT_GRID".equals(e.getCode())).findFirst().orElseThrow();
        assertThat(gridLayoutService.columnCount(grid.getId())).isEqualTo(3);
        Element remarks = content.stream().filter(e -> "DEMO_REMARKS".equals(e.getCode())).findFirst().orElseThrow();
        assertThat(remarks.getGridPosition().rowSpan()).isEqualTo(2);
        assertThat(remarks.getGridPosition().columnSpan()).isEqualTo(3);

        // A restart finds the page and leaves it (and any reviewer's edits) alone.
        seeder.run(new DefaultApplicationArguments());
        assertThat(demoPages()).hasSize(1);
        assertThat(elementRepository.findByPageIdAndDeletedAtIsNullOrderByDisplayOrderAsc(pages.get(0).getId())).hasSameSizeAs(content);
    }
}
