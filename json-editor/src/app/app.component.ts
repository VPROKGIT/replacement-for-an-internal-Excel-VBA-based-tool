import { ChangeDetectionStrategy, Component, ElementRef, HostListener, computed, inject, signal, viewChild } from '@angular/core';
import { NodeDetailComponent } from './detail/node-detail.component';
import { EditorStore } from './editor.store';
import { sampleText } from './model/sample';
import { Entry } from './model/template-doc';
import { TreeComponent } from './tree/tree.component';

/**
 * The whole screen: a toolbar (open, save, undo), the tree on the left and the selected node on
 * the right. Before a file is open it is a welcome page; with a file open and nothing selected,
 * the right side is an overview of the file and its problems.
 */
@Component({
    selector: 'app-root',
    standalone: true,
    imports: [TreeComponent, NodeDetailComponent],
    changeDetection: ChangeDetectionStrategy.OnPush,
    templateUrl: './app.component.html',
    styleUrl: './app.component.css',
})
export class AppComponent {
    readonly store = inject(EditorStore);
    readonly dragging = signal(false);
    private readonly fileInput = viewChild<ElementRef<HTMLInputElement>>('fileInput');

    readonly errorCount = computed(() => this.store.issues().filter(i => i.level === 'error').length);
    readonly warningCount = computed(() => this.store.issues().length - this.errorCount());

    /** How many nodes of each type the file has, most frequent first. */
    readonly typeCounts = computed(() => {
        const counts = new Map<string, number>();
        for (const e of this.store.entries()) {
            const type = e.node.nodeType ?? '(no type)';
            counts.set(type, (counts.get(type) ?? 0) + 1);
        }
        return [...counts.entries()].sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]));
    });

    readonly templateIds = computed(() => [...new Set(this.store.entries().map(e => e.node.templateId))].join(', '));

    /** Issues with the node they are about, errors first. */
    readonly issueList = computed(() => {
        const byUid = new Map(this.store.entries().map(e => [e.uid, e]));
        return this.store.issues()
            .map(issue => ({ issue, entry: byUid.get(issue.uid)! }))
            .sort((a, b) => Number(b.issue.level === 'error') - Number(a.issue.level === 'error'));
    });

    name(entry: Entry): string {
        return entry.node.nodeIdentifier || entry.node.uiLabel || `#${entry.node.nodeDefinitionId}`;
    }

    // --- opening --------------------------------------------------------------------------------

    async open(): Promise<void> {
        if (!this.mayDiscard()) {
            return;
        }
        if (!(await this.store.openWithPicker())) {
            this.fileInput()?.nativeElement.click(); // browsers without a file picker of their own
        }
    }

    async picked(event: Event): Promise<void> {
        const input = event.target as HTMLInputElement;
        const file = input.files?.[0];
        input.value = ''; // so picking the same file again still fires
        if (file) {
            await this.store.openFile(file);
        }
    }

    openSample(): void {
        if (this.mayDiscard()) {
            this.store.load(sampleText(), 'sample-template.json');
        }
    }

    private mayDiscard(): boolean {
        return !this.store.dirty() || confirm(`"${this.store.fileName()}" has unsaved changes. Discard them?`);
    }

    // --- dropping a file on the window ----------------------------------------------------------

    @HostListener('window:dragover', ['$event'])
    onDragOver(event: DragEvent): void {
        if (event.dataTransfer?.types.includes('Files')) {
            event.preventDefault();
            this.dragging.set(true);
        }
    }

    @HostListener('window:dragleave', ['$event'])
    onDragLeave(event: DragEvent): void {
        if (!event.relatedTarget) {
            this.dragging.set(false);
        }
    }

    @HostListener('window:drop', ['$event'])
    async onDrop(event: DragEvent): Promise<void> {
        event.preventDefault();
        this.dragging.set(false);
        const item = event.dataTransfer?.items?.[0];
        const file = event.dataTransfer?.files?.[0];
        if (!file || !this.mayDiscard()) {
            return;
        }
        // Chrome and Edge hand over a handle, so a dropped file can be saved in place too.
        const handle = await (item as any)?.getAsFileSystemHandle?.().catch(() => null);
        if (handle?.kind === 'file') {
            await this.store.openHandle(handle);
        } else {
            await this.store.openFile(file);
        }
    }

    // --- keyboard and leaving -------------------------------------------------------------------

    @HostListener('window:keydown', ['$event'])
    onKey(event: KeyboardEvent): void {
        if (!(event.ctrlKey || event.metaKey) || !this.store.loaded()) {
            return;
        }
        const key = event.key.toLowerCase();
        if (key === 's') {
            event.preventDefault();
            // A field being typed in commits on blur; do that first so the save includes it.
            (document.activeElement as HTMLElement | null)?.blur();
            void (event.shiftKey ? this.store.saveAs() : this.store.save());
            return;
        }
        // In a text box Ctrl+Z belongs to the box (it undoes typing); elsewhere it undoes an edit.
        const typing = ['INPUT', 'TEXTAREA', 'SELECT'].includes((event.target as HTMLElement).tagName);
        if (typing) {
            return;
        }
        if (key === 'z' && !event.shiftKey) {
            event.preventDefault();
            this.store.undo();
        } else if (key === 'y' || (key === 'z' && event.shiftKey)) {
            event.preventDefault();
            this.store.redo();
        }
    }

    @HostListener('window:beforeunload', ['$event'])
    onLeave(event: BeforeUnloadEvent): void {
        if (this.store.dirty()) {
            event.preventDefault();
        }
    }
}
