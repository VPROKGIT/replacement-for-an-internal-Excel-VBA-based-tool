package com.vprok.forms.service;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * A UI attribute entry as read: its id and its non-empty values, in the kinds' display order.
 * Plain data, so it can be used after the transaction that loaded it has closed.
 */
public record UiAttributeEntryView(Long id, List<Value> values) {

    public record Value(String code, String value) {
    }

    /** Values keyed by kind code, in display order. */
    public Map<String, String> valuesByCode() {
        Map<String, String> byCode = new LinkedHashMap<>();
        values.forEach(v -> byCode.put(v.code(), v.value()));
        return byCode;
    }

    /** The editor's one-line summary, e.g. {@code TAR_VAL "email" ; ACT_TYP "VALIDATE"}. */
    public String summary() {
        return values.stream()
                .map(v -> abbreviate(v.code()) + " \"" + v.value() + "\"")
                .collect(Collectors.joining(" ; "));
    }

    /**
     * The first three letters of each underscore-separated word: {@code TARGET_VALUE -> TAR_VAL},
     * {@code UI_PARAMETER -> UI_PAR}. A rule rather than a lookup table, so a kind added as a seed
     * row gets its abbreviation without a code change.
     */
    public static String abbreviate(String code) {
        return Arrays.stream(code.split("_"))
                .filter(word -> !word.isEmpty())
                .map(word -> word.length() <= 3 ? word : word.substring(0, 3))
                .collect(Collectors.joining("_"));
    }
}
