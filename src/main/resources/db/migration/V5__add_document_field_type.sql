-- V5__add_document_field_type.sql
-- FIELD_DOCUMENT: a field where the end user provides a document.
--
-- This defines the structural SLOT only. File storage and upload handling are
-- deliberately out of scope for this application - they are the frontend's
-- responsibility. Nothing here stores, references or validates file content.
--
-- Named FIELD_DOCUMENT rather than DOCUMENT because the FIELD_ prefix is
-- load-bearing: the export contract tells consumers "a type starting with
-- FIELD_ is a leaf input", and the admin UI offers the attribute editor on
-- FIELD_* types.

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
    'FIELD_LIST',
    'FIELD_DOCUMENT'
));

-- Valid wherever the other FIELD_* types are valid: under a SECTION, under a
-- SUBSECTION, and inside a MAP (a MAP holds "any mix of ordinary fields").
INSERT INTO element_type_rule (parent_type, child_type) VALUES
    ('SECTION',    'FIELD_DOCUMENT'),
    ('SUBSECTION', 'FIELD_DOCUMENT'),
    ('MAP',        'FIELD_DOCUMENT');

-- ---------------------------------------------------------------------------
-- Confidentiality. Applicable to FIELD_DOCUMENT only.
--
-- v1 rule, enforced in ElementAttributeValueService: CONFIDENTIAL = true
-- requires a non-blank NON_CONFIDENTIAL_VERSION_CODE. Deliberately NOT checked
-- in v1: that the code exists, or that it names a FIELD_DOCUMENT. That is a
-- conscious scope cut, not an oversight.
-- ---------------------------------------------------------------------------
INSERT INTO attribute_definition (code, name, data_type, description) VALUES
    ('CONFIDENTIAL', 'Confidential', 'BOOLEAN',
     'The document is confidential. When true, a non-confidential version must be named.'),
    ('NON_CONFIDENTIAL_VERSION_CODE', 'Non-confidential version (code)', 'STRING',
     'Code of the document field holding the non-confidential version. Required when Confidential is true.');

INSERT INTO attribute_applicability (attribute_definition_id, element_type)
SELECT id, 'FIELD_DOCUMENT' FROM attribute_definition
WHERE code IN ('CONFIDENTIAL', 'NON_CONFIDENTIAL_VERSION_CODE');
