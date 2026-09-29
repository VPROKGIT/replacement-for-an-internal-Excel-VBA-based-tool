package com.vprok.forms.config;

import static org.springframework.security.config.Customizer.withDefaults;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;

/**
 * Sign-in for a fixed list of users (FORMS-12). Everyone who can sign in may do everything: there
 * is one role, no permissions to tell apart yet.
 *
 * <ul>
 *   <li>{@code /api/**} - the REST API and the JSON export, called by tools rather than browsers:
 *       HTTP Basic on every request, no session, and so no CSRF token to carry.</li>
 *   <li>Everything else - the editor: a login page and a session; every POST carries a CSRF token
 *       (Thymeleaf adds it to each {@code th:action} form).</li>
 * </ul>
 */
@Configuration
@EnableConfigurationProperties(SecurityConfig.FormsSecurityProperties.class)
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    /** {@code forms.security.users}: {@code name:bcrypt-hash[,name:bcrypt-hash...]}, normally from FORMS_SECURITY_USERS. */
    @ConfigurationProperties("forms.security")
    public record FormsSecurityProperties(String users) {
    }

    @Bean
    public UserDetailsService userDetailsService(FormsSecurityProperties properties) {
        List<UserDetails> users = ConfiguredUsers.parse(properties.users());
        List<String> plainText = users.stream()
                .filter(u -> u.getPassword().startsWith("{noop}"))
                .map(UserDetails::getUsername)
                .toList();
        if (!plainText.isEmpty()) {
            log.warn("Users {} have plain-text ({noop}) passwords. That is for local development only.", plainText);
        }
        log.info("{} user(s) may sign in.", users.size());
        return new InMemoryUserDetailsManager(users);
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    @Order(1)
    public SecurityFilterChain apiSecurity(HttpSecurity http) throws Exception {
        http.securityMatcher("/api/**")
                .authorizeHttpRequests(requests -> requests.anyRequest().authenticated())
                .httpBasic(withDefaults())
                // Stateless: a browser's editor session never authenticates an API call, so there
                // is no cookie a cross-site request could ride on, and CSRF has nothing to guard.
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable());
        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain editorSecurity(HttpSecurity http) throws Exception {
        // After sign-in, back to the page that asked for it - without the "?continue" marker Spring
        // Security adds by default, so editor URLs stay clean and bookmarkable.
        HttpSessionRequestCache requestCache = new HttpSessionRequestCache();
        requestCache.setMatchingRequestParameterName(null);
        http.requestCache(cache -> cache.requestCache(requestCache))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/login", "/css/**", "/js/**", "/favicon.ico", "/error").permitAll()
                        .anyRequest().authenticated())
                .formLogin(form -> form.loginPage("/login").defaultSuccessUrl("/ui/pages"))
                .logout(logout -> logout.logoutSuccessUrl("/login?logout"));
        return http.build();
    }
}
