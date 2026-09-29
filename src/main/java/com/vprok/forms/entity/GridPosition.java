package com.vprok.forms.entity;

/**
 * Where a child of a grid container (MATRIX) sits: its top-left cell (1-based) and how many rows
 * and columns it covers. A plain value - bounds and overlap are the service layer's job, since
 * they depend on the grid and the siblings.
 */
public record GridPosition(Integer row, Integer column, Integer rowSpan, Integer columnSpan) {

    public static GridPosition cell(int row, int column) {
        return new GridPosition(row, column, 1, 1);
    }

    public int lastRow() {
        return row + rowSpan - 1;
    }

    public int lastColumn() {
        return column + columnSpan - 1;
    }

    public boolean overlaps(GridPosition other) {
        return row <= other.lastRow() && other.row <= lastRow()
                && column <= other.lastColumn() && other.column <= lastColumn();
    }

    public boolean covers(int cellRow, int cellColumn) {
        return row <= cellRow && cellRow <= lastRow() && column <= cellColumn && cellColumn <= lastColumn();
    }
}
