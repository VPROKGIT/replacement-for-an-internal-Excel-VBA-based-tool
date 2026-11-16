import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { EditorStore } from '../editor.store';
import {
    AVAILABLE_FIELD_KEYS, AvailableField, Entry, HEADING_TYPE, NODE_KEYS, TreeNode, UI_PARAMETER_KEYS, UiParameter,
    ancestors, asArray, distinct, referencedBy, siblings, subtree,
} from '../model/template-doc';

type RowList = 'uiParameters' | 'availableFields';

interface FieldDef {
    key: string;
    label: string;
    kind: 'text' | 'area' | 'number';
    /** Id of a datalist with suggestions taken from the file. */
    list?: string;
    hint?: string;
}

interface ParentOption {
    uid: number;
    label: string;
}

/** Values a plain input can show and write back; anything else is edited as JSON. */
function isPlain(value: unknown): boolean {
    return value === null || value === undefined || ['string', 'number', 'boolean'].includes(typeof value);
}

/**
 * The right pane: everything about the selected node - its own properties, its UI parameters, its
 * available fields, and whatever else the file holds for it. Inputs write to the store when they
 * lose focus or Enter is pressed (the change event), so one edit is one undo step.
 */
@Component({
    selector: 'app-node-detail',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    templateUrl: './node-detail.component.html',
    styleUrl: './node-detail.component.css',
})
export class NodeDetailComponent {
    readonly store = inject(EditorStore);
    readonly headingType = HEADING_TYPE;

    readonly general: FieldDef[] = [
        { key: 'nodeIdentifier', label: 'Identifier', kind: 'text' },
        { key: 'nodeType', label: 'Type', kind: 'text', list: 'dl-node-types' },
        { key: 'uiLabel', label: 'Label', kind: 'text' },
        { key: 'nodeOptionsDataSource', label: 'Options data source', kind: 'text', list: 'dl-datasources' },
        { key: 'editableRule', label: 'Editable rule', kind: 'text' },
        { key: 'ordinal', label: 'Ordinal', kind: 'number', hint: 'Position among the nodes under the same parent' },
        { key: 'nodeDefinitionId', label: 'Node definition id', kind: 'number', hint: 'Changing it also updates the nodes that point at it' },
        { key: 'templateId', label: 'Template id', kind: 'number' },
        { key: 'uiInstructionText', label: 'Instruction text', kind: 'area' },
        { key: 'uiHelpText', label: 'Help text', kind: 'area' },
    ];

    readonly flags = [
        { key: 'isRequired', label: 'Required' },
        { key: 'isRepeating', label: 'Repeating' },
        { key: 'isRfiAllowed', label: 'RFI allowed' },
    ];

    /** Per JSON editor (by key), the message for text that is not valid JSON yet. */
    readonly jsonErrors = signal<Record<string, string>>({});

    readonly entry = this.store.selected;
    readonly issues = computed(() => this.store.issuesByUid().get(this.entry()?.uid ?? -1) ?? []);
    readonly path = computed(() => (this.entry() ? ancestors(this.store.entries(), this.entry()!.uid) : []));
    readonly usedBy = computed(() => (this.entry() ? referencedBy(this.store.entries(), this.entry()!) : []));
    readonly parameters = computed(() => asArray<UiParameter>(this.entry()?.node.uiParameters));
    readonly fields = computed(() => asArray<AvailableField>(this.entry()?.node.availableFields));
    readonly childCount = computed(() => (this.entry() ? subtree(this.store.entries(), this.entry()!.uid).length - 1 : 0));

    readonly position = computed(() => {
        const entry = this.entry();
        if (!entry) {
            return { first: true, last: true };
        }
        const sorted = siblings(this.store.entries(), entry);
        const index = sorted.findIndex(e => e.uid === entry.uid);
        return { first: index <= 0, last: index === sorted.length - 1 };
    });

    /** Properties of the node this editor has no dedicated control for, plus the non-plain ones. */
    readonly otherKeys = computed(() => {
        const node = this.entry()?.node;
        if (!node) {
            return [];
        }
        const dedicated = new Set<string>([...this.general.map(f => f.key), ...this.flags.map(f => f.key),
            'uiParameters', 'availableFields', 'parentHeading']);
        const known: string[] = NODE_KEYS.filter(k => !dedicated.has(k));
        const unknown = Object.keys(node).filter(k => !(NODE_KEYS as readonly string[]).includes(k));
        const notPlain = this.general.filter(f => !isPlain(node[f.key])).map(f => f.key);
        return [...known.filter(k => k in node), ...unknown, ...notPlain];
    });

    readonly extraParameterKeys = computed(() => this.extraKeys(this.parameters(), UI_PARAMETER_KEYS));
    readonly extraFieldKeys = computed(() => this.extraKeys(this.fields(), AVAILABLE_FIELD_KEYS));

    /** Headings this node could be moved under: every heading except itself and its own subtree. */
    readonly parentOptions = computed<ParentOption[]>(() => {
        const entry = this.entry();
        if (!entry) {
            return [];
        }
        const own = new Set(subtree(this.store.entries(), entry.uid).map(e => e.uid));
        const options: ParentOption[] = [];
        const walk = (n: TreeNode, depth: number) => {
            if (own.has(n.entry.uid)) {
                return;
            }
            const isParent = n.entry.node.nodeDefinitionId === entry.node.parentHeading;
            if (n.entry.node.nodeType === HEADING_TYPE || isParent) {
                options.push({ uid: n.entry.uid, label: '  '.repeat(depth) + this.name(n.entry) });
            }
            n.children.forEach(child => walk(child, depth + 1));
        };
        this.store.tree().roots.forEach(root => walk(root, 0));
        return options;
    });

    readonly parentUid = computed(() => {
        const parentId = this.entry()?.node.parentHeading;
        return this.store.entries().find(e => e.node.nodeDefinitionId === parentId && parentId !== null)?.uid ?? null;
    });

    /** Every node, in tree order: what a UI parameter can target. */
    readonly targets = computed(() => {
        const out: { id: number; label: string }[] = [];
        const walk = (n: TreeNode) => {
            const id = n.entry.node.nodeDefinitionId;
            if (typeof id === 'number') {
                out.push({ id, label: `${this.name(n.entry)} (${id})` });
            }
            n.children.forEach(walk);
        };
        const tree = this.store.tree();
        [...tree.roots, ...tree.orphans].forEach(walk);
        return out;
    });

    // Suggestions for free-text inputs: whatever the file already uses.
    readonly nodeTypes = computed(() => distinct([HEADING_TYPE, ...this.store.entries().map(e => e.node.nodeType)]));
    readonly datasources = computed(() => distinct(this.store.entries().flatMap(e =>
        [e.node.nodeOptionsDataSource, ...asArray<AvailableField>(e.node.availableFields).map(f => f?.datasource)])));
    readonly parameterNames = computed(() => distinct(this.allParameters().map(p => p?.uiParameter)));
    readonly actionTypes = computed(() => distinct(this.allParameters().map(p => p?.actionType)));
    readonly displays = computed(() => distinct(this.store.entries().flatMap(e =>
        asArray<AvailableField>(e.node.availableFields).map(f => f?.uiDisplay))));

    private allParameters(): UiParameter[] {
        return this.store.entries().flatMap(e => asArray<UiParameter>(e.node.uiParameters));
    }

    private extraKeys(rows: Record<string, unknown>[], known: readonly string[]): string[] {
        return [...new Set(rows.flatMap(row => Object.keys(row ?? {})))].filter(k => !known.includes(k));
    }

    name(entry: Entry): string {
        return entry.node.nodeIdentifier || entry.node.uiLabel || `#${entry.node.nodeDefinitionId}`;
    }

    isPlain(key: string): boolean {
        return isPlain(this.entry()?.node[key]);
    }

    hasTarget(id: number | null): boolean {
        return id === null || this.targets().some(t => t.id === id);
    }

    /** A value as JSON text, for the properties edited as JSON. */
    json(value: unknown): string {
        return value === undefined ? '' : JSON.stringify(value, null, 2);
    }

    /** A value in a one-line input: text as it is, anything else as JSON. */
    loose(value: unknown): string {
        if (value === null || value === undefined) {
            return '';
        }
        return typeof value === 'string' ? value : JSON.stringify(value);
    }

    // --- writing back ---------------------------------------------------------------------------

    private uid(): number {
        return this.entry()!.uid;
    }

    /** An empty input means "no value", which these files write as null. */
    private text(event: Event): string | null {
        const value = (event.target as HTMLInputElement).value;
        return value.trim() === '' ? null : value;
    }

    /** A number, null when emptied; undefined (meaning "leave it") for something that is no number. */
    private number(event: Event): number | null | undefined {
        const raw = (event.target as HTMLInputElement).value.trim();
        if (raw === '') {
            return null;
        }
        const value = Number(raw);
        return Number.isFinite(value) ? value : undefined;
    }

    /** Text typed into a one-line input for a property of unknown kind: JSON if it parses, else text. */
    private parseLoose(event: Event): unknown {
        const raw = (event.target as HTMLInputElement).value;
        if (raw.trim() === '') {
            return null;
        }
        try {
            return JSON.parse(raw);
        } catch {
            return raw;
        }
    }

    setGeneral(def: FieldDef, event: Event): void {
        const value = def.kind === 'number' ? this.number(event) : this.text(event);
        if (value !== undefined) {
            this.store.setNodeValue(this.uid(), def.key, value);
        }
    }

    setFlag(key: string, event: Event): void {
        this.store.setNodeValue(this.uid(), key, (event.target as HTMLInputElement).checked);
    }

    setParent(event: Event): void {
        const value = (event.target as HTMLSelectElement).value;
        this.store.setParent(this.uid(), value === '' ? null : Number(value));
    }

    setJson(key: string, event: Event): void {
        const raw = (event.target as HTMLTextAreaElement).value;
        let value: unknown;
        try {
            value = raw.trim() === '' ? null : JSON.parse(raw);
        } catch (e) {
            this.jsonErrors.update(errors => ({ ...errors, [key]: e instanceof Error ? e.message : 'Not valid JSON' }));
            return;
        }
        this.jsonErrors.update(({ [key]: _, ...rest }) => rest);
        this.store.setNodeValue(this.uid(), key, value);
    }

    setRowText(list: RowList, index: number, key: string, event: Event): void {
        this.store.setRowValue(this.uid(), list, index, key, this.text(event));
    }

    setRowNumber(list: RowList, index: number, key: string, event: Event): void {
        const value = this.number(event);
        if (value !== undefined) {
            this.store.setRowValue(this.uid(), list, index, key, value);
        }
    }

    setRowFlag(list: RowList, index: number, key: string, event: Event): void {
        this.store.setRowValue(this.uid(), list, index, key, (event.target as HTMLInputElement).checked);
    }

    setRowLoose(list: RowList, index: number, key: string, event: Event): void {
        this.store.setRowValue(this.uid(), list, index, key, this.parseLoose(event));
    }

    setTarget(index: number, event: Event): void {
        const value = (event.target as HTMLSelectElement).value;
        this.store.setRowValue(this.uid(), 'uiParameters', index, 'targetNodeDefId', value === '' ? null : Number(value));
    }
}
