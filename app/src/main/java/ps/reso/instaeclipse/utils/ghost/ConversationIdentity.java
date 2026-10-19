package ps.reso.instaeclipse.utils.ghost;


/** Identity validation only; display names and arbitrary strings are never identifiers. */
public final class ConversationIdentity {
    private ConversationIdentity() {}
    public static boolean validId(String value) {
        return value != null && value.matches("[1-9][0-9]{0,63}");
    }
    public static String fromKeyDescription(String description) {
        if (description == null || description.length() > 512) return null;
        java.util.regex.Matcher match = java.util.regex.Pattern.compile(
                "^DirectThreadKey\\{mThreadId='([1-9][0-9]{0,63})', mThreadV2Id='[^']*'\\}$").matcher(description);
        return match.matches() ? match.group(1) : null;
    }
}
