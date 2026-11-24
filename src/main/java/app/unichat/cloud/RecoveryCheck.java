package app.unichat.cloud;

import org.jspecify.annotations.Nullable;

/**
 * Whether the person kept the recovery code just shown: they type two of its groups back, forgiven as the code itself
 * is when typed (case, spaces, O for 0, I or L for 1).
 */
public final class RecoveryCheck {
    /** Groups in a code, counted from 1. */
    public static final int GROUPS = RecoveryCode.GROUPS;

    private RecoveryCheck() {}

    /** Whether {@code typed} is group {@code group} (from 1) of {@code code}, as shown; false for anything that isn't a code. */
    public static boolean matches(String code, int group, @Nullable String typed) {
        RecoveryCode c = RecoveryCode.parse(code);
        if (c == null || typed == null || group < 1 || group > GROUPS) return false;
        return c.group(group).equals(RecoveryCode.normalized(typed));
    }
}
