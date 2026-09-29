package com.vprok.forms.web.ui;

import com.vprok.forms.entity.GridPosition;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * How the editor draws a grid container (MATRIX): its width, how many rows to show, and which
 * cells are free. Always one row more than the lowest field reaches, so there is somewhere to add.
 */
public record GridView(int columnCount, int rowCount, List<Cell> emptyCells) {

    public record Cell(int row, int column) {
    }

    public static GridView of(int columnCount, List<GridPosition> taken) {
        List<GridPosition> positions = taken.stream().filter(Objects::nonNull).toList();
        int rowCount = positions.stream().mapToInt(GridPosition::lastRow).max().orElse(0) + 1;
        List<Cell> empty = new ArrayList<>();
        for (int row = 1; row <= rowCount; row++) {
            for (int column = 1; column <= columnCount; column++) {
                int r = row;
                int c = column;
                if (positions.stream().noneMatch(p -> p.covers(r, c))) {
                    empty.add(new Cell(row, column));
                }
            }
        }
        return new GridView(columnCount, rowCount, empty);
    }
}
