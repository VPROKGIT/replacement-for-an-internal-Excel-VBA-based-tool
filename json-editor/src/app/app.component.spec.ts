import { TestBed } from '@angular/core/testing';
import { AppComponent } from './app.component';
import { EditorStore } from './editor.store';
import { sampleText } from './model/sample';

describe('AppComponent', () => {
    beforeEach(async () => {
        await TestBed.configureTestingModule({ imports: [AppComponent] }).compileComponents();
    });

    it('starts on the welcome page', () => {
        const fixture = TestBed.createComponent(AppComponent);
        fixture.detectChanges();
        expect((fixture.nativeElement as HTMLElement).querySelector('.welcome h1')?.textContent).toContain('Open a template file');
    });

    it('shows the tree and the overview once a file is loaded, and the node once one is picked', () => {
        const fixture = TestBed.createComponent(AppComponent);
        const store = TestBed.inject(EditorStore);
        store.load(sampleText(), 'sample.json');
        fixture.detectChanges();
        const page = fixture.nativeElement as HTMLElement;

        expect(page.querySelectorAll('app-tree .row').length).toBe(12);
        expect(page.querySelector('.overview h1')?.textContent).toContain('sample.json');
        expect(page.querySelector('.pill')?.textContent).toContain('No problems');

        store.selectById(1007);
        fixture.detectChanges();
        expect(page.querySelector('app-node-detail h1')?.textContent).toContain('venue');
        expect(page.querySelectorAll('app-node-detail .param').length).toBe(2); // its two UI parameters

        store.selectById(1002);
        fixture.detectChanges();
        expect(page.querySelectorAll('app-node-detail tbody tr').length).toBe(3); // the contact's three available fields
    });

    it('refuses a file that is not a template, and says why', () => {
        const store = TestBed.inject(EditorStore);
        expect(store.load('{"hello": "world"}', 'other.json')).toBeFalse();
        expect(store.loaded()).toBeFalse();
        expect(store.toast()?.message).toContain('does not look like a template');
    });
});
