# JSON export schema

The export endpoint returns the full structure of one page, ready for a frontend to render a
form from. There was no legacy VBA/database JSON format to stay compatible with, so this shape
was designed from scratch; the decisions and their reasoning are documented below.

## Endpoints

| Method | Path                                | Returns                          |
|--------|-------------------------------------|----------------------------------|
| `GET`  | `/api/export/pages/by-code/{code}`  | The page with that `code`        |
| `GET`  | `/api/export/pages/by-id/{id}`      | The page with that database `id` |

Both return `200` with the document below, or `404` (`application/problem+json`) if no such
non-deleted page exists. Requesting a non-`PAGE` element's id via `by-id` is also a `404` —
export is always rooted at a whole page, never a fragment.

Lookup by **code** is the intended integration path: `code` is the stable machine key. `by-id`
exists for tooling and for links out of the editor UI.

## Document shape

The document is a single **node**, recursive at every level. Every node has the same shape
regardless of whether it is the page, a section, a subsection, or a field:

| Key          | Type              | Always present? | Meaning                                                             |
|--------------|-------------------|-----------------|---------------------------------------------------------------------|
| `id`         | number            | yes             | Database id. Informational — build against `code`, not this.        |
| `code`       | string            | yes             | Stable machine key, unique within the page.                         |
| `label`      | string            | yes             | Human-readable display name.                                        |
| `type`       | string            | yes             | `PAGE`, `SECTION`, `SUBSECTION`, `MAP`, or one of the `FIELD_*` types. |
| `attributes` | object            | no              | Resolved attribute values, keyed by camelCase attribute code.       |
| `options`    | array of objects  | no              | Selectable options. Only on `FIELD_LIST` elements.                  |
| `children`   | array of nodes    | no              | Child elements, in display order.                                   |

Each entry of `options` is `{ "code": string, "label": string, "isDefault": boolean }`.

### Why one uniform recursive node instead of `page → sections → subsections → fields`

The hierarchy is data-driven: which child types a parent may contain lives in the
`element_type_rule` table, so fields may sit **directly under a section** with no intervening
subsection, and new combinations are a seed row rather than a code change. A fixed
`sections`/`subsections`/`fields` shape would re-introduce exactly the rigidity that table
exists to avoid, and would force consumers to check two different arrays for fields. One
recursive `children` array handles every current and future arrangement, and consumers can
render it with a single recursive function.

Branch on `type` to decide how to render a node. Treat any `type` starting with `FIELD_` as a
leaf input; `PAGE`, `SECTION`, `SUBSECTION`, and `MAP` are containers.

Note the rule is **not** "anything not starting with `FIELD_` is a container" by accident — it is
that containers are an open set that can grow. Prefer branching on the container types you know
and rendering an unrecognised node by recursing into its `children`, so a future container type
degrades to "renders its contents" rather than disappearing.

### `MAP`: a composite field

A `MAP` groups a mix of ordinary fields under one heading — for example five text boxes, a list,
and a text area that belong together. It is a **container, not an input**: it has no value of its
own, and it carries its fields in the same `children` array every other container uses. Its
children are ordinary `FIELD_*` nodes with their own `code`, `attributes`, and (for
`FIELD_LIST`) `options`, exactly as they would be anywhere else.

A `MAP` may appear wherever a field may appear — under a `SECTION` or a `SUBSECTION`. It cannot
contain another `MAP`, a `SECTION`, or a `SUBSECTION`; only fields.

There is no separate export path for maps: the node above is the whole contract. A consumer that
already recurses through `children` and branches on `type` needs no new code beyond deciding how
to lay a map's fields out visually.

## Conventions

**Absent means empty.** `attributes`, `options`, and `children` are omitted entirely when they
would be empty, rather than emitted as `{}` / `[]`. A missing key means "none" — never
"unknown". Treat a missing `children` as `[]` and a missing `attributes` as `{}`.

**Unset attributes are omitted, not defaulted.** If an attribute has no value for an element, its
key is absent. The application has no concept of a per-attribute default (there is no default
column on `attribute_definition`), so emitting one would mean inventing semantics in the export
layer. Apply your own defaults on the consuming side — e.g. treat a missing `mandatory` as
`false`.

**Attribute keys are camelCase attribute codes.** `MANDATORY` → `mandatory`,
`MULTIPLE_ALLOWED` → `multipleAllowed`, `MAX_LENGTH` → `maxLength`. This is a deterministic
transformation, not a lookup table, so an attribute added as a seed row automatically gets a
sensible key. Attribute values are resolved to their names and values — the export never exposes
raw `attribute_definition` ids that a consumer would have to resolve separately.

**Attribute values are typed per the attribute's `data_type`,** not returned as strings:

| `data_type` | JSON type | Example         |
|-------------|-----------|-----------------|
| `BOOLEAN`   | boolean   | `true`          |
| `INTEGER`   | number    | `50`            |
| `DECIMAL`   | number    | `1.5`           |
| `STRING`    | string    | `"Enter name"`  |
| `DATE`      | string    | `"2026-07-06"`  |

`DATE` stays a string in ISO-8601 (`yyyy-MM-dd`) form because JSON has no date type.

**Ordering is stable and meaningful.** `children` and `options` are ordered by their
`display_order`, which is the order authors arranged them in and the order they should render
in. Keys within `attributes` are sorted alphabetically, so a given structure always serialises
byte-identically and diffs cleanly.

**Deleted content never appears.** Elements are soft-deleted, and deleting one cascades to its
whole subtree; the export reads only non-deleted elements. Deactivated list options are likewise
excluded — they are retained in the database so historical answers still resolve, but must not be
offered for new input, so `options` contains only active ones.

## Worked example

A page with one section that contains **both** a field sitting directly under it and a subsection
holding two more fields — the two placements the flexible hierarchy allows:

```json
{
  "id": 1,
  "code": "EXPORT_PAGE_1",
  "label": "Export Page",
  "type": "PAGE",
  "children": [
    {
      "id": 2,
      "code": "SEC_A",
      "label": "Section A",
      "type": "SECTION",
      "attributes": { "collapsed": true },
      "children": [
        {
          "id": 3,
          "code": "DIRECT_FIELD",
          "label": "Direct Field",
          "type": "FIELD_TEXT",
          "attributes": { "mandatory": true, "maxLength": 50 }
        },
        {
          "id": 4,
          "code": "SUB_A",
          "label": "Subsection A",
          "type": "SUBSECTION",
          "children": [
            {
              "id": 5,
              "code": "NESTED_NUMBER",
              "label": "Nested Number",
              "type": "FIELD_NUMBER",
              "attributes": { "minValue": 1.5 }
            },
            {
              "id": 6,
              "code": "NESTED_LIST",
              "label": "Nested List",
              "type": "FIELD_LIST",
              "attributes": { "mandatory": false },
              "options": [
                { "code": "RED", "label": "Red", "isDefault": false },
                { "code": "BLUE", "label": "Blue", "isDefault": true }
              ]
            }
          ]
        }
      ]
    }
  ]
}
```

Points worth noting in the example above, since they are easy to misread:

- `DIRECT_FIELD` is a **direct child of the section**, a sibling of the subsection. Consumers
  must not assume fields only ever appear inside a subsection.
- `DIRECT_FIELD` has no `children` and no `options` key at all — it is a leaf, and not a list.
- `SUB_A` has no `attributes` key, because none are set on it.
- The `PAGE` node itself has no `attributes` key for the same reason. Sections and subsections
  *can* carry attributes (`collapsed` above) — attributes are not exclusive to fields.
- `NESTED_LIST` has a third option, `GREEN`, in the database that was deactivated; it is absent
  here.

This exact structure is asserted byte-for-byte (STRICT comparison) in `FormExportIT`, so this
document and the implementation cannot drift apart silently.

### Worked example: a section containing a `MAP`

An ordinary field and a `MAP` sitting side by side under the same section — the map is a peer of
the field, not a special case above it:

```json
{
  "id": 1,
  "code": "MAP_EXPORT_PAGE",
  "label": "Map Export Page",
  "type": "PAGE",
  "children": [
    {
      "id": 2,
      "code": "SEC_M",
      "label": "Section With Map",
      "type": "SECTION",
      "children": [
        {
          "id": 3,
          "code": "PLAIN_FIELD",
          "label": "Plain Field",
          "type": "FIELD_TEXT"
        },
        {
          "id": 4,
          "code": "ADDRESS_MAP",
          "label": "Address",
          "type": "MAP",
          "children": [
            {
              "id": 5,
              "code": "STREET",
              "label": "Street",
              "type": "FIELD_TEXT",
              "attributes": { "mandatory": true }
            },
            {
              "id": 6,
              "code": "COUNTRY",
              "label": "Country",
              "type": "FIELD_LIST",
              "options": [
                { "code": "BE", "label": "Belgium", "isDefault": true },
                { "code": "NL", "label": "Netherlands", "isDefault": false }
              ]
            },
            {
              "id": 7,
              "code": "NOTES",
              "label": "Notes",
              "type": "FIELD_TEXTAREA",
              "attributes": { "maxLength": 500 }
            }
          ]
        }
      ]
    }
  ]
}
```

Points worth noting:

- `ADDRESS_MAP` uses the **same `children` array** a `SECTION` or `SUBSECTION` uses. There is no
  map-specific key anywhere in the document.
- `ADDRESS_MAP` has no `attributes` key: no attribute in the catalogue is currently applicable to
  `MAP`, so it never carries any. That is a seed-data fact, not a shape difference — if an
  attribute is later made applicable to `MAP`, it appears here like on any other node.
- The map's children are unremarkable fields: `STREET` carries `mandatory`, `COUNTRY` carries
  `options` because it is a `FIELD_LIST`, `NOTES` carries `maxLength`. Nothing about them changes
  because their parent happens to be a map.

This structure is likewise asserted byte-for-byte (STRICT) in `FormExportIT`.

## Versioning

The document is deliberately unwrapped — the root *is* the page node, with no envelope. If a
breaking change is ever needed, add a version to the URL path or a header rather than changing
the node shape in place, so existing consumers keep working.
