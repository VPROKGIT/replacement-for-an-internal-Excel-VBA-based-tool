package com.vprok.forms.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.vprok.forms.service.UiAttributeEntryView.Value;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The editor's one-line summary of a UI attribute entry (FORMS-22). */
class UiAttributeEntryViewTest {

    @Test
    void abbreviatesEachWordToItsFirstThreeLetters() {
        assertThat(UiAttributeEntryView.abbreviate("TARGET_VALUE")).isEqualTo("TAR_VAL");
        assertThat(UiAttributeEntryView.abbreviate("ACTION_TYPE")).isEqualTo("ACT_TYP");
        // Words of three letters or fewer stay whole.
        assertThat(UiAttributeEntryView.abbreviate("UI_PARAMETER")).isEqualTo("UI_PAR");
        assertThat(UiAttributeEntryView.abbreviate("TARGET_NODE_DEF_ID")).isEqualTo("TAR_NOD_DEF_ID");
        assertThat(UiAttributeEntryView.abbreviate("TARGET_AVAILABLE_FIELD_INDEX")).isEqualTo("TAR_AVA_FIE_IND");
    }

    @Test
    void summaryListsTheEntrysValuesInOrderSeparatedBySemicolons() {
        UiAttributeEntryView entry = new UiAttributeEntryView(1L, List.of(
                new Value("TARGET_VALUE", "email"),
                new Value("ACTION_TYPE", "VALIDATE")));

        assertThat(entry.summary()).isEqualTo("TAR_VAL \"email\" ; ACT_TYP \"VALIDATE\"");
    }

    @Test
    void aSingleValueHasNoSeparator() {
        UiAttributeEntryView entry = new UiAttributeEntryView(1L, List.of(new Value("UI_LABEL", "Name")));

        assertThat(entry.summary()).isEqualTo("UI_LAB \"Name\"");
    }
}
