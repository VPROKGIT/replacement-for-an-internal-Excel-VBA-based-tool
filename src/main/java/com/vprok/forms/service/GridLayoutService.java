package com.vprok.forms.service;

import com.vprok.forms.entity.AttributeDefinition;
import com.vprok.forms.entity.Element;
import com.vprok.forms.entity.ElementAttributeValue;
import com.vprok.forms.entity.GridPosition;
import com.vprok.forms.repository.AttributeApplicabilityRepository;
import com.vprok.forms.repository.AttributeDefinitionRepository;
import com.vprok.forms.repository.ElementAttributeValueRepository;
import com.vprok.forms.repository.ElementRepository;
import com.vprok.forms.web.error.InvalidAttributeValueException;
import com.vprok.forms.web.error.InvalidElementHierarchyException;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * The rules for grid containers (MATRIX, FORMS-20): each child sits in an explicit cell, may span
 * rows and columns, never overlaps a sibling and never runs past the last column.
 *
 * <p>Which containers are grids is data, not a type name: a type is a grid when the COLUMN_COUNT
 * attribute applies to it. Depends on repositories only, so both ElementService and
 * ElementAttributeValueService can use it without a dependency cycle; callers own the transaction.
 */
@Service
public class GridLayoutService {

    public static final String COLUMN_COUNT = "COLUMN_COUNT";
    public static final int MIN_COLUMNS = 1;
    public static final int MAX_COLUMNS = 6;
    public static final int DEFAULT_COLUMNS = 2;

    private final ElementRepository elementRepository;
    private final AttributeDefinitionRepository attributeDefinitionRepository;
    private final AttributeApplicabilityRepository attributeApplicabilityRepository;
    private final ElementAttributeValueRepository elementAttributeValueRepository;

    public GridLayoutService(
            ElementRepository elementRepository,
            AttributeDefinitionRepository attributeDefinitionRepository,
            AttributeApplicabilityRepository attributeApplicabilityRepository,
            ElementAttributeValueRepository elementAttributeValueRepository) {
        this.elementRepository = elementRepository;
        this.attributeDefinitionRepository = attributeDefinitionRepository;
        this.attributeApplicabilityRepository = attributeApplicabilityRepository;
        this.elementAttributeValueRepository = elementAttributeValueRepository;
    }

    public boolean isGridType(String elementType) {
        return columnCountDefinition()
                .map(def -> attributeApplicabilityRepository.existsByIdAttributeDefinitionIdAndIdElementType(def.getId(), elementType))
                .orElse(false);
    }

    /** The grid's width; a grid always has one (see {@link #requireValidColumnCount}), the default is only a fallback. */
    public int columnCount(Long gridId) {
        return storedColumnCount(gridId).map(Integer::parseInt).orElse(DEFAULT_COLUMNS);
    }

    /** Gives a newly created grid its starting width. */
    void initialise(Element grid) {
        columnCountDefinition().ifPresent(def ->
                elementAttributeValueRepository.save(new ElementAttributeValue(grid, def, String.valueOf(DEFAULT_COLUMNS))));
    }

    /**
     * The cell a child of {@code grid} gets: {@code requested} if it is valid and free, or the
     * first free single cell (in reading order) when nothing was requested.
     *
     * @param movingElementId the child being placed, whose own current cell doesn't count as taken; null for a new child
     */
    GridPosition place(Element grid, GridPosition requested, Long movingElementId) {
        List<Element> siblings = elementRepository.findByParentElementIdAndDeletedAtIsNullOrderByDisplayOrderAsc(grid.getId()).stream()
                .filter(e -> !e.getId().equals(movingElementId) && e.getGridPosition() != null)
                .toList();
        if (requested == null) {
            return firstFreeCell(siblings, columnCount(grid.getId()));
        }
        requireWellFormed(requested);
        int columns = columnCount(grid.getId());
        if (requested.lastColumn() > columns) {
            throw new InvalidElementHierarchyException(
                    "Column %d with a width of %d goes past the last column: this grid has %d."
                            .formatted(requested.column(), requested.columnSpan(), columns));
        }
        for (Element sibling : siblings) {
            if (sibling.getGridPosition().overlaps(requested)) {
                throw new InvalidElementHierarchyException(
                        "That space is taken by %s (%s).".formatted(sibling.getLabel(), sibling.getCode()));
            }
        }
        return requested;
    }

    /** Keeps a grid's children in reading order (row, then column), so display_order always matches the layout. */
    void resequence(Long gridId) {
        List<Element> children = elementRepository.findByParentElementIdAndDeletedAtIsNullOrderByDisplayOrderAsc(gridId).stream()
                .sorted(Comparator.comparing((Element e) -> e.getGridPosition() == null ? Integer.MAX_VALUE : e.getGridPosition().row())
                        .thenComparing(e -> e.getGridPosition() == null ? Integer.MAX_VALUE : e.getGridPosition().column()))
                .toList();
        for (int i = 0; i < children.size(); i++) {
            children.get(i).setDisplayOrder(i);
        }
        elementRepository.saveAll(children);
    }

    /**
     * After an attribute change: a grid must keep a column count from 1 to 6 that all its children
     * still fit in. Anything else is left alone.
     */
    void requireValidColumnCount(Element element) {
        if (!isGridType(element.getElementType())) {
            return;
        }
        String raw = storedColumnCount(element.getId()).orElseThrow(() -> new InvalidAttributeValueException(
                "A %s needs a number of columns (from %d to %d).".formatted(element.getElementType(), MIN_COLUMNS, MAX_COLUMNS)));
        // Already a valid INTEGER (long) by the time this runs, but not necessarily an int.
        long requested = Long.parseLong(raw);
        if (requested < MIN_COLUMNS || requested > MAX_COLUMNS) {
            throw new InvalidAttributeValueException(
                    "Columns must be from %d to %d, not %d.".formatted(MIN_COLUMNS, MAX_COLUMNS, requested));
        }
        int columns = (int) requested;
        List<Element> tooWide = elementRepository.findByParentElementIdAndDeletedAtIsNullOrderByDisplayOrderAsc(element.getId()).stream()
                .filter(e -> e.getGridPosition() != null && e.getGridPosition().lastColumn() > columns)
                .toList();
        if (!tooWide.isEmpty()) {
            throw new InvalidAttributeValueException("Columns can't be %d: %s still reach past column %d. Move or narrow them first."
                    .formatted(columns, tooWide.stream().map(e -> e.getLabel() + " (" + e.getCode() + ")").collect(Collectors.joining(", ")), columns));
        }
    }

    private static void requireWellFormed(GridPosition position) {
        if (position.row() == null || position.column() == null || position.rowSpan() == null || position.columnSpan() == null) {
            throw new InvalidElementHierarchyException("A position needs a row, a column, a height and a width.");
        }
        if (position.row() < 1 || position.column() < 1 || position.rowSpan() < 1 || position.columnSpan() < 1) {
            throw new InvalidElementHierarchyException("Row, column, height and width must all be 1 or more.");
        }
    }

    private static GridPosition firstFreeCell(List<Element> siblings, int columns) {
        for (int row = 1; ; row++) {
            for (int column = 1; column <= columns; column++) {
                int r = row;
                int c = column;
                if (siblings.stream().noneMatch(e -> e.getGridPosition().covers(r, c))) {
                    return GridPosition.cell(row, column);
                }
            }
        }
    }

    private Optional<AttributeDefinition> columnCountDefinition() {
        return attributeDefinitionRepository.findByCode(COLUMN_COUNT);
    }

    private Optional<String> storedColumnCount(Long gridId) {
        return columnCountDefinition()
                .flatMap(def -> elementAttributeValueRepository.findByElementIdAndAttributeDefinitionId(gridId, def.getId()))
                .map(ElementAttributeValue::getValue);
    }
}
