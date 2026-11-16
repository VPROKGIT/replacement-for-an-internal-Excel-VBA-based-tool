# Template editor

A browser editor for the template JSON files: open a file, see its node definitions as a tree, edit
them, and save the changes back into the file. Angular 17, no server and no database.

It is separate from the Spring Boot application in the rest of this repository and does not use it.

## Run it

Needs Node.js (built and tested on 24; Angular 17 officially supports 18 and 20, and prints a
warning on 24 that can be ignored).

```bash
cd json-editor
npm install
npm start
```

Then open http://localhost:4200 in **Chrome or Edge**. Those two can write straight back into the
file you opened; other browsers can only download a changed copy.

To produce static files that any web server (or a shared folder behind one) can serve:

```bash
npm run build
```

The result is in `dist/json-editor/browser`.

## The file format

A template file is a flat JSON array of node definitions. The tree is not nested in the file:

- a node's `parentHeading` is its parent's `nodeDefinitionId`, or `null` at the top level;
- siblings are ordered by `ordinal`;
- `uiParameters` and `availableFields` are lists inside each node.

A file where that array sits inside a larger document (`{ "nodeDefinitions": [...] }`) opens too;
the rest of the document is written back unchanged.

## What saving does to the file

- **Nothing is dropped.** Properties the editor has no control for are shown under "Other
  properties" and stay in the file, in their original position.
- **Only edited lines change.** Indentation, line endings and key order are kept, so a diff of the
  saved file shows just the edits. A file laid out in a way the editor cannot reproduce (unusual
  spacing) is re-indented on its first save; the overview page says so before you save.
- **Structure stays consistent.**
  - Moving, adding or deleting nodes keeps the ordinals under a parent at 1..n.
  - A file that lists every node before its children stays in that order.
  - Changing a `nodeDefinitionId` also updates the children and UI parameters that point at it.
- **New ids** (node, UI parameter, available field) are the highest id of that kind in the file
  plus one. Change them by hand if your system assigns ids differently.

## Checks

The editor flags, without blocking a save: duplicate `nodeDefinitionId`s and `nodeIdentifier`s, a
`parentHeading` or `targetNodeDefId` that points at nothing in the file, loops of parents, clashing
ordinals, a `templateId` that differs from the rest of the file, and unnamed UI parameters or fields.

## Code

| Where | What |
|-------|------|
| `src/app/model/template-doc.ts` | The file as data: parse, tree, checks, every change, write. No Angular. |
| `src/app/editor.store.ts` | The open file as signals: undo/redo, selection, open and save. |
| `src/app/tree/` | Left pane: the tree and its search. |
| `src/app/detail/` | Right pane: the selected node, its UI parameters and available fields. |
| `src/app/model/sample.ts` | A made-up template for "Try the sample" and the tests. |

## Tests

```bash
npm test -- --watch=false --browsers=ChromeHeadless
```

With Edge instead of Chrome, point `CHROME_BIN` at `msedge.exe` first.
