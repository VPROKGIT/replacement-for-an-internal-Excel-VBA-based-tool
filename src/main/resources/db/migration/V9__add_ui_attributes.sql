-- V9__add_ui_attributes.sql
-- UI attributes (FORMS-22): an ordered list of entries per field, each entry a
-- set of free-text values the frontend reads from the export (e.g. "validate
-- this field against TARGET_VALUE"). Unlike attributes (one value per code per
-- element), a field can carry many entries, each using any subset of the kinds.
--
-- Data-driven like attribute_definition: the kinds are seeded rows, and which
-- element types carry entries is a table too, so both are a migration away -
-- no code change.

-- ---------------------------------------------------------------------------
-- The catalogue of kinds an entry can hold, in the order the editor shows them
-- and the export lists them.
-- ---------------------------------------------------------------------------
CREATE TABLE ui_attribute_definition (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code          VARCHAR(50)  NOT NULL UNIQUE,
    name          VARCHAR(100) NOT NULL,
    display_order INTEGER      NOT NULL DEFAULT 0,
    description   TEXT,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

COMMENT ON TABLE ui_attribute_definition IS 'Kinds of value a UI attribute entry can hold (TARGET_VALUE, ACTION_TYPE, ...). All free text.';

-- ---------------------------------------------------------------------------
-- Which element types carry UI attribute entries. Every kind applies to each
-- of them; there is no per-kind applicability (not needed so far).
-- ---------------------------------------------------------------------------
CREATE TABLE ui_attribute_element_type (
    element_type VARCHAR(30) PRIMARY KEY
);

COMMENT ON TABLE ui_attribute_element_type IS 'Element types that get the inspector''s UI attributes tab and may carry entries.';

-- ---------------------------------------------------------------------------
-- One entry (one summary line in the editor) of an element. Entries are
-- settings, not structure: hard-deleted, and invisible once their element is
-- soft-deleted (every read goes through a non-deleted element).
-- ---------------------------------------------------------------------------
CREATE TABLE ui_attribute_entry (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    element_id    BIGINT      NOT NULL REFERENCES element (id) ON DELETE CASCADE,
    display_order INTEGER     NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_ui_entry_element ON ui_attribute_entry (element_id);

COMMENT ON COLUMN ui_attribute_entry.display_order IS 'Order among the element''s entries; kept contiguous by the service layer.';

-- ---------------------------------------------------------------------------
-- The values of an entry: only the non-empty ones are stored, and the service
-- layer refuses an entry with none.
-- ---------------------------------------------------------------------------
CREATE TABLE ui_attribute_entry_value (
    id                         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    entry_id                   BIGINT NOT NULL REFERENCES ui_attribute_entry (id) ON DELETE CASCADE,
    ui_attribute_definition_id BIGINT NOT NULL REFERENCES ui_attribute_definition (id) ON DELETE RESTRICT,
    value                      TEXT   NOT NULL,

    CONSTRAINT uq_ui_entry_value UNIQUE (entry_id, ui_attribute_definition_id)
);

CREATE INDEX idx_ui_entry_value_entry ON ui_attribute_entry_value (entry_id);

-- ---------------------------------------------------------------------------
-- Seed: the seven kinds, and every field type.
-- ---------------------------------------------------------------------------
INSERT INTO ui_attribute_definition (code, name, display_order) VALUES
    ('UI_PARAMETER',                 'UI parameter',                 1),
    ('TARGET_VALUE',                 'Target value',                 2),
    ('TARGET_NODE_DEF_ID',           'Target node def id',           3),
    ('UI_LABEL',                     'UI label',                     4),
    ('ACTION_TYPE',                  'Action type',                  5),
    ('TARGET_DATA_NODE_FIELD',       'Target data node field',       6),
    ('TARGET_AVAILABLE_FIELD_INDEX', 'Target available field index', 7);

INSERT INTO ui_attribute_element_type (element_type) VALUES
    ('FIELD_TEXT'),
    ('FIELD_TEXTAREA'),
    ('FIELD_NUMBER'),
    ('FIELD_DATE'),
    ('FIELD_BOOLEAN'),
    ('FIELD_LIST'),
    ('FIELD_DOCUMENT');
