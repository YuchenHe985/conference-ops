package com.yuchenhe.conference.security;

import static org.springframework.http.HttpMethod.GET;

import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Stateless HTTP Basic over TLS (terminated in front of the service). CSRF protection is off
 * because no browser session or cookie is ever issued. Rules are deny-by-default: anything not
 * listed needs the ORGANIZER role.
 */
@Configuration
@org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
public class SecurityConfig {

  @Bean
  SecurityFilterChain api(HttpSecurity http) throws Exception {
    http.csrf(c -> c.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .httpBasic(Customizer.withDefaults())
        .authorizeHttpRequests(
            a ->
                a.requestMatchers(
                        "/actuator/health/**",
                        "/v3/api-docs/**",
                        "/swagger-ui/**",
                        "/swagger-ui.html")
                    .permitAll()
                    .requestMatchers(GET, "/api/sessions", "/api/sessions/{id}")
                    .authenticated()
                    .requestMatchers("/api/sessions/{id}/registrations", "/api/registrations/**")
                    .authenticated()
                    .requestMatchers(GET, "/api/me/registrations")
                    .authenticated()
                    .anyRequest()
                    .hasRole("ORGANIZER"));
    return http.build();
  }

  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  @Bean
  UserDetailsService users(AppUserRepository repo) {
    return username ->
        repo.findByUsername(username.toLowerCase())
            .map(
                u ->
                    new Caller(
                        u.getUsername(),
                        u.getPasswordHash(),
                        List.of(new SimpleGrantedAuthority("ROLE_" + u.getRole().name())),
                        u.getRole(),
                        u.getAttendeeId()))
            .orElseThrow(() -> new UsernameNotFoundException("unknown user"));
  }
}
