-- V2__seed_data.sql
-- Baseline attribute catalog + hierarchy rules, replacing the fixed columns
-- of the old Excel file. Extend by adding rows, not columns.

-- ---------------------------------------------------------------------------
-- Attribute catalog
-- ---------------------------------------------------------------------------
INSERT INTO attribute_definition (code, name, data_type, description) VALUES
    ('MANDATORY',        'Mandatory',              'BOOLEAN', 'Whether the field must be filled in.'),
    ('MULTIPLE_ALLOWED', 'Multiple inputs allowed','BOOLEAN', 'Whether the user may add more than one value/instance.'),
    ('READ_ONLY',        'Read only',              'BOOLEAN', 'Field is displayed but not editable.'),
    ('MAX_LENGTH',       'Maximum length',         'INTEGER', 'Maximum number of characters (text fields).'),
    ('MIN_LENGTH',       'Minimum length',         'INTEGER', 'Minimum number of characters (text fields).'),
    ('DEFAULT_VALUE',    'Default value',          'STRING',  'Pre-filled value shown to the user.'),
    ('PLACEHOLDER',      'Placeholder',            'STRING',  'Placeholder text inside the input.'),
    ('HELP_TEXT',        'Help text',              'STRING',  'Explanatory text rendered next to the field.'),
    ('REGEX_PATTERN',    'Validation pattern',     'STRING',  'Regular expression the value must match.'),
    ('MIN_VALUE',        'Minimum value',          'DECIMAL', 'Lower bound for numeric fields.'),
    ('MAX_VALUE',        'Maximum value',          'DECIMAL', 'Upper bound for numeric fields.'),
    ('COLLAPSED',        'Collapsed by default',   'BOOLEAN', 'Section/subsection starts collapsed in the UI.');

-- ---------------------------------------------------------------------------
-- Attribute applicability matrix
-- ---------------------------------------------------------------------------
WITH attr AS (SELECT id, code FROM attribute_definition)
INSERT INTO attribute_applicability (attribute_definition_id, element_type)
SELECT a.id, t.element_type
FROM attr a
JOIN LATERAL (
    VALUES
        ('MANDATORY',        'FIELD_TEXT'), ('MANDATORY',        'FIELD_TEXTAREA'),
        ('MANDATORY',        'FIELD_NUMBER'),('MANDATORY',       'FIELD_DATE'),
        ('MANDATORY',        'FIELD_BOOLEAN'),('MANDATORY',      'FIELD_LIST'),
        ('MULTIPLE_ALLOWED', 'FIELD_TEXT'), ('MULTIPLE_ALLOWED', 'FIELD_TEXTAREA'),
        ('MULTIPLE_ALLOWED', 'FIELD_NUMBER'),('MULTIPLE_ALLOWED','FIELD_DATE'),
        ('MULTIPLE_ALLOWED', 'FIELD_LIST'), ('MULTIPLE_ALLOWED', 'SUBSECTION'),
        ('READ_ONLY',        'FIELD_TEXT'), ('READ_ONLY',        'FIELD_TEXTAREA'),
        ('READ_ONLY',        'FIELD_NUMBER'),('READ_ONLY',       'FIELD_DATE'),
        ('READ_ONLY',        'FIELD_BOOLEAN'),('READ_ONLY',      'FIELD_LIST'),
        ('MAX_LENGTH',       'FIELD_TEXT'), ('MAX_LENGTH',       'FIELD_TEXTAREA'),
        ('MIN_LENGTH',       'FIELD_TEXT'), ('MIN_LENGTH',       'FIELD_TEXTAREA'),
        ('DEFAULT_VALUE',    'FIELD_TEXT'), ('DEFAULT_VALUE',    'FIELD_TEXTAREA'),
        ('DEFAULT_VALUE',    'FIELD_NUMBER'),('DEFAULT_VALUE',   'FIELD_DATE'),
        ('DEFAULT_VALUE',    'FIELD_BOOLEAN'),
        ('PLACEHOLDER',      'FIELD_TEXT'), ('PLACEHOLDER',      'FIELD_TEXTAREA'),
        ('PLACEHOLDER',      'FIELD_NUMBER'),
        ('HELP_TEXT',        'FIELD_TEXT'), ('HELP_TEXT',        'FIELD_TEXTAREA'),
        ('HELP_TEXT',        'FIELD_NUMBER'),('HELP_TEXT',       'FIELD_DATE'),
        ('HELP_TEXT',        'FIELD_BOOLEAN'),('HELP_TEXT',      'FIELD_LIST'),
        ('REGEX_PATTERN',    'FIELD_TEXT'),
        ('MIN_VALUE',        'FIELD_NUMBER'),('MAX_VALUE',       'FIELD_NUMBER'),
        ('COLLAPSED',        'SECTION'),    ('COLLAPSED',        'SUBSECTION')
) AS t(attr_code, element_type) ON t.attr_code = a.code;

-- ---------------------------------------------------------------------------
-- Hierarchy rules (flexible: fields may sit directly under a SECTION,
-- with or without an intermediate SUBSECTION)
-- ---------------------------------------------------------------------------
INSERT INTO element_type_rule (parent_type, child_type) VALUES
    ('PAGE',       'SECTION'),
    ('SECTION',    'SUBSECTION'),
    ('SECTION',    'FIELD_TEXT'),
    ('SECTION',    'FIELD_TEXTAREA'),
    ('SECTION',    'FIELD_NUMBER'),
    ('SECTION',    'FIELD_DATE'),
    ('SECTION',    'FIELD_BOOLEAN'),
    ('SECTION',    'FIELD_LIST'),
    ('SUBSECTION', 'FIELD_TEXT'),
    ('SUBSECTION', 'FIELD_TEXTAREA'),
    ('SUBSECTION', 'FIELD_NUMBER'),
    ('SUBSECTION', 'FIELD_DATE'),
    ('SUBSECTION', 'FIELD_BOOLEAN'),
    ('SUBSECTION', 'FIELD_LIST');
