-- V1__init_schema.sql
-- Core EAV schema for the form-structure application.
-- Requires PostgreSQL 15+.

-- ---------------------------------------------------------------------------
-- element: every node in the hierarchy (page, section, subsection, field)
-- ---------------------------------------------------------------------------
CREATE TABLE element (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    parent_element_id   BIGINT REFERENCES element (id),
    -- Denormalized reference to the root PAGE of this subtree; NULL only for
    -- PAGE rows themselves. Set by the service layer on create. Enables
    -- page-scoped code uniqueness and fast whole-page loads. If an element is
    -- ever moved between pages, page_id must be updated for the whole subtree.
    page_id             BIGINT REFERENCES element (id),
    element_type        VARCHAR(30)  NOT NULL,
    code                VARCHAR(100) NOT NULL,
    label               VARCHAR(255) NOT NULL,
    display_order       INTEGER      NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by          VARCHAR(100),
    updated_by          VARCHAR(100),
    -- Soft delete: rows are never removed by normal operations. All normal
    -- queries filter WHERE deleted_at IS NULL. Deleting an element requires
    -- the service layer to also soft-delete its subtree.
    deleted_at          TIMESTAMPTZ,
    deleted_by          VARCHAR(100),

    CONSTRAINT chk_element_type CHECK (element_type IN (
        'PAGE',
        'SECTION',
        'SUBSECTION',
        'FIELD_TEXT',
        'FIELD_TEXTAREA',
        'FIELD_NUMBER',
        'FIELD_DATE',
        'FIELD_BOOLEAN',
        'FIELD_LIST'
    )),

    -- Exactly the PAGE rows have no page_id and no parent.
    CONSTRAINT chk_page_root CHECK (
        (element_type = 'PAGE' AND page_id IS NULL AND parent_element_id IS NULL)
        OR
        (element_type <> 'PAGE' AND page_id IS NOT NULL AND parent_element_id IS NOT NULL)
    )
);

-- Page-scoped code uniqueness: a code may appear only once in the whole tree
-- under a page (not merely among siblings), among non-deleted rows. Partial
-- indexes are used so a soft-deleted element's code can be reused.
CREATE UNIQUE INDEX uq_element_page_code
    ON element (page_id, code)
    WHERE deleted_at IS NULL AND page_id IS NOT NULL;

-- Page codes themselves are globally unique among non-deleted pages.
CREATE UNIQUE INDEX uq_element_root_code
    ON element (code)
    WHERE deleted_at IS NULL AND element_type = 'PAGE';

CREATE INDEX idx_element_parent ON element (parent_element_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_element_page   ON element (page_id)           WHERE deleted_at IS NULL;
CREATE INDEX idx_element_type   ON element (element_type);

COMMENT ON TABLE  element IS 'Hierarchy of pages, sections, subsections and fields (self-referencing tree, soft-deleted).';
COMMENT ON COLUMN element.code IS 'Stable machine key, unique across the entire tree under its page (among non-deleted rows).';
COMMENT ON COLUMN element.display_order IS 'Ordering among siblings; uniqueness enforced in the service layer.';

-- ---------------------------------------------------------------------------
-- element_type_rule: which child types a parent type may contain.
-- Data-driven so the hierarchy stays flexible (e.g. fields directly under a
-- section) and new combinations are a seed row, not a code change. Enforced
-- by the service layer on create/move.
-- ---------------------------------------------------------------------------
CREATE TABLE element_type_rule (
    parent_type VARCHAR(30) NOT NULL,
    child_type  VARCHAR(30) NOT NULL,
    PRIMARY KEY (parent_type, child_type)
);

COMMENT ON TABLE element_type_rule IS 'Allowed parent/child element_type combinations; replaces a fixed 4-level hierarchy.';

-- ---------------------------------------------------------------------------
-- attribute_definition: catalog of possible parameters
-- ---------------------------------------------------------------------------
CREATE TABLE attribute_definition (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code        VARCHAR(50)  NOT NULL UNIQUE,
    name        VARCHAR(100) NOT NULL,
    data_type   VARCHAR(20)  NOT NULL,
    description TEXT,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT chk_attr_data_type CHECK (data_type IN (
        'BOOLEAN', 'STRING', 'INTEGER', 'DECIMAL', 'DATE'
    ))
);

COMMENT ON TABLE attribute_definition IS 'Catalog of parameters that can be attached to elements (MANDATORY, MAX_LENGTH, ...).';

CREATE TABLE attribute_applicability (
    attribute_definition_id BIGINT      NOT NULL
        REFERENCES attribute_definition (id) ON DELETE CASCADE,
    element_type            VARCHAR(30) NOT NULL,
    PRIMARY KEY (attribute_definition_id, element_type)
);

-- ---------------------------------------------------------------------------
-- element_attribute_value: actual value of an attribute on an element.
-- Values are settings, not structure: they are hard-deleted/replaced, and
-- become invisible automatically when their element is soft-deleted (queries
-- join through non-deleted elements).
-- ---------------------------------------------------------------------------
CREATE TABLE element_attribute_value (
    id                      BIGINT NOT NULL GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    element_id              BIGINT NOT NULL
        REFERENCES element (id) ON DELETE CASCADE,
    attribute_definition_id BIGINT NOT NULL
        REFERENCES attribute_definition (id) ON DELETE RESTRICT,
    value                   TEXT   NOT NULL,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_eav_element_attr UNIQUE (element_id, attribute_definition_id)
);

CREATE INDEX idx_eav_element ON element_attribute_value (element_id);
CREATE INDEX idx_eav_attr    ON element_attribute_value (attribute_definition_id);

COMMENT ON COLUMN element_attribute_value.value IS 'Stored as text; validated/cast in the application according to attribute_definition.data_type.';

-- ---------------------------------------------------------------------------
-- element_list_option: options for FIELD_LIST elements.
-- Uses an active flag (deactivate instead of delete) so historical answers
-- referencing an option never break; no separate deleted_at needed here.
-- ---------------------------------------------------------------------------
CREATE TABLE element_list_option (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    element_id    BIGINT       NOT NULL
        REFERENCES element (id) ON DELETE CASCADE,
    code          VARCHAR(100) NOT NULL,
    label         VARCHAR(255) NOT NULL,
    display_order INTEGER      NOT NULL DEFAULT 0,
    is_default    BOOLEAN      NOT NULL DEFAULT FALSE,
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uq_list_option_code UNIQUE (element_id, code)
);

CREATE INDEX idx_list_option_element ON element_list_option (element_id);
