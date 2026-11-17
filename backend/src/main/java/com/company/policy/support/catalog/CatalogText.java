package com.company.policy.support.catalog;

import java.text.Normalizer;
import java.text.Normalizer.Form;
import java.util.regex.Pattern;

public final class CatalogText {
    private static final Pattern SPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

    private CatalogText() {}

    public static String normalize(String value) {
        return value == null
                ? ""
                : SPACE.matcher(Normalizer.normalize(value, Form.NFKC)).replaceAll(" ").strip();
    }
}
