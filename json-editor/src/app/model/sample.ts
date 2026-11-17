import { AvailableField, NodeDefinition, UiParameter } from './template-doc';

/*
 * A small made-up template in the same shape as the real files, for trying the editor without a
 * file at hand (the welcome screen's "Try the sample") and for the tests. Nothing in it comes from
 * a real template.
 */

const TEMPLATE_ID = 900;

function node(id: number, parent: number | null, ordinal: number, nodeType: string, nodeIdentifier: string,
              extra: Partial<NodeDefinition> = {}): NodeDefinition {
    return {
        nodeType,
        nodeOptionsDataSource: null,
        nodeIdentifier,
        uiLabel: nodeIdentifier,
        uiInstructionText: null,
        uiHelpText: null,
        uiParameters: [],
        editableRule: null,
        parentHeading: parent,
        isRepeating: false,
        ordinal,
        isRequired: false,
        availableFields: [],
        nodeDefinitionId: id,
        templateId: TEMPLATE_ID,
        isRfiAllowed: false,
        dependentNodeDefinitions: [],
        ...extra,
    };
}

function parameter(id: number, uiParameter: string, actionType: string, extra: Partial<UiParameter> = {}): UiParameter {
    return {
        uiParameter, id, targetValue: null, uiLabel: null, actionType, targetNodeDefId: null,
        targetDataNodeField: null, targetAvailableFieldIndex: null, ...extra,
    };
}

function field(id: number, ordinal: number, fieldName: string, extra: Partial<AvailableField> = {}): AvailableField {
    return { id, fieldName, uiLabel: fieldName, isRequired: false, uiValue: null, ordinal, datasource: null, uiDisplay: null, ...extra };
}

export function sampleNodes(): NodeDefinition[] {
    return [
        node(1000, null, 1, 'HEADING', 'organiserData'),
        node(1001, 1000, 1, 'HEADING', 'organiserHeading', { isRequired: true }),
        node(1002, 1001, 1, 'CONTACT', 'organiser', {
            isRfiAllowed: true,
            availableFields: [
                field(5000, 1, 'name', { uiLabel: 'organiserName', isRequired: true }),
                field(5001, 2, 'email', { uiDisplay: 'inputText[]' }),
                field(5002, 3, 'countryCode', { uiLabel: 'country', datasource: 'countries' }),
            ],
        }),
        node(1003, null, 2, 'HEADING', 'eventData'),
        node(1004, 1003, 1, 'HEADING', 'eventHeading'),
        node(1005, 1004, 1, 'TEXT_MEDIUM', 'eventName', {
            isRequired: true,
            uiParameters: [parameter(7000, 'inputText', 'display')],
        }),
        node(1006, 1004, 2, 'BOOLEAN', 'isOnlineCB', {
            uiLabel: null,
            uiParameters: [parameter(7001, 'inputCheckbox', 'display')],
            availableFields: [field(5003, 1, 'FALSE', { uiLabel: 'isOnlineCB', isRequired: true, uiValue: 'false' })],
        }),
        node(1007, 1004, 3, 'TEXT_MEDIUM', 'venue', {
            nodeOptionsDataSource: 'venues',
            isRepeating: true,
            uiParameters: [
                parameter(7003, 'showIf', 'conditionalDisplay', { targetValue: 'false', targetNodeDefId: 1006 }),
                parameter(7002, 'typeahead', 'display'),
            ],
        }),
        node(1008, 1004, 4, 'TEXT_MEDIUM', 'description', {
            uiParameters: [parameter(7004, 'textarea', 'display')],
        }),
        node(1009, null, 3, 'HEADING', 'documents'),
        node(1010, 1009, 1, 'HEADING', 'documentsHeading'),
        node(1011, 1010, 1, 'DOCUMENT', 'programmeFile', {
            isRepeating: true,
            uiParameters: [
                parameter(7005, 'inputFile', 'display'),
                parameter(7006, 'documentType', 'defaultValue', { targetValue: 'Programme' }),
            ],
        }),
    ];
}

/** The sample as file text, indented the way the editor writes a new file. */
export function sampleText(): string {
    return JSON.stringify(sampleNodes(), null, 4) + '\n';
}
