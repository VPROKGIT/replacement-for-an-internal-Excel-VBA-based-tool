package com.vprok.forms.config;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * The fixed list of people who may sign in (FORMS-12), read from one setting so it can be set on
 * a host as a single environment variable: {@code FORMS_SECURITY_USERS=alice:<hash>,bob:<hash>}.
 *
 * <p>Each password is a BCrypt hash ({@code $2a$...}, {@code $2b$...} or {@code $2y$...}), or an
 * explicitly prefixed value that Spring's DelegatingPasswordEncoder understands, such as
 * {@code {bcrypt}...} - or {@code {noop}plain} for local development only. A bare plain-text
 * password is refused, so one can't end up on a host by accident. Everyone gets the same single
 * role: there are no permissions to tell apart yet.
 */
public final class ConfiguredUsers {

    static final String ROLE = "USER";
    private static final int MAX_USERNAME_LENGTH = 100; // element.created_by / updated_by

    private ConfiguredUsers() {
    }

    public static List<UserDetails> parse(String spec) {
        if (spec == null || spec.isBlank()) {
            throw new IllegalStateException("No users are configured, so nobody could sign in. Set FORMS_SECURITY_USERS to "
                    + "name:bcrypt-hash[,name:bcrypt-hash...] (see README), or run locally with --spring.profiles.active=local.");
        }
        List<UserDetails> users = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String[] entries = spec.split(",");
        for (int i = 0; i < entries.length; i++) {
            String trimmed = entries[i].trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int colon = trimmed.indexOf(':');
            if (colon <= 0 || colon == trimmed.length() - 1) {
                // Never echo the entry: it may be a password pasted without its name.
                throw new IllegalStateException("Each user must be written name:password-hash; entry %d is not.".formatted(i + 1));
            }
            String username = trimmed.substring(0, colon).trim();
            String password = trimmed.substring(colon + 1).trim();
            if (username.length() > MAX_USERNAME_LENGTH) {
                throw new IllegalStateException("User name '%s…' is longer than %d characters".formatted(username.substring(0, 20), MAX_USERNAME_LENGTH));
            }
            if (!seen.add(username)) {
                throw new IllegalStateException("User '%s' is configured twice".formatted(username));
            }
            users.add(User.withUsername(username).password(encoded(username, password)).roles(ROLE).build());
        }
        if (users.isEmpty()) {
            return parse(null);
        }
        return users;
    }

    /** The password in DelegatingPasswordEncoder form: {@code {id}value}. */
    private static String encoded(String username, String password) {
        if (password.startsWith("{")) {
            return password;
        }
        if (password.startsWith("$2a$") || password.startsWith("$2b$") || password.startsWith("$2y$")) {
            return "{bcrypt}" + password;
        }
        throw new IllegalStateException(("The password for '%s' is not a BCrypt hash. Hash it first (see README), "
                + "or write {noop}password for local development only.").formatted(username));
    }
}
