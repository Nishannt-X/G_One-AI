package com.example.g_one;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Refuses to start when G_ONE_ACCESS_CODE is set but blank while the former AISETU_ACCESS_CODE holds a real code.
 * The new name wins even when blank, so that mix would silently switch the room access check off; failing closed
 * makes the person delete the old line or fill in the new one. The values are never put in the message.
 */
@Component
class AccessCodeGuard {
    AccessCodeGuard(Environment env) {
        verify(env);
    }

    static void verify(Environment env) {
        String current = env.getProperty("G_ONE_ACCESS_CODE");
        String former = env.getProperty("AISETU_ACCESS_CODE");
        if (current != null && current.isBlank() && former != null && !former.isBlank()) {
            throw new IllegalStateException("G_ONE_ACCESS_CODE is blank but AISETU_ACCESS_CODE is set; that would turn"
                    + " the room access check off. Put the access code in G_ONE_ACCESS_CODE and delete"
                    + " AISETU_ACCESS_CODE, or unset the blank G_ONE_ACCESS_CODE.");
        }
    }
}
