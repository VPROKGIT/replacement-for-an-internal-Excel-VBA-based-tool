package com.vprok.forms.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

/** The FORMS_SECURITY_USERS format: name:hash pairs, BCrypt hashes, and loud failure otherwise. */
class ConfiguredUsersTest {

    private final PasswordEncoder encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();

    @Test
    void readsNamesAndBcryptHashesInEveryCommonForm() {
        String hash = new BCryptPasswordEncoder().encode("s3cret");
        String htpasswdStyle = "$2y$" + hash.substring(4); // what `htpasswd -B` prints
        List<UserDetails> users = ConfiguredUsers.parse(" alice:" + hash + " , bob:{bcrypt}" + hash + ",carol:" + htpasswdStyle + ",");

        assertThat(users).extracting(UserDetails::getUsername).containsExactly("alice", "bob", "carol");
        assertThat(users).allSatisfy(user -> {
            assertThat(encoder.matches("s3cret", user.getPassword())).isTrue();
            assertThat(encoder.matches("wrong", user.getPassword())).isFalse();
            assertThat(user.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_USER");
        });
    }

    @Test
    void acceptsAnExplicitNoopPasswordForLocalDevelopment() {
        UserDetails dev = ConfiguredUsers.parse("dev:{noop}dev").get(0);
        assertThat(encoder.matches("dev", dev.getPassword())).isTrue();
    }

    @Test
    void refusesAnythingUnclearAndNeverEchoesAPassword() {
        assertThatThrownBy(() -> ConfiguredUsers.parse("")).hasMessageContaining("No users are configured");
        assertThatThrownBy(() -> ConfiguredUsers.parse(" , ")).hasMessageContaining("No users are configured");
        assertThatThrownBy(() -> ConfiguredUsers.parse("alice:plaintext"))
                .hasMessageContaining("not a BCrypt hash")
                .hasMessageNotContaining("plaintext");
        assertThatThrownBy(() -> ConfiguredUsers.parse("alice:{noop}a,alice:{noop}b")).hasMessageContaining("configured twice");
        assertThatThrownBy(() -> ConfiguredUsers.parse("$2a$10$pastedWithoutAName"))
                .hasMessageContaining("entry 1")
                .hasMessageNotContaining("pastedWithoutAName");
        assertThatThrownBy(() -> ConfiguredUsers.parse("alice:")).hasMessageContaining("entry 1");
    }
}
