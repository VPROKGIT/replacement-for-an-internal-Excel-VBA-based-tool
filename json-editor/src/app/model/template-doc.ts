/*
 * The template file as data: reading it, laying it out as a tree, checking it, changing it and
 * writing it back. Pure functions with no Angular (and no imports at all), so they can be tested
 * and run on their own.
 *
 * A template file is a flat JSON array of node definitions. The tree is not nested in the file:
 * a node points at its parent through `parentHeading` (the parent's `nodeDefinitionId`, or null
 * at the top level) and siblings are ordered by `ordinal`.
 *
 * Two rules run through everything here:
 *  - Nothing in the file is lost. Nodes are kept as the parsed objects, so a property this editor
 *    does not know about survives a load and save untouched, in its original position.
 *  - A save changes as little of the file as possible: same indentation, line endings and key
 *    order, so a diff shows only what was edited.
 */

export interface UiParameter {
    uiParameter: string | null;
    id: number | null;
    targetValue: string | null;
    uiLabel: string | null;
    actionType: string | null;
    targetNodeDefId: number | null;
    targetDataNodeField: string | null;
    targetAvailableFieldIndex: number | null;
    [key: string]: unknown;
}

export interface AvailableField {
    id: number | null;
    fieldName: string | null;
    uiLabel: string | null;
    isRequired: boolean;
    uiValue: string | null;
    ordinal: number | null;
    datasource: string | null;
    uiDisplay: string | null;
    [key: string]: unknown;
}

export interface NodeDefinition {
    nodeType: string | null;
    nodeOptionsDataSource: string | null;
    nodeIdentifier: string | null;
    uiLabel: string | null;
    uiInstructionText: string | null;
    uiHelpText: string | null;
    uiParameters: UiParameter[];
    editableRule: unknown;
    parentHeading: number | null;
    isRepeating: boolean;
    ordinal: number | null;
    isRequired: boolean;
    availableFields: AvailableField[];
    nodeDefinitionId: number | null;
    templateId: number | null;
    isRfiAllowed: boolean;
    dependentNodeDefinitions: unknown[];
    [key: string]: unknown;
}

/** A node plus an identity that survives edits (ids in the file can be changed or duplicated). */
export interface Entry {
    uid: number;
    node: NodeDefinition;
}

export interface TreeNode {
    entry: Entry;
    children: TreeNode[];
}

export interface Tree {
    roots: TreeNode[];
    /** Nodes whose parent does not exist (or that sit in a parent loop), each with its subtree. */
    orphans: TreeNode[];
}

export interface Issue {
    uid: number;
    level: 'error' | 'warning';
    message: string;
}

/** How the file was written, so it can be written back the same way. */
export interface FileFormat {
    /** Everything before the first character of the JSON (a byte-order mark, blank lines). */
    prefix: string;
    /** Everything after its last character (usually a newline, or nothing). */
    suffix: string;
    /** One level of indentation; empty for a file written on a single line. */
    indent: string;
    /** Indentation the whole document is shifted by, as when it was cut out of a larger one. */
    base: string;
    eol: '\n' | '\r\n';
}

export interface ParsedTemplate {
    /** The whole parsed document: the node array itself, or an object that contains it. */
    root: unknown;
    /** Where the node array sits inside {@link root}; empty when the root is the array. */
    path: string[];
    nodes: NodeDefinition[];
    format: FileFormat;
}

export const NODE_KEYS = [
    'nodeType', 'nodeOptionsDataSource', 'nodeIdentifier', 'uiLabel', 'uiInstructionText', 'uiHelpText',
    'uiParameters', 'editableRule', 'parentHeading', 'isRepeating', 'ordinal', 'isRequired',
    'availableFields', 'nodeDefinitionId', 'templateId', 'isRfiAllowed', 'dependentNodeDefinitions',
] as const;

export const UI_PARAMETER_KEYS = [
    'uiParameter', 'id', 'targetValue', 'uiLabel', 'actionType', 'targetNodeDefId',
    'targetDataNodeField', 'targetAvailableFieldIndex',
] as const;

export const AVAILABLE_FIELD_KEYS = [
    'id', 'fieldName', 'uiLabel', 'isRequired', 'uiValue', 'ordinal', 'datasource', 'uiDisplay',
] as const;

export const HEADING_TYPE = 'HEADING';

// --- reading and writing -----------------------------------------------------------------------

export function detectFormat(text: string): FileFormat {
    const prefix = /^[﻿\s]*/.exec(text)![0];
    const suffix = text.length > prefix.length ? /\s*$/.exec(text)![0] : '';
    const body = text.slice(prefix.length, text.length - suffix.length);
    const eol = body.includes('\r\n') ? '\r\n' : '\n';
    const lines = body.split(/\r?\n/);
    if (lines.length < 2) {
        return { prefix, suffix, indent: '', base: '', eol };
    }
    const leading = (line: string) => /^[ \t]*/.exec(line)![0];
    // The last line is the closing bracket: its indentation is what the whole document is
    // shifted by. The second line is one level deeper than that.
    const base = leading(lines[lines.length - 1]);
    const second = leading(lines[1]);
    const indent = second.startsWith(base) && second.length > base.length ? second.slice(base.length) : second;
    return { prefix, suffix, indent: indent || '  ', base: indent ? base : '', eol };
}

/**
 * Reads a template file. Accepts the node array itself, or a document that holds it somewhere
 * inside (the rest of that document is kept and written back unchanged).
 */
export function parseTemplate(text: string): ParsedTemplate {
    let root: unknown;
    try {
        root = JSON.parse(text.replace(/^﻿/, ''));
    } catch (e) {
        throw new Error('This is not valid JSON: ' + (e instanceof Error ? e.message : String(e)));
    }
    const path = locateNodes(root);
    if (!path) {
        throw new Error(
            'This file does not look like a template: expected a list of node definitions '
            + '(objects with "nodeType" and "nodeDefinitionId").');
    }
    const nodes = path.reduce<any>((value, key) => value[key], root) as NodeDefinition[];
    return { root, path, nodes, format: detectFormat(text) };
}

export function serializeTemplate(parsed: Pick<ParsedTemplate, 'root' | 'path' | 'format'>, nodes: NodeDefinition[]): string {
    const { path, format } = parsed;
    let root: unknown = nodes;
    if (path.length > 0) {
        root = parsed.root;
        const holder = path.slice(0, -1).reduce<any>((value, key) => value[key], root);
        holder[path[path.length - 1]] = nodes;
    }
    let out = JSON.stringify(root, null, format.indent);
    if (format.base) {
        out = out.split('\n').map((line, i) => (i === 0 ? line : format.base + line)).join('\n');
    }
    if (format.eol === '\r\n') {
        out = out.replace(/\n/g, '\r\n');
    }
    return format.prefix + out + format.suffix;
}

function isObject(value: unknown): value is Record<string, unknown> {
    return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function looksLikeNodes(value: unknown): boolean {
    return Array.isArray(value) && value.length > 0 && value.every(isObject)
        && value.some(o => 'nodeType' in o || 'nodeDefinitionId' in o);
}

function locateNodes(root: unknown): string[] | null {
    if (Array.isArray(root)) {
        return root.length === 0 || looksLikeNodes(root) ? [] : null;
    }
    // Breadth-first, a few levels deep: { "nodeDefinitions": [...] }, { "template": { "nodes": [...] } }.
    let level: { value: unknown; path: string[] }[] = [{ value: root, path: [] }];
    for (let depth = 0; depth < 4 && level.length > 0; depth++) {
        const next: typeof level = [];
        for (const { value, path } of level) {
            if (!isObject(value)) {
                continue;
            }
            for (const [key, child] of Object.entries(value)) {
                if (looksLikeNodes(child)) {
                    return [...path, key];
                }
                next.push({ value: child, path: [...path, key] });
            }
        }
        level = next;
    }
    return null;
}

export function toEntries(nodes: NodeDefinition[], firstUid = 1): Entry[] {
    return nodes.map((node, i) => ({ uid: firstUid + i, node }));
}

// --- the tree ----------------------------------------------------------------------------------

function byOrdinal(index: Map<number, number>) {
    const ordinal = (e: Entry) => (typeof e.node.ordinal === 'number' ? e.node.ordinal : Number.MAX_SAFE_INTEGER);
    return (a: Entry, b: Entry) => ordinal(a) - ordinal(b) || index.get(a.uid)! - index.get(b.uid)!;
}

export function buildTree(entries: Entry[]): Tree {
    const index = new Map(entries.map((e, i) => [e.uid, i]));
    const byId = new Map<number, Entry>();
    for (const e of entries) {
        const id = e.node.nodeDefinitionId;
        if (typeof id === 'number' && !byId.has(id)) {
            byId.set(id, e); // with duplicate ids, children hang off the first; validate() reports it
        }
    }
    const childrenOf = new Map<number, Entry[]>();
    const roots: Entry[] = [];
    const loose: Entry[] = [];
    for (const e of entries) {
        const parentId = e.node.parentHeading;
        if (parentId === null || parentId === undefined) {
            roots.push(e);
            continue;
        }
        const parent = byId.get(parentId);
        if (parent && parent !== e) {
            childrenOf.set(parent.uid, [...(childrenOf.get(parent.uid) ?? []), e]);
        } else {
            loose.push(e);
        }
    }
    const order = byOrdinal(index);
    const seen = new Set<number>();
    const make = (e: Entry): TreeNode => {
        seen.add(e.uid);
        const children: TreeNode[] = [];
        for (const child of (childrenOf.get(e.uid) ?? []).sort(order)) {
            if (!seen.has(child.uid)) {
                children.push(make(child));
            }
        }
        return { entry: e, children };
    };
    const tree: Tree = { roots: roots.sort(order).map(make), orphans: loose.map(make) };
    // Whatever is still unvisited sits in a loop of parents (A under B under A).
    for (const e of entries) {
        if (!seen.has(e.uid)) {
            tree.orphans.push(make(e));
        }
    }
    return tree;
}

export function flatten(tree: Tree): Entry[] {
    const out: Entry[] = [];
    const walk = (n: TreeNode) => {
        out.push(n.entry);
        n.children.forEach(walk);
    };
    tree.roots.forEach(walk);
    tree.orphans.forEach(walk);
    return out;
}

export function findTreeNode(tree: Tree, uid: number): TreeNode | null {
    const stack = [...tree.roots, ...tree.orphans];
    while (stack.length > 0) {
        const n = stack.pop()!;
        if (n.entry.uid === uid) {
            return n;
        }
        stack.push(...n.children);
    }
    return null;
}

/** The node and everything under it. */
export function subtree(entries: Entry[], uid: number): Entry[] {
    const start = findTreeNode(buildTree(entries), uid);
    const out: Entry[] = [];
    const walk = (n: TreeNode) => {
        out.push(n.entry);
        n.children.forEach(walk);
    };
    if (start) {
        walk(start);
    }
    return out;
}

/** From the top-level heading down to the node's parent. */
export function ancestors(entries: Entry[], uid: number): Entry[] {
    const byId = new Map<number, Entry>();
    for (const e of entries) {
        if (typeof e.node.nodeDefinitionId === 'number' && !byId.has(e.node.nodeDefinitionId)) {
            byId.set(e.node.nodeDefinitionId, e);
        }
    }
    const out: Entry[] = [];
    let current = entries.find(e => e.uid === uid);
    const seen = new Set<number>([uid]);
    while (current && typeof current.node.parentHeading === 'number') {
        const parent = byId.get(current.node.parentHeading);
        if (!parent || seen.has(parent.uid)) {
            break;
        }
        seen.add(parent.uid);
        out.unshift(parent);
        current = parent;
    }
    return out;
}

export function siblings(entries: Entry[], entry: Entry): Entry[] {
    const parent = entry.node.parentHeading ?? null;
    const index = new Map(entries.map((e, i) => [e.uid, i]));
    return entries.filter(e => (e.node.parentHeading ?? null) === parent).sort(byOrdinal(index));
}

/** Whether the array lists every node right before its children, as the files normally do. */
export function isPreOrder(entries: Entry[]): boolean {
    const flat = flatten(buildTree(entries));
    return flat.every((e, i) => e === entries[i]);
}

function keepPreOrder(entries: Entry[], wasPreOrder: boolean): void {
    if (wasPreOrder) {
        entries.splice(0, entries.length, ...flatten(buildTree(entries)));
    }
}

// --- checks ------------------------------------------------------------------------------------

export function validate(entries: Entry[]): Issue[] {
    const issues: Issue[] = [];
    const add = (e: Entry, level: Issue['level'], message: string) => issues.push({ uid: e.uid, level, message });

    const count = <T>(values: T[]) => values.reduce((m, v) => m.set(v, (m.get(v) ?? 0) + 1), new Map<T, number>());
    const nodeIds = count(entries.map(e => e.node.nodeDefinitionId));
    const identifiers = count(entries.map(e => e.node.nodeIdentifier));
    const paramIds = count(entries.flatMap(e => asArray<UiParameter>(e.node.uiParameters).map(p => p?.id)));
    const fieldIds = count(entries.flatMap(e => asArray<AvailableField>(e.node.availableFields).map(f => f?.id)));
    const ordinals = count(entries.map(e => `${e.node.parentHeading ?? ''}|${e.node.ordinal}`));
    const byId = new Map(entries.map(e => [e.node.nodeDefinitionId, e]));
    const templateIds = count(entries.map(e => e.node.templateId));
    const usualTemplateId = [...templateIds.entries()].sort((a, b) => b[1] - a[1])[0]?.[0];
    const inLoop = new Set(loopMembers(entries));

    for (const e of entries) {
        const n = e.node;
        if (typeof n.nodeDefinitionId !== 'number') {
            add(e, 'error', 'nodeDefinitionId is missing.');
        } else if (nodeIds.get(n.nodeDefinitionId)! > 1) {
            add(e, 'error', `nodeDefinitionId ${n.nodeDefinitionId} is used by ${nodeIds.get(n.nodeDefinitionId)} nodes.`);
        }
        if (!n.nodeIdentifier) {
            add(e, 'error', 'nodeIdentifier is empty.');
        } else if (identifiers.get(n.nodeIdentifier)! > 1) {
            add(e, 'warning', `nodeIdentifier "${n.nodeIdentifier}" is used by ${identifiers.get(n.nodeIdentifier)} nodes.`);
        }
        if (!n.nodeType) {
            add(e, 'error', 'nodeType is empty.');
        }
        if (n.parentHeading !== null && n.parentHeading !== undefined) {
            const parent = byId.get(n.parentHeading);
            if (n.parentHeading === n.nodeDefinitionId) {
                add(e, 'error', 'parentHeading points at the node itself.');
            } else if (!parent) {
                add(e, 'error', `parentHeading ${n.parentHeading} does not exist in this file.`);
            } else if (inLoop.has(e.uid)) {
                add(e, 'error', 'This node is in a loop of parents, so it is not reachable from the top level.');
            } else if (parent.node.nodeType !== HEADING_TYPE) {
                add(e, 'warning', `Its parent "${parent.node.nodeIdentifier}" is a ${parent.node.nodeType}, not a ${HEADING_TYPE}.`);
            }
        }
        if (usualTemplateId !== undefined && n.templateId !== usualTemplateId) {
            add(e, 'warning', `templateId is ${n.templateId}; the rest of the file uses ${usualTemplateId}.`);
        }
        if (typeof n.ordinal !== 'number') {
            add(e, 'warning', 'ordinal is missing.');
        } else if (ordinals.get(`${n.parentHeading ?? ''}|${n.ordinal}`)! > 1) {
            add(e, 'warning', `ordinal ${n.ordinal} is shared with another node under the same parent.`);
        }
        asArray<UiParameter>(n.uiParameters).forEach((p, i) => {
            const name = `UI parameter ${i + 1}` + (p?.uiParameter ? ` (${p.uiParameter})` : '');
            if (!p?.uiParameter) {
                add(e, 'warning', `${name} has no uiParameter name.`);
            }
            if (typeof p?.targetNodeDefId === 'number' && !byId.has(p.targetNodeDefId)) {
                add(e, 'error', `${name} targets node ${p.targetNodeDefId}, which does not exist in this file.`);
            }
            if (typeof p?.id === 'number' && paramIds.get(p.id)! > 1) {
                add(e, 'warning', `${name} has id ${p.id}, which another UI parameter also has.`);
            }
        });
        asArray<AvailableField>(n.availableFields).forEach((f, i) => {
            const name = `Available field ${i + 1}` + (f?.fieldName ? ` (${f.fieldName})` : '');
            if (!f?.fieldName) {
                add(e, 'warning', `${name} has no fieldName.`);
            }
            if (typeof f?.id === 'number' && fieldIds.get(f.id)! > 1) {
                add(e, 'warning', `${name} has id ${f.id}, which another available field also has.`);
            }
        });
    }
    return issues;
}

/** The value as a list; a file where it is missing or something else is treated as having none. */
export function asArray<T>(value: unknown): T[] {
    return Array.isArray(value) ? value : [];
}

function loopMembers(entries: Entry[]): number[] {
    const reachable = new Set<number>();
    const walk = (n: TreeNode) => {
        reachable.add(n.entry.uid);
        n.children.forEach(walk);
    };
    buildTree(entries).roots.forEach(walk);
    const byId = new Map(entries.map(e => [e.node.nodeDefinitionId, e]));
    // Unreachable although its parent exists: the chain of parents never ends at the top level.
    return entries
        .filter(e => !reachable.has(e.uid) && typeof e.node.parentHeading === 'number' && byId.has(e.node.parentHeading))
        .filter(e => {
            const seen = new Set<number>();
            let current: Entry | undefined = e;
            while (current && typeof current.node.parentHeading === 'number') {
                if (seen.has(current.uid)) {
                    return true;
                }
                seen.add(current.uid);
                current = byId.get(current.node.parentHeading);
            }
            return false;
        })
        .map(e => e.uid);
}

// --- new ids -----------------------------------------------------------------------------------

function nextAfter(values: unknown[]): number {
    const numbers = values.filter((v): v is number => typeof v === 'number');
    return numbers.length === 0 ? 1 : Math.max(...numbers) + 1;
}

export function nextNodeId(entries: Entry[]): number {
    return nextAfter(entries.map(e => e.node.nodeDefinitionId));
}

export function nextUiParameterId(entries: Entry[]): number {
    return nextAfter(entries.flatMap(e => asArray<UiParameter>(e.node.uiParameters).map(p => p?.id)));
}

export function nextAvailableFieldId(entries: Entry[]): number {
    return nextAfter(entries.flatMap(e => asArray<AvailableField>(e.node.availableFields).map(f => f?.id)));
}

/** An empty value of the same kind as the sample: what a property unknown to this editor starts as. */
function blankLike(sample: unknown): unknown {
    if (Array.isArray(sample)) {
        return [];
    }
    return typeof sample === 'boolean' ? false : null;
}

/**
 * A new object with the same keys, in the same order, as its peers in the file (so a file with
 * extra properties gets them on new objects too), falling back to the standard keys.
 */
function shapedLike<T extends Record<string, unknown>>(peer: T | undefined, standardKeys: readonly string[], values: Record<string, unknown>): T {
    const out: Record<string, unknown> = {};
    const keys = peer ? [...Object.keys(peer), ...standardKeys.filter(k => !(k in peer))] : standardKeys;
    for (const key of keys) {
        out[key] = key in values ? values[key] : blankLike(peer?.[key]);
    }
    return out as T;
}

function uniqueIdentifier(entries: Entry[], wanted: string): string {
    const taken = new Set(entries.map(e => e.node.nodeIdentifier));
    let candidate = wanted;
    for (let n = 2; taken.has(candidate); n++) {
        candidate = wanted + n;
    }
    return candidate;
}

// --- changes (all of these change `entries` in place) -------------------------------------------

function find(entries: Entry[], uid: number): Entry {
    const entry = entries.find(e => e.uid === uid);
    if (!entry) {
        throw new Error(`Node ${uid} is no longer in the file.`);
    }
    return entry;
}

function renumber(sorted: Entry[]): void {
    sorted.forEach((e, i) => (e.node.ordinal = i + 1));
}

/** Adds a node as the last child of `parentUid` (or at the top level), and returns it. */
export function addNode(entries: Entry[], parentUid: number | null, nodeType: string, uid: number): Entry {
    const wasPreOrder = isPreOrder(entries);
    const parent = parentUid === null ? null : find(entries, parentUid);
    const parentId = parent ? parent.node.nodeDefinitionId : null;
    const peers = entries.filter(e => (e.node.parentHeading ?? null) === parentId);
    const templateIds = entries.map(e => e.node.templateId).filter(t => t !== null && t !== undefined);
    const node = shapedLike<NodeDefinition>(entries[0]?.node, NODE_KEYS, {
        nodeType,
        nodeOptionsDataSource: null,
        nodeIdentifier: uniqueIdentifier(entries, nodeType === HEADING_TYPE ? 'newHeading' : 'newNode'),
        uiLabel: null,
        uiInstructionText: null,
        uiHelpText: null,
        uiParameters: [],
        editableRule: null,
        parentHeading: parentId,
        isRepeating: false,
        ordinal: nextAfter(peers.map(e => e.node.ordinal)),
        isRequired: false,
        availableFields: [],
        nodeDefinitionId: nextNodeId(entries),
        templateId: parent?.node.templateId ?? templateIds[0] ?? null,
        isRfiAllowed: false,
        dependentNodeDefinitions: [],
    });
    const entry: Entry = { uid, node };
    // Right after the parent's last descendant, where a file in tree order would have it.
    const family = parent ? subtree(entries, parent.uid) : [];
    const after = family.length > 0 ? Math.max(...family.map(e => entries.indexOf(e))) : entries.length - 1;
    entries.splice(after + 1, 0, entry);
    keepPreOrder(entries, wasPreOrder);
    return entry;
}

/** Removes the node and everything under it; returns how many nodes went. */
export function deleteNode(entries: Entry[], uid: number): number {
    const entry = find(entries, uid);
    const gone = new Set(subtree(entries, uid).map(e => e.uid));
    gone.add(uid);
    entries.splice(0, entries.length, ...entries.filter(e => !gone.has(e.uid)));
    renumber(siblings(entries, entry).filter(e => e.uid !== uid));
    return gone.size;
}

/** Swaps the node with its neighbour among its siblings (-1 = up, +1 = down). */
export function moveNode(entries: Entry[], uid: number, direction: -1 | 1): boolean {
    const wasPreOrder = isPreOrder(entries);
    const sorted = siblings(entries, find(entries, uid));
    const from = sorted.findIndex(e => e.uid === uid);
    const to = from + direction;
    if (to < 0 || to >= sorted.length) {
        return false;
    }
    [sorted[from], sorted[to]] = [sorted[to], sorted[from]];
    renumber(sorted);
    keepPreOrder(entries, wasPreOrder);
    return true;
}

/** Whether `uid` may be moved under `parentUid`: not under itself or its own descendants. */
export function canMoveUnder(entries: Entry[], uid: number, parentUid: number | null): boolean {
    return parentUid === null || !subtree(entries, uid).some(e => e.uid === parentUid);
}

/** Moves the node (with its subtree) under another parent, as its last child. */
export function reparent(entries: Entry[], uid: number, parentUid: number | null): void {
    if (!canMoveUnder(entries, uid, parentUid)) {
        throw new Error('A node cannot be moved under itself or one of its own children.');
    }
    const wasPreOrder = isPreOrder(entries);
    const entry = find(entries, uid);
    const oldSiblings = siblings(entries, entry).filter(e => e.uid !== uid);
    const parentId = parentUid === null ? null : find(entries, parentUid).node.nodeDefinitionId;
    const newSiblings = entries.filter(e => e.uid !== uid && (e.node.parentHeading ?? null) === parentId);
    entry.node.parentHeading = parentId;
    entry.node.ordinal = nextAfter(newSiblings.map(e => e.node.ordinal));
    renumber(oldSiblings);
    keepPreOrder(entries, wasPreOrder);
}

/**
 * Changes a node's id and, when the old id was unambiguous, everything that pointed at it: its
 * children's parentHeading, UI parameters targeting it, and numbers in dependentNodeDefinitions.
 */
export function changeNodeId(entries: Entry[], uid: number, newId: number | null): void {
    const entry = find(entries, uid);
    const oldId = entry.node.nodeDefinitionId;
    const wasUnique = typeof oldId === 'number' && entries.filter(e => e.node.nodeDefinitionId === oldId).length === 1;
    entry.node.nodeDefinitionId = newId;
    if (!wasUnique || newId === null) {
        return;
    }
    for (const e of entries) {
        if (e.node.parentHeading === oldId) {
            e.node.parentHeading = newId;
        }
        for (const p of asArray<UiParameter>(e.node.uiParameters)) {
            if (p && p.targetNodeDefId === oldId) {
                p.targetNodeDefId = newId;
            }
        }
        if (Array.isArray(e.node.dependentNodeDefinitions)) {
            e.node.dependentNodeDefinitions = e.node.dependentNodeDefinitions.map(d => (d === oldId ? newId : d));
        }
    }
}

/** Copies one node (not its children) right after the original, with fresh ids. */
export function duplicateNode(entries: Entry[], uid: number, newUid: number): Entry {
    const wasPreOrder = isPreOrder(entries);
    const original = find(entries, uid);
    const node: NodeDefinition = JSON.parse(JSON.stringify(original.node));
    node.nodeDefinitionId = nextNodeId(entries);
    node.nodeIdentifier = uniqueIdentifier(entries, (original.node.nodeIdentifier ?? 'node') + 'Copy');
    let paramId = nextUiParameterId(entries);
    asArray<UiParameter>(node.uiParameters).forEach(p => (p.id = paramId++));
    let fieldId = nextAvailableFieldId(entries);
    asArray<AvailableField>(node.availableFields).forEach(f => (f.id = fieldId++));
    const copy: Entry = { uid: newUid, node };
    const sorted = siblings(entries, original);
    sorted.splice(sorted.indexOf(original) + 1, 0, copy);
    const family = subtree(entries, uid);
    entries.splice(Math.max(...family.map(e => entries.indexOf(e))) + 1, 0, copy);
    renumber(sorted);
    keepPreOrder(entries, wasPreOrder);
    return copy;
}

export function addUiParameter(entries: Entry[], uid: number): void {
    const node = find(entries, uid).node;
    const peer = entries.flatMap(e => asArray<UiParameter>(e.node.uiParameters))[0];
    const parameter = shapedLike<UiParameter>(peer, UI_PARAMETER_KEYS, {
        uiParameter: null, id: nextUiParameterId(entries), targetValue: null, uiLabel: null, actionType: null,
        targetNodeDefId: null, targetDataNodeField: null, targetAvailableFieldIndex: null,
    });
    node.uiParameters = [...asArray<UiParameter>(node.uiParameters), parameter];
}

export function addAvailableField(entries: Entry[], uid: number): void {
    const node = find(entries, uid).node;
    const peer = entries.flatMap(e => asArray<AvailableField>(e.node.availableFields))[0];
    const existing = asArray<AvailableField>(node.availableFields);
    const field = shapedLike<AvailableField>(peer, AVAILABLE_FIELD_KEYS, {
        id: nextAvailableFieldId(entries), fieldName: null, uiLabel: null, isRequired: false, uiValue: null,
        ordinal: nextAfter(existing.map(f => f?.ordinal)), datasource: null, uiDisplay: null,
    });
    node.availableFields = [...existing, field];
}

/** Moves or removes a row of `uiParameters` / `availableFields`; fields keep ordinals 1..n. */
export function moveRow(entries: Entry[], uid: number, list: 'uiParameters' | 'availableFields', index: number, direction: -1 | 1): void {
    const rows = asArray<Record<string, unknown>>(find(entries, uid).node[list]);
    const to = index + direction;
    if (to < 0 || to >= rows.length) {
        return;
    }
    [rows[index], rows[to]] = [rows[to], rows[index]];
    if (list === 'availableFields') {
        rows.forEach((row, i) => (row['ordinal'] = i + 1));
    }
}

export function removeRow(entries: Entry[], uid: number, list: 'uiParameters' | 'availableFields', index: number): void {
    const rows = asArray<Record<string, unknown>>(find(entries, uid).node[list]);
    rows.splice(index, 1);
    if (list === 'availableFields') {
        rows.forEach((row, i) => (row['ordinal'] = i + 1));
    }
}

/** Nodes with a UI parameter that targets this node (showIf and the like). */
export function referencedBy(entries: Entry[], entry: Entry): Entry[] {
    const id = entry.node.nodeDefinitionId;
    if (typeof id !== 'number') {
        return [];
    }
    return entries.filter(e => e !== entry && asArray<UiParameter>(e.node.uiParameters).some(p => p?.targetNodeDefId === id));
}

/** The distinct non-empty values of something across the file, sorted: suggestions for inputs. */
export function distinct(values: unknown[]): string[] {
    return [...new Set(values.filter((v): v is string => typeof v === 'string' && v !== ''))].sort((a, b) => a.localeCompare(b));
}
