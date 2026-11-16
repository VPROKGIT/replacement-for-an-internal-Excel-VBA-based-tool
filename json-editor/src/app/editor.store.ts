import { Injectable, WritableSignal, computed, signal } from '@angular/core';
import {
    Entry, Issue, NodeDefinition, ParsedTemplate, addAvailableField, addNode, addUiParameter, buildTree,
    canMoveUnder, changeNodeId, deleteNode, duplicateNode, moveNode, moveRow, parseTemplate, removeRow,
    reparent, serializeTemplate, toEntries, validate,
} from './model/template-doc';

export interface Toast {
    message: string;
    kind: 'info' | 'error';
    /** Offers "Undo" beside the message. */
    undoable?: boolean;
}

interface Snapshot {
    id: number;
    entries: Entry[];
}

const HISTORY_LIMIT = 200;

/**
 * The open file and everything the screen needs to know about it. All state is signals; every
 * change goes through {@link change}, which works on a copy and keeps the previous state for undo,
 * so a state that has been shown is never modified afterwards.
 */
@Injectable({ providedIn: 'root' })
export class EditorStore {
    readonly entries = signal<Entry[]>([]);
    readonly loaded = signal(false);
    readonly fileName = signal<string | null>(null);
    readonly selectedUid = signal<number | null>(null);
    readonly collapsed = signal<ReadonlySet<number>>(new Set());
    readonly toast = signal<Toast | null>(null);
    /** False when saving will reformat the file (it was not written the way this editor writes). */
    readonly keepsFormatting = signal(true);
    /** Whether Save writes straight into the opened file, rather than downloading a copy. */
    readonly savesInPlace = signal(false);

    private readonly stateId = signal(0);
    private readonly savedStateId = signal(0);
    private readonly undoStack = signal<Snapshot[]>([]);
    private readonly redoStack = signal<Snapshot[]>([]);

    readonly dirty = computed(() => this.stateId() !== this.savedStateId());
    readonly canUndo = computed(() => this.undoStack().length > 0);
    readonly canRedo = computed(() => this.redoStack().length > 0);
    readonly tree = computed(() => buildTree(this.entries()));
    readonly issues = computed(() => validate(this.entries()));
    readonly issuesByUid = computed(() => {
        const map = new Map<number, Issue[]>();
        for (const issue of this.issues()) {
            map.set(issue.uid, [...(map.get(issue.uid) ?? []), issue]);
        }
        return map;
    });
    readonly selected = computed(() => this.entries().find(e => e.uid === this.selectedUid()) ?? null);

    private parsed: ParsedTemplate | null = null;
    private handle: FileSystemFileHandle | null = null;
    private nextUid = 1;
    private nextStateId = 1;
    private toastTimer: ReturnType<typeof setTimeout> | undefined;

    // --- files ----------------------------------------------------------------------------------

    /** Whether the browser can write back into the file it opened (Chrome, Edge). */
    get canPickFiles(): boolean {
        return 'showOpenFilePicker' in window;
    }

    /** Opens the browser's file picker. Returns false when this browser has none (use an input). */
    async openWithPicker(): Promise<boolean> {
        if (!this.canPickFiles) {
            return false;
        }
        try {
            const [handle] = await (window as any).showOpenFilePicker({
                types: [{ description: 'JSON files', accept: { 'application/json': ['.json'] } }],
            });
            await this.openHandle(handle);
        } catch (e) {
            this.reportUnlessCancelled(e, 'Could not open the file');
        }
        return true;
    }

    async openHandle(handle: FileSystemFileHandle): Promise<void> {
        const file = await handle.getFile();
        this.load(await file.text(), file.name, handle);
    }

    async openFile(file: File): Promise<void> {
        this.load(await file.text(), file.name, null);
    }

    /** Shows a document in the editor. Reports a file that is not a template instead of opening it. */
    load(text: string, name: string, handle: FileSystemFileHandle | null = null): boolean {
        let parsed: ParsedTemplate;
        try {
            parsed = parseTemplate(text);
        } catch (e) {
            this.show({ kind: 'error', message: `${name}: ${e instanceof Error ? e.message : e}` });
            return false;
        }
        this.parsed = parsed;
        this.handle = handle;
        this.nextUid = 1;
        const entries = toEntries(parsed.nodes, this.nextUid);
        this.nextUid += entries.length;
        this.entries.set(entries);
        this.undoStack.set([]);
        this.redoStack.set([]);
        this.stateId.set(this.nextStateId++);
        this.savedStateId.set(this.stateId());
        this.fileName.set(name);
        this.selectedUid.set(null);
        this.collapsed.set(new Set());
        this.keepsFormatting.set(serializeTemplate(parsed, parsed.nodes) === text);
        this.savesInPlace.set(handle !== null);
        this.loaded.set(true);
        this.toast.set(null);
        return true;
    }

    close(): void {
        this.parsed = null;
        this.handle = null;
        this.entries.set([]);
        this.loaded.set(false);
        this.fileName.set(null);
        this.selectedUid.set(null);
        this.savedStateId.set(this.stateId());
    }

    /** The file as it would be written now. */
    text(): string {
        return this.parsed ? serializeTemplate(this.parsed, this.entries().map(e => e.node)) : '';
    }

    /** Writes into the opened file; where that is not possible, downloads the file instead. */
    async save(): Promise<void> {
        if (!this.loaded()) {
            return;
        }
        if (!this.handle) {
            return this.saveAs();
        }
        try {
            await this.write(this.handle);
            this.markSaved(`Saved ${this.fileName()}`);
        } catch (e) {
            this.reportUnlessCancelled(e, 'Could not save the file');
        }
    }

    /** Asks where to save (Chrome, Edge) or downloads the file (other browsers). */
    async saveAs(): Promise<void> {
        if (!this.loaded()) {
            return;
        }
        const name = this.fileName() ?? 'template.json';
        if ('showSaveFilePicker' in window) {
            try {
                const handle: FileSystemFileHandle = await (window as any).showSaveFilePicker({
                    suggestedName: name,
                    types: [{ description: 'JSON files', accept: { 'application/json': ['.json'] } }],
                });
                await this.write(handle);
                this.handle = handle;
                this.savesInPlace.set(true);
                this.fileName.set(handle.name);
                this.markSaved(`Saved ${handle.name}`);
            } catch (e) {
                this.reportUnlessCancelled(e, 'Could not save the file');
            }
            return;
        }
        const url = URL.createObjectURL(new Blob([this.text()], { type: 'application/json' }));
        const link = document.createElement('a');
        link.href = url;
        link.download = name;
        link.click();
        URL.revokeObjectURL(url);
        this.markSaved(`Downloaded ${name} - replace the original file with it`);
    }

    private async write(handle: FileSystemFileHandle): Promise<void> {
        const writable = await (handle as any).createWritable();
        await writable.write(this.text());
        await writable.close();
    }

    private markSaved(message: string): void {
        this.savedStateId.set(this.stateId());
        this.keepsFormatting.set(true);
        this.show({ kind: 'info', message });
    }

    private reportUnlessCancelled(e: unknown, what: string): void {
        if (e instanceof DOMException && e.name === 'AbortError') {
            return; // the user closed the picker
        }
        this.show({ kind: 'error', message: `${what}: ${e instanceof Error ? e.message : e}` });
    }

    // --- history --------------------------------------------------------------------------------

    /** Applies a change to a copy of the file and makes that copy the current state. */
    private change<T>(apply: (entries: Entry[]) => T): T | undefined {
        const before = this.entries();
        const next = structuredClone(before);
        let result: T;
        try {
            result = apply(next);
        } catch (e) {
            this.show({ kind: 'error', message: e instanceof Error ? e.message : String(e) });
            return undefined;
        }
        this.undoStack.update(stack => [...stack.slice(-(HISTORY_LIMIT - 1)), { id: this.stateId(), entries: before }]);
        this.redoStack.set([]);
        this.entries.set(next);
        this.stateId.set(this.nextStateId++);
        return result;
    }

    undo(): void {
        this.step(this.undoStack, this.redoStack);
    }

    redo(): void {
        this.step(this.redoStack, this.undoStack);
    }

    private step(from: WritableSignal<Snapshot[]>, to: WritableSignal<Snapshot[]>): void {
        const stack = from();
        const snapshot = stack[stack.length - 1];
        if (!snapshot) {
            return;
        }
        from.set(stack.slice(0, -1));
        to.update(s => [...s, { id: this.stateId(), entries: this.entries() }]);
        this.entries.set(snapshot.entries);
        this.stateId.set(snapshot.id);
        if (this.selectedUid() !== null && !snapshot.entries.some(e => e.uid === this.selectedUid())) {
            this.selectedUid.set(null);
        }
        this.toast.set(null);
    }

    // --- selection and view ----------------------------------------------------------------------

    select(uid: number | null): void {
        this.selectedUid.set(uid);
    }

    /** Selects the node with this nodeDefinitionId, if there is one. */
    selectById(id: number | null): void {
        const entry = this.entries().find(e => e.node.nodeDefinitionId === id);
        if (entry) {
            this.selectedUid.set(entry.uid);
        }
    }

    toggleCollapsed(uid: number): void {
        this.collapsed.update(set => {
            const next = new Set(set);
            next.has(uid) ? next.delete(uid) : next.add(uid);
            return next;
        });
    }

    setAllCollapsed(collapsed: boolean): void {
        this.collapsed.set(new Set(collapsed ? this.entries().map(e => e.uid) : []));
    }

    private expand(uid: number): void {
        if (this.collapsed().has(uid)) {
            this.toggleCollapsed(uid);
        }
    }

    // --- node changes ---------------------------------------------------------------------------

    /** Sets one property of a node. Ids and parents go through their own methods. */
    setNodeValue(uid: number, key: string, value: unknown): void {
        const current = this.entries().find(e => e.uid === uid)?.node[key];
        if (JSON.stringify(current) === JSON.stringify(value)) {
            return;
        }
        if (key === 'nodeDefinitionId') {
            this.change(entries => changeNodeId(entries, uid, value as number | null));
        } else {
            this.change(entries => (entries.find(e => e.uid === uid)!.node[key] = value));
        }
    }

    setParent(uid: number, parentUid: number | null): void {
        this.change(entries => reparent(entries, uid, parentUid));
        if (parentUid !== null) {
            this.expand(parentUid);
        }
    }

    canMoveUnder(uid: number, parentUid: number | null): boolean {
        return canMoveUnder(this.entries(), uid, parentUid);
    }

    addNode(parentUid: number | null, nodeType: string): void {
        const uid = this.nextUid++;
        if (this.change(entries => addNode(entries, parentUid, nodeType, uid))) {
            if (parentUid !== null) {
                this.expand(parentUid);
            }
            this.selectedUid.set(uid);
        }
    }

    duplicateNode(uid: number): void {
        const newUid = this.nextUid++;
        if (this.change(entries => duplicateNode(entries, uid, newUid))) {
            this.selectedUid.set(newUid);
        }
    }

    deleteNode(uid: number): void {
        const entry = this.entries().find(e => e.uid === uid);
        if (!entry) {
            return;
        }
        const parent = this.entries().find(e => e.node.nodeDefinitionId === entry.node.parentHeading && e !== entry);
        const removed = this.change(entries => deleteNode(entries, uid));
        if (removed === undefined) {
            return;
        }
        this.selectedUid.set(parent?.uid ?? null);
        const name = entry.node.nodeIdentifier ?? 'node';
        this.show({
            kind: 'info',
            undoable: true,
            message: removed > 1 ? `Deleted "${name}" and the ${removed - 1} node(s) under it.` : `Deleted "${name}".`,
        });
    }

    moveNode(uid: number, direction: -1 | 1): void {
        // Tried on a copy first, so a move that does nothing (already first or last) records no state.
        if (moveNode(structuredClone(this.entries()), uid, direction)) {
            this.change(entries => moveNode(entries, uid, direction));
        }
    }

    // --- rows: UI parameters and available fields --------------------------------------------------

    addRow(uid: number, list: 'uiParameters' | 'availableFields'): void {
        this.change(entries => (list === 'uiParameters' ? addUiParameter(entries, uid) : addAvailableField(entries, uid)));
    }

    setRowValue(uid: number, list: 'uiParameters' | 'availableFields', index: number, key: string, value: unknown): void {
        const rows = this.entries().find(e => e.uid === uid)?.node[list] as Record<string, unknown>[] | undefined;
        if (!rows?.[index] || JSON.stringify(rows[index][key]) === JSON.stringify(value)) {
            return;
        }
        this.change(entries => {
            const row = (entries.find(e => e.uid === uid)!.node[list] as Record<string, unknown>[])[index];
            row[key] = value;
        });
    }

    moveRow(uid: number, list: 'uiParameters' | 'availableFields', index: number, direction: -1 | 1): void {
        this.change(entries => moveRow(entries, uid, list, index, direction));
    }

    removeRow(uid: number, list: 'uiParameters' | 'availableFields', index: number): void {
        this.change(entries => removeRow(entries, uid, list, index));
        this.show({ kind: 'info', undoable: true, message: list === 'uiParameters' ? 'UI parameter removed.' : 'Available field removed.' });
    }

    // --- messages -------------------------------------------------------------------------------

    show(toast: Toast): void {
        clearTimeout(this.toastTimer);
        this.toast.set(toast);
        if (toast.kind === 'info') {
            this.toastTimer = setTimeout(() => this.toast.set(null), 6000);
        }
    }

    dismissToast(): void {
        clearTimeout(this.toastTimer);
        this.toast.set(null);
    }
}

export type { Entry, Issue, NodeDefinition };
