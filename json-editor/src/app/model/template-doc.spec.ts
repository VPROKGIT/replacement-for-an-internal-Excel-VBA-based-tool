import { sampleNodes } from './sample';
import {
    Entry, addAvailableField, addNode, addUiParameter, buildTree, changeNodeId, deleteNode, detectFormat,
    duplicateNode, isPreOrder, moveNode, moveRow, parseTemplate, referencedBy, removeRow, reparent,
    serializeTemplate, toEntries, validate,
} from './template-doc';

/** The sample as the company's files are laid out: CRLF, 4 spaces, the whole array shifted by 4. */
function companyStyleText(): string {
    return JSON.stringify(sampleNodes(), null, 4).split('\n').map((line, i) => (i === 0 ? line : '    ' + line)).join('\r\n');
}

function entries(): Entry[] {
    return toEntries(sampleNodes());
}

function byName(list: Entry[], nodeIdentifier: string): Entry {
    return list.find(e => e.node.nodeIdentifier === nodeIdentifier)!;
}

function names(list: Entry[]): string {
    return list.map(e => e.node.nodeIdentifier).join(' ');
}

describe('reading and writing a template file', () => {
    it('writes an untouched file back byte for byte, whatever its indentation and line endings', () => {
        const layouts = [
            companyStyleText(),
            JSON.stringify(sampleNodes(), null, 2) + '\n',
            JSON.stringify(sampleNodes(), null, '\t'),
            JSON.stringify(sampleNodes()),
            '﻿' + JSON.stringify(sampleNodes(), null, 4) + '\r\n',
        ];
        for (const text of layouts) {
            const parsed = parseTemplate(text);
            expect(serializeTemplate(parsed, parsed.nodes)).toBe(text);
        }
    });

    it('recognises the shifted layout of the company files', () => {
        expect(detectFormat(companyStyleText())).toEqual({ prefix: '', suffix: '', indent: '    ', base: '    ', eol: '\r\n' });
    });

    it('changes only the edited line', () => {
        const text = companyStyleText();
        const parsed = parseTemplate(text);
        parsed.nodes[5].uiLabel = 'Event title';
        const before = text.split('\r\n');
        const after = serializeTemplate(parsed, parsed.nodes).split('\r\n');
        expect(after.length).toBe(before.length);
        expect(after.filter((line, i) => line !== before[i])).toEqual(['            "uiLabel": "Event title",']);
    });

    it('keeps properties it does not know, in their place', () => {
        const nodes: any[] = sampleNodes();
        nodes[0] = { somethingNew: { a: [1, 2] }, ...nodes[0], trailing: 'x' };
        const text = JSON.stringify(nodes, null, 4);
        const parsed = parseTemplate(text);
        expect(serializeTemplate(parsed, parsed.nodes)).toBe(text);
    });

    it('finds the nodes inside a larger document and writes the rest of it back unchanged', () => {
        const text = JSON.stringify({ name: 'Events', template: { version: 3, nodeDefinitions: sampleNodes() } }, null, 2);
        const parsed = parseTemplate(text);
        expect(parsed.path).toEqual(['template', 'nodeDefinitions']);
        expect(parsed.nodes.length).toBe(12);
        parsed.nodes[0].uiLabel = 'Changed';
        const saved = JSON.parse(serializeTemplate(parsed, parsed.nodes));
        expect(saved.name).toBe('Events');
        expect(saved.template.version).toBe(3);
        expect(saved.template.nodeDefinitions[0].uiLabel).toBe('Changed');
    });

    it('refuses what is not a template, saying why', () => {
        expect(() => parseTemplate('{ not json')).toThrowError(/not valid JSON/);
        expect(() => parseTemplate('{"hello": "world"}')).toThrowError(/does not look like a template/);
        expect(() => parseTemplate('[1, 2, 3]')).toThrowError(/does not look like a template/);
        expect(parseTemplate('[]').nodes).toEqual([]);
    });
});

describe('the tree', () => {
    it('follows parentHeading and orders siblings by ordinal, not by position in the file', () => {
        const list = entries().reverse();
        const tree = buildTree(list);
        expect(tree.roots.map(r => r.entry.node.nodeIdentifier)).toEqual(['organiserData', 'eventData', 'documents']);
        const eventHeading = tree.roots[1].children[0];
        expect(eventHeading.children.map(c => c.entry.node.nodeIdentifier)).toEqual(['eventName', 'isOnlineCB', 'venue', 'description']);
        expect(tree.orphans).toEqual([]);
    });

    it('sets aside nodes whose parent is missing or that sit in a loop, instead of losing them', () => {
        const list = entries();
        byName(list, 'venue').node.parentHeading = 4242;
        byName(list, 'documents').node.parentHeading = 1010; // documents -> documentsHeading -> documents
        const tree = buildTree(list);
        const orphanNames = tree.orphans.map(o => o.entry.node.nodeIdentifier);
        expect(orphanNames).toContain('venue');
        expect(orphanNames).toContain('documents');
        const shown = new Set<number>();
        const walk = (n: { entry: Entry; children: any[] }) => { shown.add(n.entry.uid); n.children.forEach(walk); };
        [...tree.roots, ...tree.orphans].forEach(walk);
        expect(shown.size).toBe(list.length);

        const messages = validate(list).map(i => i.message).join('\n');
        expect(messages).toContain('parentHeading 4242 does not exist');
        expect(messages).toContain('loop of parents');
    });
});

describe('checks', () => {
    it('finds nothing wrong with the sample', () => {
        expect(validate(entries())).toEqual([]);
    });

    it('reports duplicate ids and identifiers, dangling targets and clashing ordinals', () => {
        const list = entries();
        byName(list, 'description').node.nodeDefinitionId = 1005;
        byName(list, 'description').node.nodeIdentifier = 'eventName';
        byName(list, 'venue').node.uiParameters[0].targetNodeDefId = 9999;
        byName(list, 'venue').node.ordinal = 2;
        const issues = validate(list);
        const messages = issues.map(i => `${i.level}: ${i.message}`).join('\n');
        expect(messages).toContain('error: nodeDefinitionId 1005 is used by 2 nodes.');
        expect(messages).toContain('warning: nodeIdentifier "eventName" is used by 2 nodes.');
        expect(messages).toContain('error: UI parameter 1 (showIf) targets node 9999, which does not exist in this file.');
        expect(messages).toContain('warning: ordinal 2 is shared with another node under the same parent.');
    });
});

describe('changing the structure', () => {
    it('adds a node as the last child, shaped like the others, right after its parent\'s subtree in the file', () => {
        const list = entries();
        const added = addNode(list, byName(list, 'eventHeading').uid, 'TEXT_MEDIUM', 500);
        expect(added.node.parentHeading).toBe(1004);
        expect(added.node.ordinal).toBe(5);
        expect(added.node.nodeDefinitionId).toBe(1012);
        expect(added.node.templateId).toBe(900);
        expect(Object.keys(added.node)).toEqual(Object.keys(list[0].node));
        expect(names(list)).toContain('description newNode documents');
        expect(validate(list)).toEqual([]);
    });

    it('gives a new node the extra properties the file\'s nodes have', () => {
        const nodes: any[] = sampleNodes().map(n => ({ ...n, extraFlag: true, extraList: ['x'], extraText: 'y' }));
        const list = toEntries(nodes);
        const added = addNode(list, null, 'HEADING', 500).node;
        expect(added['extraFlag']).toBe(false);
        expect(added['extraList']).toEqual([]);
        expect(added['extraText']).toBeNull();
        expect(added.ordinal).toBe(4);
    });

    it('deletes a node with everything under it and closes the gap in the ordinals', () => {
        const list = entries();
        expect(deleteNode(list, byName(list, 'eventData').uid)).toBe(6);
        expect(names(list)).toBe('organiserData organiserHeading organiser documents documentsHeading programmeFile');
        expect(byName(list, 'documents').node.ordinal).toBe(2);
    });

    it('moves a node among its siblings, keeping ordinals 1..n and the file in tree order', () => {
        const list = entries();
        expect(moveNode(list, byName(list, 'eventData').uid, -1)).toBeTrue();
        expect(byName(list, 'eventData').node.ordinal).toBe(1);
        expect(byName(list, 'organiserData').node.ordinal).toBe(2);
        expect(names(list).startsWith('eventData eventHeading eventName')).toBeTrue();
        expect(isPreOrder(list)).toBeTrue();
        expect(moveNode(list, byName(list, 'eventData').uid, -1)).toBeFalse(); // already first
    });

    it('leaves the order of a file that is not in tree order alone', () => {
        const list = entries().reverse();
        const before = names(list);
        moveNode(list, byName(list, 'venue').uid, -1);
        expect(names(list)).toBe(before);
        expect(byName(list, 'venue').node.ordinal).toBe(2);
        expect(byName(list, 'isOnlineCB').node.ordinal).toBe(3);
    });

    it('moves a node with its subtree under another heading, but never under itself', () => {
        const list = entries();
        reparent(list, byName(list, 'eventHeading').uid, byName(list, 'documents').uid);
        expect(byName(list, 'eventHeading').node.parentHeading).toBe(1009);
        expect(byName(list, 'eventHeading').node.ordinal).toBe(2);
        expect(names(list)).toContain('documentsHeading programmeFile eventHeading eventName isOnlineCB venue description');
        expect(validate(list)).toEqual([]);
        expect(() => reparent(list, byName(list, 'documents').uid, byName(list, 'eventHeading').uid)).toThrowError(/under itself/);
    });

    it('changing an id takes along the children and the UI parameters that point at it', () => {
        const list = entries();
        changeNodeId(list, byName(list, 'isOnlineCB').uid, 2006);
        expect(byName(list, 'venue').node.uiParameters[0].targetNodeDefId).toBe(2006);
        changeNodeId(list, byName(list, 'eventHeading').uid, 2004);
        expect(byName(list, 'venue').node.parentHeading).toBe(2004);
        expect(validate(list)).toEqual([]);
        expect(referencedBy(list, byName(list, 'isOnlineCB')).map(e => e.node.nodeIdentifier)).toEqual(['venue']);
    });

    it('duplicates a node right after the original with fresh ids', () => {
        const list = entries();
        const copy = duplicateNode(list, byName(list, 'venue').uid, 500).node;
        expect(copy.nodeIdentifier).toBe('venueCopy');
        expect(copy.nodeDefinitionId).toBe(1012);
        expect(copy.ordinal).toBe(4);
        expect(byName(list, 'description').node.ordinal).toBe(5);
        expect(copy.uiParameters.map(p => p.id)).toEqual([7007, 7008]);
        expect(names(list)).toContain('venue venueCopy description');
        expect(validate(list)).toEqual([]);
    });
});

describe('UI parameters and available fields', () => {
    it('adds rows with the next free id', () => {
        const list = entries();
        const uid = byName(list, 'organiser').uid;
        addUiParameter(list, uid);
        addAvailableField(list, uid);
        const node = byName(list, 'organiser').node;
        expect(node.uiParameters[0]).toEqual({
            uiParameter: null, id: 7007, targetValue: null, uiLabel: null, actionType: null,
            targetNodeDefId: null, targetDataNodeField: null, targetAvailableFieldIndex: null,
        });
        expect(node.availableFields[3]).toEqual({
            id: 5004, fieldName: null, uiLabel: null, isRequired: false, uiValue: null, ordinal: 4, datasource: null, uiDisplay: null,
        });
    });

    it('moving or removing an available field keeps its ordinals 1..n', () => {
        const list = entries();
        const uid = byName(list, 'organiser').uid;
        moveRow(list, uid, 'availableFields', 2, -1);
        const fields = () => byName(list, 'organiser').node.availableFields.map(f => `${f.ordinal}:${f.fieldName}`);
        expect(fields()).toEqual(['1:name', '2:countryCode', '3:email']);
        removeRow(list, uid, 'availableFields', 0);
        expect(fields()).toEqual(['1:countryCode', '2:email']);
    });

    it('moves UI parameters by position', () => {
        const list = entries();
        moveRow(list, byName(list, 'venue').uid, 'uiParameters', 0, 1);
        expect(byName(list, 'venue').node.uiParameters.map(p => p.uiParameter)).toEqual(['typeahead', 'showIf']);
    });
});
