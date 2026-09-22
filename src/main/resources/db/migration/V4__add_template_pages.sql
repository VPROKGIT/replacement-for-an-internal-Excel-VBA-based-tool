-- V4__add_template_pages.sql
-- Template pages: an otherwise ordinary PAGE whose MAPs are offered as reusable
-- starting points. A template is never listed as a form and never exported;
-- using one means deep-copying a MAP out of it into a real page (a one-time
-- copy - there is no link from a copy back to its template).

ALTER TABLE element ADD COLUMN is_template BOOLEAN NOT NULL DEFAULT FALSE;

-- Only a PAGE row can be a template. Everything that honours the flag (page
-- listing, export) looks only at page rows, so a stray TRUE on a section or
-- field would be silently meaningless; make it impossible instead.
ALTER TABLE element ADD CONSTRAINT chk_template_only_on_page
    CHECK (is_template = FALSE OR element_type = 'PAGE');

COMMENT ON COLUMN element.is_template IS
    'PAGE rows only. TRUE = a template page: excluded from page listings and export; its MAPs can be cloned into real pages.';
