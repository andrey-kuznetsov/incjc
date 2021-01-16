package incjc;

import com.google.common.collect.Sets;

public abstract class Debug {
    public static final boolean DEBUG_ENABLED = Sets.newHashSet("1", "true", "TRUE", "yes", "Y").contains(System.getenv("INCJC_DEBUG"));

    public static void debug(String msg) {
        if (DEBUG_ENABLED) {
            System.out.println("[DEBUG] " + msg);
        }
    }
}
