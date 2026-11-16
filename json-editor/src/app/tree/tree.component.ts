import { NgTemplateOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { EditorStore } from '../editor.store';
import { HEADING_TYPE, TreeNode } from '../model/template-doc';

/**
 * The left pane: the file's nodes as the tree their parentHeading links describe. Clicking a node
 * opens it in the detail pane. The search box narrows the tree to matching nodes and their parents.
 */
@Component({
    selector: 'app-tree',
    standalone: true,
    imports: [NgTemplateOutlet],
    changeDetection: ChangeDetectionStrategy.OnPush,
    templateUrl: './tree.component.html',
    styleUrl: './tree.component.css',
})
export class TreeComponent {
    readonly store = inject(EditorStore);
    readonly filter = signal('');
    readonly headingType = HEADING_TYPE;

    /** With a search text: the nodes that match, plus their ancestors. Null when not searching. */
    readonly visible = computed<Set<number> | null>(() => {
        const text = this.filter().trim().toLowerCase();
        if (!text) {
            return null;
        }
        const visible = new Set<number>();
        const visit = (n: TreeNode): boolean => {
            const node = n.entry.node;
            const own = [node.nodeIdentifier, node.uiLabel, node.nodeType, node.nodeDefinitionId]
                .some(v => v !== null && v !== undefined && String(v).toLowerCase().includes(text));
            const below = n.children.map(visit).some(Boolean);
            if (own || below) {
                visible.add(n.entry.uid);
            }
            return own || below;
        };
        const tree = this.store.tree();
        [...tree.roots, ...tree.orphans].forEach(visit);
        return visible;
    });

    readonly matchCount = computed(() => this.visible()?.size ?? 0);

    shown(n: TreeNode): boolean {
        return this.visible()?.has(n.entry.uid) ?? true;
    }

    /** While searching everything that matches is shown, whatever was collapsed. */
    open(n: TreeNode): boolean {
        return this.visible() !== null || !this.store.collapsed().has(n.entry.uid);
    }

    toggle(event: Event, n: TreeNode): void {
        event.stopPropagation();
        this.store.toggleCollapsed(n.entry.uid);
    }

    label(n: TreeNode): string {
        return n.entry.node.nodeIdentifier || n.entry.node.uiLabel || '(no identifier)';
    }

    hint(n: TreeNode): string {
        const node = n.entry.node;
        return [`${node.nodeType} #${node.nodeDefinitionId}`, node.uiLabel ? `label: ${node.uiLabel}` : null]
            .filter(Boolean).join(' - ');
    }

    /** 'error', 'warning' or null: the worst problem found on this node. */
    issueLevel(n: TreeNode): 'error' | 'warning' | null {
        const issues = this.store.issuesByUid().get(n.entry.uid);
        if (!issues) {
            return null;
        }
        return issues.some(i => i.level === 'error') ? 'error' : 'warning';
    }

    /** A stable colour group per node type, so the same type always looks the same. */
    hue(nodeType: string | null): number {
        let hash = 0;
        for (const ch of nodeType ?? '') {
            hash = (hash * 31 + ch.charCodeAt(0)) % 360;
        }
        return hash;
    }
}
