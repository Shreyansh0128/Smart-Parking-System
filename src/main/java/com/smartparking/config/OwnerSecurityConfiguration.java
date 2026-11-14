package com.smartparking.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

@Configuration
public class OwnerSecurityConfiguration {
    @Bean
    public UserDetailsService ownerUserDetailsService(
            @Value("${parking.owner.username:owner}") String username,
            @Value("${parking.owner.password-hash:}") String passwordHash) {
        if (username == null || username.isBlank() || passwordHash == null || !passwordHash.startsWith("$2"))
            throw new IllegalStateException("Configure PARKING_OWNER_PASSWORD_HASH with a BCrypt password hash");
        return new InMemoryUserDetailsManager(User.withUsername(username.trim())
                .password(passwordHash)
                .roles("OWNER")
                .build());
    }

    @Bean
    public PasswordEncoder ownerPasswordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain ownerSecurityFilterChain(HttpSecurity http) throws Exception {
        CookieCsrfTokenRepository csrfRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfRepository.setCookiePath("/");
        return http
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/style.css", "/script.js", "/api/health").permitAll()
                        .anyRequest().hasRole("OWNER"))
                .formLogin(form -> form.permitAll())
                .logout(logout -> logout.logoutUrl("/api/auth/logout")
                        .logoutSuccessHandler((request, response, authentication) -> response.setStatus(204)))
                .csrf(csrf -> csrf.csrfTokenRepository(csrfRepository))
                .build();
    }
}
