package com.vprok.forms.web.dto.export;

import com.vprok.forms.entity.GridPosition;

/**
 * Where a child of a grid container (MATRIX) sits: its top-left cell (1-based) and how many rows
 * and columns it covers. Part of the export contract, kept separate from the entity's value type
 * so the two can't drift into each other.
 */
public record ExportLayout(int row, int column, int rowSpan, int columnSpan) {

    public static ExportLayout from(GridPosition position) {
        return position == null ? null
                : new ExportLayout(position.row(), position.column(), position.rowSpan(), position.columnSpan());
    }
}
