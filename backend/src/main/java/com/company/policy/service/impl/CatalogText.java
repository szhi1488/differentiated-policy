package com.company.policy.service.impl;

import java.text.Normalizer;
import java.text.Normalizer.Form;
import java.util.regex.Pattern;

final class CatalogText {
    private static final Pattern SPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

    private CatalogText() {}

    static String normalize(String value) {
        return value == null
                ? ""
                : SPACE.matcher(Normalizer.normalize(value, Form.NFKC)).replaceAll(" ").strip();
    }
}
