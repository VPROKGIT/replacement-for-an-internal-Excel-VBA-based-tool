package com.vprok.forms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vprok.forms.entity.Element;
import com.vprok.forms.entity.GridPosition;
import com.vprok.forms.web.error.InvalidAttributeValueException;
import com.vprok.forms.web.error.InvalidElementHierarchyException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * MATRIX, the grid container (FORMS-20), against real PostgreSQL: every child in an explicit cell,
 * spans in both directions, no overlaps, nothing past the last column, a column count from 1 to 6
 * that its fields must fit in, and children kept in reading order. Each test builds its own page
 * with unique codes, since the class shares one database.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class MatrixIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private ElementService elementService;

    @Autowired
    private ElementAttributeValueService attributeValues;

    @Autowired
    private GridLayoutService gridLayoutService;

    /** A page with one section holding one MATRIX; returns [section, matrix]. */
    private Element[] matrixOnNewPage(String prefix) {
        Element page = elementService.create(null, "PAGE", prefix + "_PAGE", prefix + " page", null);
        Element section = elementService.create(page.getId(), "SECTION", prefix + "_SEC", "Section", null);
        Element matrix = elementService.create(section.getId(), "MATRIX", prefix + "_GRID", "Grid", null);
        return new Element[] {section, matrix};
    }

    private Element field(Element matrix, String code, GridPosition position) {
        return elementService.create(matrix.getId(), "FIELD_TEXT", code, code, null, position);
    }

    private List<String> childCodes(Element parent) {
        return elementService.getChildren(parent.getId()).stream().map(Element::getCode).toList();
    }

    private GridPosition positionOf(Element element) {
        return elementService.getActiveOrThrow(element.getId()).getGridPosition();
    }

    @Test
    void aNewMatrixHasTwoColumnsAndFieldsWithoutAPositionFillItInReadingOrder() {
        Element matrix = matrixOnNewPage("MX_FILL")[1];

        assertThat(attributeValues.list(matrix.getId()))
                .extracting(v -> v.getAttributeDefinition().getCode() + "=" + v.getValue())
                .containsExactly("COLUMN_COUNT=2");

        Element a = field(matrix, "MX_FILL_A", null);
        Element b = field(matrix, "MX_FILL_B", null);
        Element c = field(matrix, "MX_FILL_C", null);
        assertThat(positionOf(a)).isEqualTo(GridPosition.cell(1, 1));
        assertThat(positionOf(b)).isEqualTo(GridPosition.cell(1, 2));
        assertThat(positionOf(c)).isEqualTo(GridPosition.cell(2, 1));
        assertThat(childCodes(matrix)).containsExactly("MX_FILL_A", "MX_FILL_B", "MX_FILL_C");
    }

    @Test
    void positionsAreCheckedForBoundsAndOverlapsWithSpansInBothDirections() {
        Element matrix = matrixOnNewPage("MX_RULES")[1];
        attributeValues.setValue(matrix.getId(), "COLUMN_COUNT", "3");

        // A 2x2 block in the top-left corner.
        field(matrix, "MX_RULES_BLOCK", new GridPosition(1, 1, 2, 2));

        assertThatThrownBy(() -> field(matrix, "MX_RULES_INSIDE", GridPosition.cell(2, 2)))
                .isInstanceOf(InvalidElementHierarchyException.class)
                .hasMessageContaining("taken by MX_RULES_BLOCK");
        assertThatThrownBy(() -> field(matrix, "MX_RULES_WIDE", new GridPosition(1, 3, 1, 2)))
                .isInstanceOf(InvalidElementHierarchyException.class)
                .hasMessageContaining("past the last column");
        assertThatThrownBy(() -> field(matrix, "MX_RULES_ZERO", GridPosition.cell(0, 1)))
                .isInstanceOf(InvalidElementHierarchyException.class)
                .hasMessageContaining("1 or more");
        assertThatThrownBy(() -> field(matrix, "MX_RULES_PARTIAL", new GridPosition(1, 3, null, 1)))
                .isInstanceOf(InvalidElementHierarchyException.class);

        // Beside the block, and far below it: empty cells and empty rows in between are allowed.
        field(matrix, "MX_RULES_SIDE", new GridPosition(1, 3, 2, 1));
        field(matrix, "MX_RULES_LOW", GridPosition.cell(5, 2));

        // The first free cell skips everything the spans cover.
        Element auto = field(matrix, "MX_RULES_AUTO", null);
        assertThat(positionOf(auto)).isEqualTo(GridPosition.cell(3, 1));

        assertThat(childCodes(matrix))
                .containsExactly("MX_RULES_BLOCK", "MX_RULES_SIDE", "MX_RULES_AUTO", "MX_RULES_LOW");
    }

    @Test
    void placeMovesAndResizesAFieldButNeverOntoAnother() {
        Element matrix = matrixOnNewPage("MX_PLACE")[1];
        Element a = field(matrix, "MX_PLACE_A", null);
        Element b = field(matrix, "MX_PLACE_B", null);

        assertThatThrownBy(() -> elementService.place(a.getId(), GridPosition.cell(1, 2)))
                .isInstanceOf(InvalidElementHierarchyException.class)
                .hasMessageContaining("taken by MX_PLACE_B");

        // Down a row and full width - its own old cell doesn't count as taken.
        elementService.place(a.getId(), new GridPosition(2, 1, 1, 2));
        assertThat(positionOf(a)).isEqualTo(new GridPosition(2, 1, 1, 2));
        assertThat(childCodes(matrix)).containsExactly("MX_PLACE_B", "MX_PLACE_A");

        // Overlapping only itself is fine.
        elementService.place(a.getId(), new GridPosition(2, 2, 2, 1));
        assertThat(positionOf(a)).isEqualTo(new GridPosition(2, 2, 2, 1));

        // A deleted field leaves a gap, which the next field without a position takes.
        elementService.softDelete(b.getId());
        Element c = field(matrix, "MX_PLACE_C", null);
        assertThat(positionOf(c)).isEqualTo(GridPosition.cell(1, 1));
    }

    @Test
    void theColumnCountStaysFromOneToSixAndMustFitEveryField() {
        Element matrix = matrixOnNewPage("MX_COLS")[1];
        attributeValues.setValue(matrix.getId(), "COLUMN_COUNT", "3");
        field(matrix, "MX_COLS_RIGHT", GridPosition.cell(1, 3));

        assertThatThrownBy(() -> attributeValues.setValue(matrix.getId(), "COLUMN_COUNT", "7"))
                .isInstanceOf(InvalidAttributeValueException.class)
                .hasMessageContaining("from 1 to 6");
        assertThatThrownBy(() -> attributeValues.setValue(matrix.getId(), "COLUMN_COUNT", "0"))
                .isInstanceOf(InvalidAttributeValueException.class);
        assertThatThrownBy(() -> attributeValues.setValue(matrix.getId(), "COLUMN_COUNT", "99999999999"))
                .isInstanceOf(InvalidAttributeValueException.class);
        assertThatThrownBy(() -> attributeValues.deleteValue(matrix.getId(), "COLUMN_COUNT"))
                .isInstanceOf(InvalidAttributeValueException.class)
                .hasMessageContaining("needs a number of columns");
        assertThatThrownBy(() -> attributeValues.setValue(matrix.getId(), "COLUMN_COUNT", "2"))
                .isInstanceOf(InvalidAttributeValueException.class)
                .hasMessageContaining("MX_COLS_RIGHT");

        // Every rejection rolled back: still 3 columns.
        assertThat(gridLayoutService.columnCount(matrix.getId())).isEqualTo(3);
        attributeValues.setValue(matrix.getId(), "COLUMN_COUNT", "6");
        assertThat(gridLayoutService.columnCount(matrix.getId())).isEqualTo(6);
    }

    @Test
    void positionsOnlyExistInsideAGridAndMovingInOrOutKeepsThatTrue() {
        Element[] built = matrixOnNewPage("MX_MOVE");
        Element section = built[0];
        Element matrix = built[1];
        field(matrix, "MX_MOVE_FIRST", null);

        assertThatThrownBy(() -> elementService.create(section.getId(), "FIELD_TEXT", "MX_MOVE_BAD", "Bad", null, GridPosition.cell(1, 1)))
                .isInstanceOf(InvalidElementHierarchyException.class)
                .hasMessageContaining("inside a grid");

        Element outside = elementService.create(section.getId(), "FIELD_DATE", "MX_MOVE_DATE", "Date", null);
        assertThat(positionOf(outside)).isNull();
        assertThatThrownBy(() -> elementService.place(outside.getId(), GridPosition.cell(1, 1)))
                .isInstanceOf(InvalidElementHierarchyException.class);

        // In: the first free cell. Out: no position at all.
        elementService.move(outside.getId(), matrix.getId());
        assertThat(positionOf(outside)).isEqualTo(GridPosition.cell(1, 2));
        elementService.move(outside.getId(), section.getId());
        assertThat(positionOf(outside)).isNull();

        // A grid's order is its layout, so a plain reorder is refused.
        List<Long> ids = elementService.getChildren(matrix.getId()).stream().map(Element::getId).toList();
        assertThatThrownBy(() -> elementService.reorderChildren(matrix.getId(), ids))
                .isInstanceOf(InvalidElementHierarchyException.class)
                .hasMessageContaining("position in the grid");

        // Fields only inside a MATRIX, and a MATRIX never under a page directly.
        assertThatThrownBy(() -> elementService.create(matrix.getId(), "MAP", "MX_MOVE_MAP", "Map", null))
                .isInstanceOf(InvalidElementHierarchyException.class);
        assertThatThrownBy(() -> elementService.create(matrix.getId(), "SUBSECTION", "MX_MOVE_SUB", "Sub", null))
                .isInstanceOf(InvalidElementHierarchyException.class);
    }
}
