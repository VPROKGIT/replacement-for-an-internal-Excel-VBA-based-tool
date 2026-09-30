package com.vprok.forms.web.ui;

/** One field of the UI attributes box: a kind, and its value in the selected entry ("" when none is selected or it has none). */
public record UiAttributeRow(String code, String name, String value) {
}
