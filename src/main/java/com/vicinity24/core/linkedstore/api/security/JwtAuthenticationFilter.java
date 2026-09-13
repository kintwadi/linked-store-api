package com.vicinity24.core.linkedstore.api.security;

import com.vicinity24.core.linkedstore.api.entity.UserRole;
import com.vicinity24.core.linkedstore.api.service.JwtService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    private static final String BEARER = "Bearer ";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || authHeader.isBlank() || !authHeader.startsWith(BEARER)) {
            filterChain.doFilter(request, response);
            return;
        }
        String token = authHeader.substring(BEARER.length()).trim();
        try {
            Claims claims = jwtService.parseAccess(token);
            CurrentUser current = buildCurrent(claims);
            List<SimpleGrantedAuthority> authorities = buildAuthorities(current);
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    current, null, authorities
            );
            SecurityContextHolder.getContext().setAuthentication(authentication);
        } catch (JwtException | IllegalArgumentException ex) {
            log.debug("Ignoring invalid JWT on {} {}: {}", request.getMethod(), request.getRequestURI(), ex.getMessage());
            SecurityContextHolder.clearContext();
        }
        filterChain.doFilter(request, response);
    }

    private static CurrentUser buildCurrent(Claims claims) {
        String subject = claims.getSubject();
        UUID userId = subject == null ? null : UUID.fromString(subject);
        String email = claims.get(JwtService.CLAIM_EMAIL, String.class);
        String storeStr = claims.get(JwtService.CLAIM_STORE_ID, String.class);
        UUID storeId = storeStr == null ? null : UUID.fromString(storeStr);
        String roleStr = claims.get(JwtService.CLAIM_ROLE, String.class);
        UserRole role;
        try { role = roleStr == null ? UserRole.CLERK : UserRole.valueOf(roleStr); }
        catch (IllegalArgumentException ignore) { role = UserRole.CLERK; }
        Boolean global = claims.get(JwtService.CLAIM_IS_GLOBAL_ADMIN, Boolean.class);
        boolean isGlobal = Boolean.TRUE.equals(global);
        return CurrentUser.builder()
                .userId(userId)
                .email(email)
                .storeId(storeId)
                .role(role)
                .globalAdmin(isGlobal)
                .authenticated(true)
                .build();
    }

    private static List<SimpleGrantedAuthority> buildAuthorities(CurrentUser u) {
        boolean effectiveGlobal = u.isGlobalAdmin() || u.getRole() == UserRole.GLOBAL_ADMIN;
        String role = effectiveGlobal ? "ROLE_GLOBAL_ADMIN" : "ROLE_" + u.roleName();
        return List.of(
                new SimpleGrantedAuthority("ROLE_USER"),
                new SimpleGrantedAuthority(role)
        );
    }
}
