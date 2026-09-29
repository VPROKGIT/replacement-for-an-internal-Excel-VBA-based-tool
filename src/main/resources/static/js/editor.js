/*
 * Page editor: drag and drop inside grid containers (MATRIX, FORMS-20).
 *
 * Optional by design. Dropping a field on a cell only fills in and posts #grid-move-form - the
 * same request the inspector's position fields send - so the server decides whether the move is
 * allowed (bounds, overlaps) and answers with its usual redirect and banner. Without this script
 * the editor works the same, positions are just typed in the inspector.
 *
 * The drop cell is worked out from the pointer and the grid's computed track sizes, so it is
 * right for every cell - empty, occupied, or covered by the dragged field itself. The dropped
 * field's top-left corner goes to that cell; its height and width are kept.
 */
(function () {
    'use strict';

    const form = document.getElementById('grid-move-form');
    if (!form) {
        return;
    }
    let dragged = null;

    document.querySelectorAll('.grid-item[draggable="true"]').forEach(function (item) {
        item.addEventListener('dragstart', function (event) {
            dragged = item;
            event.dataTransfer.effectAllowed = 'move';
            event.dataTransfer.setData('text/plain', item.dataset.elementId);
            item.classList.add('dragging');
        });
        item.addEventListener('dragend', function () {
            item.classList.remove('dragging');
            clearTarget();
            dragged = null;
        });
    });

    document.querySelectorAll('.grid').forEach(function (grid) {
        grid.addEventListener('dragover', function (event) {
            if (!dragged || dragged.parentElement !== grid) {
                return; // only within the field's own grid
            }
            event.preventDefault();
            event.dataTransfer.dropEffect = 'move';
            showTarget(grid, cellAt(grid, event));
        });
        grid.addEventListener('dragleave', function (event) {
            if (!grid.contains(event.relatedTarget)) {
                clearTarget();
            }
        });
        grid.addEventListener('drop', function (event) {
            if (!dragged || dragged.parentElement !== grid) {
                return;
            }
            event.preventDefault();
            const cell = cellAt(grid, event);
            form.action = form.dataset.actionPrefix + dragged.dataset.elementId + '/position';
            form.elements.row.value = cell.row;
            form.elements.column.value = cell.column;
            form.elements.rowSpan.value = dragged.dataset.rowSpan;
            form.elements.columnSpan.value = dragged.dataset.columnSpan;
            form.submit();
        });
    });

    /** The 1-based row and column under the pointer, from the grid's laid-out tracks. */
    function cellAt(grid, event) {
        const style = getComputedStyle(grid);
        const box = grid.getBoundingClientRect();
        return {
            row: trackAt(style.gridTemplateRows, parseFloat(style.rowGap) || 0,
                event.clientY - box.top - (parseFloat(style.paddingTop) || 0)),
            column: trackAt(style.gridTemplateColumns, parseFloat(style.columnGap) || 0,
                event.clientX - box.left - (parseFloat(style.paddingLeft) || 0))
        };
    }

    /** Which track (1-based) an offset falls in, given computed sizes like "120px 120px 120px". */
    function trackAt(template, gap, offset) {
        const sizes = template.trim().split(/\s+/).map(parseFloat);
        let edge = 0;
        for (let i = 0; i < sizes.length; i++) {
            edge += sizes[i];
            if (offset < edge + gap / 2) {
                return i + 1;
            }
            edge += gap;
        }
        return sizes.length;
    }

    function showTarget(grid, cell) {
        clearTarget();
        const empty = grid.querySelector('.grid-empty[data-row="' + cell.row + '"][data-column="' + cell.column + '"]');
        (empty || grid).classList.add('drop-target');
    }

    function clearTarget() {
        document.querySelectorAll('.drop-target').forEach(function (el) {
            el.classList.remove('drop-target');
        });
    }
})();
