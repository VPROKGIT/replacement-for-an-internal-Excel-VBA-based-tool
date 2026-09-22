-- V3__add_map_element_type.sql
-- MAP: a composite field that groups a mix of ordinary fields as its children
-- (e.g. 5 text boxes, 1 list, 1 text area).
--
-- MAP is deliberately just another node in the existing tree - no new table, no
-- new export logic, no Java enum. Its children are ordinary FIELD_* elements
-- with their own codes, attributes and list options. Everything that makes it
-- work is in this file: the type itself, and the hierarchy rows saying where a
-- MAP may sit and what it may contain.

-- ---------------------------------------------------------------------------
-- Allow 'MAP' as an element_type. The CHECK constraint is recreated rather
-- than edited in place; Postgres has no "alter constraint" for CHECK.
-- ---------------------------------------------------------------------------
ALTER TABLE element DROP CONSTRAINT chk_element_type;

ALTER TABLE element ADD CONSTRAINT chk_element_type CHECK (element_type IN (
    'PAGE',
    'SECTION',
    'SUBSECTION',
    'MAP',
    'FIELD_TEXT',
    'FIELD_TEXTAREA',
    'FIELD_NUMBER',
    'FIELD_DATE',
    'FIELD_BOOLEAN',
    'FIELD_LIST'
));

-- ---------------------------------------------------------------------------
-- A MAP is valid exactly where a FIELD_* is valid today: under a SECTION or a
-- SUBSECTION. Deliberately NOT under PAGE (fields aren't either), and
-- deliberately not inside another MAP - nested maps aren't a requirement, and
-- leaving the row out keeps that door closed until it is one.
-- ---------------------------------------------------------------------------
INSERT INTO element_type_rule (parent_type, child_type) VALUES
    ('SECTION',    'MAP'),
    ('SUBSECTION', 'MAP');

-- A MAP may contain any ordinary field subtype, in any mix and any number.
INSERT INTO element_type_rule (parent_type, child_type) VALUES
    ('MAP', 'FIELD_TEXT'),
    ('MAP', 'FIELD_TEXTAREA'),
    ('MAP', 'FIELD_NUMBER'),
    ('MAP', 'FIELD_DATE'),
    ('MAP', 'FIELD_BOOLEAN'),
    ('MAP', 'FIELD_LIST');
