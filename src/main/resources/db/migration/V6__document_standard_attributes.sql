-- V6__document_standard_attributes.sql
-- FIELD_DOCUMENT gets the attributes every other field type already has:
-- MANDATORY, READ_ONLY and HELP_TEXT. Their absence in V5 was a gap in the
-- original spec, not a design decision. Data only - no schema change.
INSERT INTO attribute_applicability (attribute_definition_id, element_type)
SELECT id, 'FIELD_DOCUMENT' FROM attribute_definition
WHERE code IN ('MANDATORY', 'READ_ONLY', 'HELP_TEXT');
