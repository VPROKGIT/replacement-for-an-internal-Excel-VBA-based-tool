-- V8__add_matrix_element_type.sql
-- MATRIX (FORMS-20): a container that lays its fields out on a grid. It is a
-- layout aid for whoever builds the real form from the export: each field sits
-- in an explicit cell (row, column) and may span several rows and/or columns.
--
-- What makes a container a grid is data, not its type name: a container is a
-- grid when COLUMN_COUNT applies to its element_type (see GridLayoutService).
-- MATRIX is the first such type.

-- ---------------------------------------------------------------------------
-- Allow 'MATRIX' as an element_type (recreated, not edited: Postgres has no
-- ALTER for CHECK constraints).
-- ---------------------------------------------------------------------------
ALTER TABLE element DROP CONSTRAINT chk_element_type;

ALTER TABLE element ADD CONSTRAINT chk_element_type CHECK (element_type IN (
    'PAGE',
    'SECTION',
    'SUBSECTION',
    'MAP',
    'MATRIX',
    'FIELD_TEXT',
    'FIELD_TEXTAREA',
    'FIELD_NUMBER',
    'FIELD_DATE',
    'FIELD_BOOLEAN',
    'FIELD_LIST',
    'FIELD_DOCUMENT'
));

-- ---------------------------------------------------------------------------
-- A MATRIX sits under a SECTION or a SUBSECTION, and holds fields only - every
-- FIELD_* type, but no MAP, SUBSECTION or MATRIX.
-- ---------------------------------------------------------------------------
INSERT INTO element_type_rule (parent_type, child_type) VALUES
    ('SECTION',    'MATRIX'),
    ('SUBSECTION', 'MATRIX'),
    ('MATRIX',     'FIELD_TEXT'),
    ('MATRIX',     'FIELD_TEXTAREA'),
    ('MATRIX',     'FIELD_NUMBER'),
    ('MATRIX',     'FIELD_DATE'),
    ('MATRIX',     'FIELD_BOOLEAN'),
    ('MATRIX',     'FIELD_LIST'),
    ('MATRIX',     'FIELD_DOCUMENT');

-- ---------------------------------------------------------------------------
-- A grid child's cell. Dedicated columns rather than attributes: attribute
-- applicability is keyed by the element's own type, so a "row" attribute would
-- apply to every text field everywhere, not just those inside a grid. All four
-- are set (inside a grid) or all four are NULL (anywhere else); the service
-- layer enforces which, plus bounds and no-overlap, which a CHECK cannot see.
-- ---------------------------------------------------------------------------
ALTER TABLE element
    ADD COLUMN grid_row         INTEGER,
    ADD COLUMN grid_column      INTEGER,
    ADD COLUMN grid_row_span    INTEGER,
    ADD COLUMN grid_column_span INTEGER;

ALTER TABLE element ADD CONSTRAINT chk_grid_position CHECK (
    (grid_row IS NULL AND grid_column IS NULL AND grid_row_span IS NULL AND grid_column_span IS NULL)
    OR (grid_row >= 1 AND grid_column >= 1 AND grid_row_span >= 1 AND grid_column_span >= 1)
);

COMMENT ON COLUMN element.grid_row IS 'Top row (1-based) of this element''s cell when its parent is a grid (MATRIX); NULL otherwise.';
COMMENT ON COLUMN element.grid_column IS 'Left column (1-based) of this element''s cell when its parent is a grid; NULL otherwise.';
COMMENT ON COLUMN element.grid_row_span IS 'Rows the cell covers (>= 1) when its parent is a grid; NULL otherwise.';
COMMENT ON COLUMN element.grid_column_span IS 'Columns the cell covers (>= 1) when its parent is a grid; NULL otherwise.';

-- ---------------------------------------------------------------------------
-- The grid's width. Bounds (1-6) and "every field still fits" are enforced by
-- the service layer; a new MATRIX starts at 2.
-- ---------------------------------------------------------------------------
INSERT INTO attribute_definition (code, name, data_type, description) VALUES
    ('COLUMN_COUNT', 'Columns', 'INTEGER', 'Number of columns in the grid, from 1 to 6.');

INSERT INTO attribute_applicability (attribute_definition_id, element_type)
SELECT id, 'MATRIX' FROM attribute_definition WHERE code = 'COLUMN_COUNT';
