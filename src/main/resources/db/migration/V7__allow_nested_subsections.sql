-- V7__allow_nested_subsections.sql
-- A SUBSECTION may contain another SUBSECTION (FORMS-18), to any depth.
-- Data only - no schema change. Cycles are already impossible: ElementService
-- rejects moving an element into its own subtree, whatever the types involved.
INSERT INTO element_type_rule (parent_type, child_type) VALUES
    ('SUBSECTION', 'SUBSECTION');
