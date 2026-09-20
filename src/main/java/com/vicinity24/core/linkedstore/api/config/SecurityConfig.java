package com.vicinity24.core.linkedstore.api.config;

import com.vicinity24.core.linkedstore.api.security.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.GET, "/api/auth/invites/*/preview").permitAll()
                .requestMatchers("/api/auth/**").permitAll()
                .requestMatchers("/api/public/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/stores", "/api/products", "/api/products/**").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/reservations", "/api/checkout/**",
                        "/api/pickup/verify", "/api/transactions/*/mark-paid").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/connect/webhook").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/connect/health").permitAll()
                .requestMatchers("/sse/**", "/sse/*/**",
                        "/api/admin/sse/**",
                        "/api/stores/*/sse/**",
                        "/api/stores/me/sse/**").hasAnyRole(
                        "GLOBAL_ADMIN", "OWNER", "STORE_ADMIN", "STORE_REPRESENTATIVE", "CLERK", "RUNNER")
                .requestMatchers("/api/admin/stores/me", "/api/admin/stores/me/**").hasAnyRole(
                        "GLOBAL_ADMIN", "OWNER", "STORE_ADMIN", "STORE_REPRESENTATIVE")
                .requestMatchers(HttpMethod.GET, "/api/admin/stores/*").hasAnyRole(
                        "GLOBAL_ADMIN", "OWNER", "STORE_ADMIN", "STORE_REPRESENTATIVE")
                .requestMatchers(HttpMethod.GET, "/api/admin/stores/*/inventory",
                        "/api/admin/stores/*/inventory/**").hasAnyRole(
                        "GLOBAL_ADMIN", "OWNER", "STORE_ADMIN", "STORE_REPRESENTATIVE", "CLERK", "RUNNER")
                .requestMatchers(HttpMethod.POST, "/api/admin/stores/*/inventory/*/share-code").hasAnyRole(
                        "GLOBAL_ADMIN", "OWNER", "STORE_ADMIN", "STORE_REPRESENTATIVE", "CLERK", "RUNNER")
                .requestMatchers(HttpMethod.POST, "/api/admin/stores/*/inventory",
                        "/api/admin/stores/*/inventory/**").hasAnyRole(
                        "GLOBAL_ADMIN", "OWNER", "STORE_ADMIN", "STORE_REPRESENTATIVE")
                .requestMatchers(HttpMethod.PUT, "/api/admin/stores/*/inventory/**").hasAnyRole(
                        "GLOBAL_ADMIN", "OWNER", "STORE_ADMIN", "STORE_REPRESENTATIVE")
                .requestMatchers(HttpMethod.DELETE, "/api/admin/stores/*/inventory/**").hasAnyRole(
                        "GLOBAL_ADMIN", "OWNER", "STORE_ADMIN", "STORE_REPRESENTATIVE")
                .requestMatchers(HttpMethod.POST, "/api/admin/stores/*/connect/**").hasAnyRole(
                        "GLOBAL_ADMIN", "OWNER", "STORE_ADMIN")
                .requestMatchers(HttpMethod.GET, "/api/admin/stores/*/connect/**").hasAnyRole(
                        "GLOBAL_ADMIN", "OWNER", "STORE_ADMIN")
                .requestMatchers(HttpMethod.GET, "/api/admin/stores/inventory/all").hasAnyRole(
                        "GLOBAL_ADMIN", "OWNER", "STORE_ADMIN", "STORE_REPRESENTATIVE", "CLERK", "RUNNER")
                .requestMatchers(HttpMethod.GET, "/api/admin/stores").hasAnyRole(
                        "GLOBAL_ADMIN", "OWNER", "STORE_ADMIN", "STORE_REPRESENTATIVE", "CLERK", "RUNNER")
                .requestMatchers("/api/admin/stores/**").hasRole("GLOBAL_ADMIN")
                .requestMatchers("/api/admin/transactions/**").hasAnyRole(
                        "GLOBAL_ADMIN", "OWNER", "STORE_ADMIN", "STORE_REPRESENTATIVE")
                .requestMatchers("/api/admin/users/**").hasAnyRole(
                        "GLOBAL_ADMIN", "OWNER", "STORE_ADMIN")
                .requestMatchers("/api/connect/**").hasAnyRole("GLOBAL_ADMIN", "OWNER", "STORE_ADMIN")
                .requestMatchers("/api/fulfillment/**", "/api/inventory/**")
                    .hasAnyRole("GLOBAL_ADMIN", "OWNER", "STORE_ADMIN", "STORE_REPRESENTATIVE", "CLERK", "RUNNER")
                .requestMatchers("/actuator/**").denyAll()
                .anyRequest().permitAll()
            );
        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(List.of("*"));
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setExposedHeaders(Arrays.asList("Content-Type", "X-Request-Id", "Idempotency-Key", "Authorization"));
        configuration.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration cfg) throws Exception {
        return cfg.getAuthenticationManager();
    }
}
