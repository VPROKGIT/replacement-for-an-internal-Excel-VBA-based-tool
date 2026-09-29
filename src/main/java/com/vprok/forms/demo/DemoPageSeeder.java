package com.vprok.forms.demo;

import com.vprok.forms.entity.Element;
import com.vprok.forms.entity.GridPosition;
import com.vprok.forms.repository.ElementRepository;
import com.vprok.forms.service.ElementAttributeValueService;
import com.vprok.forms.service.ElementListOptionService;
import com.vprok.forms.service.ElementService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Puts an example page in a review environment (FORMS-21), so reviewers open the editor on
 * something that shows every kind of element rather than an empty screen.
 *
 * <p>Only when {@code forms.demo.seed=true} (the prod profile sets it). Built through the same
 * services as the editor, so every hierarchy and attribute rule applies. Runs on each start but
 * only creates the page when no active page has its code - so a reviewer's edits are kept, and a
 * deleted demo page comes back on the next start. A failure is logged, never fatal: the app is
 * still usable without the example.
 */
@Component
@ConditionalOnProperty(name = "forms.demo.seed", havingValue = "true")
public class DemoPageSeeder implements ApplicationRunner {

    public static final String DEMO_PAGE_CODE = "DEMO_LOAN_APPLICATION";

    private static final Logger log = LoggerFactory.getLogger(DemoPageSeeder.class);

    private final ElementService elements;
    private final ElementAttributeValueService attributes;
    private final ElementListOptionService options;
    private final ElementRepository elementRepository;
    private final TransactionTemplate transaction;

    public DemoPageSeeder(
            ElementService elements,
            ElementAttributeValueService attributes,
            ElementListOptionService options,
            ElementRepository elementRepository,
            PlatformTransactionManager transactionManager) {
        this.elements = elements;
        this.attributes = attributes;
        this.options = options;
        this.elementRepository = elementRepository;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    @Override
    public void run(ApplicationArguments args) {
        if (elementRepository.findByElementTypeAndCodeAndDeletedAtIsNull("PAGE", DEMO_PAGE_CODE).isPresent()) {
            return;
        }
        try {
            // All or nothing: a half-built demo page would be worse than none.
            transaction.executeWithoutResult(status -> seed());
            log.info("Created the demo page {}.", DEMO_PAGE_CODE);
        } catch (RuntimeException e) {
            log.error("Could not create the demo page {}; the app works without it.", DEMO_PAGE_CODE, e);
        }
    }

    void seed() {
        Element page = add(null, "PAGE", DEMO_PAGE_CODE, "Demo: loan application");

        // --- Applicant: nested subsections, a grid with spans and a gap, a composite MAP ---------
        Element applicant = add(page, "SECTION", "DEMO_APPLICANT", "Applicant");

        Element identity = add(applicant, "SUBSECTION", "DEMO_IDENTITY", "Identity");
        mandatory(add(identity, "FIELD_TEXT", "DEMO_FIRST_NAME", "First name"));
        mandatory(add(identity, "FIELD_TEXT", "DEMO_LAST_NAME", "Last name"));
        Element birth = add(identity, "FIELD_DATE", "DEMO_BIRTH_DATE", "Date of birth");
        mandatory(birth);
        help(birth, "As shown on the identity document.");

        Element idPublic = add(identity, "FIELD_DOCUMENT", "DEMO_ID_DOC_PUBLIC", "Identity document (redacted copy)");
        Element idFull = add(identity, "FIELD_DOCUMENT", "DEMO_ID_DOC", "Identity document");
        attributes.setValue(idFull.getId(), "NON_CONFIDENTIAL_VERSION_CODE", idPublic.getCode());
        attributes.setValue(idFull.getId(), "CONFIDENTIAL", "true");
        mandatory(idFull);

        Element contact = add(identity, "SUBSECTION", "DEMO_CONTACT", "Contact (a subsection inside a subsection)");
        Element grid = add(contact, "MATRIX", "DEMO_CONTACT_GRID", "Contact details");
        attributes.setValue(grid.getId(), "COLUMN_COUNT", "3");
        Element email = cell(grid, "FIELD_TEXT", "DEMO_EMAIL", "Email", new GridPosition(1, 1, 1, 2));
        mandatory(email);
        attributes.setValue(email.getId(), "PLACEHOLDER", "name@example.com");
        cell(grid, "FIELD_TEXT", "DEMO_PHONE", "Phone", GridPosition.cell(1, 3));
        // Row 2, column 2 is left empty on purpose: gaps are part of the layout.
        cell(grid, "FIELD_BOOLEAN", "DEMO_NEWSLETTER", "Newsletter", GridPosition.cell(2, 1));
        Element callTime = cell(grid, "FIELD_LIST", "DEMO_CONTACT_TIME", "Best time to call", GridPosition.cell(2, 3));
        listOptions(callTime, "MORNING", "Morning", "AFTERNOON", "Afternoon", "EVENING", "Evening");
        Element remarks = cell(grid, "FIELD_TEXTAREA", "DEMO_REMARKS", "Remarks", new GridPosition(3, 1, 2, 3));
        attributes.setValue(remarks.getId(), "MAX_LENGTH", "500");

        Element address = add(applicant, "MAP", "DEMO_ADDRESS", "Address");
        mandatory(add(address, "FIELD_TEXT", "DEMO_STREET", "Street and number"));
        mandatory(add(address, "FIELD_TEXT", "DEMO_POSTCODE", "Postcode"));
        mandatory(add(address, "FIELD_TEXT", "DEMO_CITY", "City"));
        Element country = add(address, "FIELD_LIST", "DEMO_COUNTRY", "Country");
        listOptions(country, "BE", "Belgium", "NL", "Netherlands", "LU", "Luxembourg", "FR", "France", "DE", "Germany");

        // --- Financial situation: a field directly under a section, numbers, a two-column grid ---
        Element finances = add(page, "SECTION", "DEMO_FINANCES", "Financial situation");
        Element employment = add(finances, "FIELD_LIST", "DEMO_EMPLOYMENT", "Employment");
        mandatory(employment);
        listOptions(employment, "EMPLOYED", "Employed", "SELF_EMPLOYED", "Self-employed", "RETIRED", "Retired", "OTHER", "Other");
        Element money = add(finances, "MATRIX", "DEMO_MONEY_GRID", "Monthly amounts");
        Element income = cell(money, "FIELD_NUMBER", "DEMO_INCOME", "Net income", GridPosition.cell(1, 1));
        attributes.setValue(income.getId(), "MIN_VALUE", "0");
        Element costs = cell(money, "FIELD_NUMBER", "DEMO_COSTS", "Fixed costs", GridPosition.cell(1, 2));
        attributes.setValue(costs.getId(), "MIN_VALUE", "0");
        Element amount = add(finances, "FIELD_NUMBER", "DEMO_AMOUNT", "Amount requested");
        mandatory(amount);
        attributes.setValue(amount.getId(), "MIN_VALUE", "1000");
        attributes.setValue(amount.getId(), "MAX_VALUE", "250000");

        // --- Declaration: a collapsed section ------------------------------------------------------
        Element declaration = add(page, "SECTION", "DEMO_DECLARATION", "Declaration");
        attributes.setValue(declaration.getId(), "COLLAPSED", "true");
        Element payslips = add(declaration, "FIELD_DOCUMENT", "DEMO_PAYSLIPS", "Last three payslips");
        help(payslips, "One PDF with all three pages.");
        Element consent = add(declaration, "FIELD_BOOLEAN", "DEMO_CONSENT", "I confirm the information above is correct");
        mandatory(consent);
    }

    private Element add(Element parent, String type, String code, String label) {
        return elements.create(parent == null ? null : parent.getId(), type, code, label, null);
    }

    private Element cell(Element grid, String type, String code, String label, GridPosition position) {
        return elements.create(grid.getId(), type, code, label, null, position);
    }

    private void mandatory(Element field) {
        attributes.setValue(field.getId(), "MANDATORY", "true");
    }

    private void help(Element field, String text) {
        attributes.setValue(field.getId(), "HELP_TEXT", text);
    }

    /** codeAndLabels: code, label, code, label, ... */
    private void listOptions(Element list, String... codeAndLabels) {
        for (int i = 0; i < codeAndLabels.length; i += 2) {
            options.create(list.getId(), codeAndLabels[i], codeAndLabels[i + 1], null, false);
        }
    }
}
