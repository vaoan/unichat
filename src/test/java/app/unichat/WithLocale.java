package app.unichat;

import java.util.Locale;
import java.util.function.Supplier;

/** Runs a check as on a computer set to another language, then puts the JVM's own back. */
public final class WithLocale {
    /** Turkish: "I".toLowerCase() is a dotless "ı" there. */
    public static final Locale TURKISH = Locale.forLanguageTag("tr");
    /** Persian: a number formatted by default comes out in Persian digits ("۸۰۰۸"). */
    public static final Locale PERSIAN = Locale.forLanguageTag("fa");

    private WithLocale() {}

    public static <T> T get(Locale locale, Supplier<T> check) {
        Locale was = Locale.getDefault();
        try {
            Locale.setDefault(locale);
            return check.get();
        } finally {
            Locale.setDefault(was);
        }
    }
}
