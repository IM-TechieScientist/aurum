package dev.aurum.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;

@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfiguration {

    private static final String CUSTOMER = "CUSTOMER";
    private static final String OPERATOR = "OPERATOR";
    private static final String AUDITOR = "AUDITOR";
    private static final String ADMIN = "ADMIN";

    private final ObjectMapper objectMapper;

    public SecurityConfiguration(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info")
                            .permitAll()
                        .requestMatchers("/actuator/metrics", "/actuator/metrics/**")
                            .hasAnyRole(AUDITOR, OPERATOR, ADMIN)
                        .requestMatchers(HttpMethod.POST, "/api/v1/reconciliation/rebuild")
                            .hasAnyRole(OPERATOR, ADMIN)
                        .requestMatchers(HttpMethod.GET,
                                "/api/v1/reconciliation", "/api/v1/reconciliation/**")
                            .hasAnyRole(AUDITOR, OPERATOR, ADMIN)
                        .requestMatchers(HttpMethod.PATCH,
                                "/api/v1/accounts/*/freeze", "/api/v1/accounts/*/unfreeze")
                            .hasAnyRole(OPERATOR, ADMIN)
                        .requestMatchers(HttpMethod.POST, "/api/v1/accounts")
                            .hasAnyRole(OPERATOR, ADMIN)
                        .requestMatchers(HttpMethod.POST, "/api/v1/accounts/*/fund")
                            .hasAnyRole(OPERATOR, ADMIN)
                        .requestMatchers(HttpMethod.POST, "/api/v1/transactions/*/reversal")
                            .hasAnyRole(OPERATOR, ADMIN)
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/accounts/*/withdraw", "/api/v1/transfers")
                            .hasAnyRole(CUSTOMER, OPERATOR, ADMIN)
                        .requestMatchers(HttpMethod.GET, "/api/v1/**")
                            .hasAnyRole(CUSTOMER, AUDITOR, OPERATOR, ADMIN)
                        .requestMatchers("/api/v1/**").denyAll()
                        .anyRequest().denyAll())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) ->
                                writeProblem(response, HttpStatus.UNAUTHORIZED,
                                        "AUTHENTICATION_REQUIRED", "Authentication is required"))
                        .accessDeniedHandler((request, response, exception) ->
                                writeProblem(response, HttpStatus.FORBIDDEN,
                                        "ACCESS_DENIED", "The authenticated role cannot perform this operation")))
                .httpBasic(Customizer.withDefaults())
                .build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }

    @Bean
    UserDetailsService users(
            PasswordEncoder encoder,
            @Value("${aurum.security.users.customer.username:customer}") String customerUsername,
            @Value("${aurum.security.users.customer.password:customer-local}") String customerPassword,
            @Value("${aurum.security.users.operator.username:operator}") String operatorUsername,
            @Value("${aurum.security.users.operator.password:operator-local}") String operatorPassword,
            @Value("${aurum.security.users.auditor.username:auditor}") String auditorUsername,
            @Value("${aurum.security.users.auditor.password:auditor-local}") String auditorPassword,
            @Value("${aurum.security.users.admin.username:admin}") String adminUsername,
            @Value("${aurum.security.users.admin.password:admin-local}") String adminPassword) {
        return new InMemoryUserDetailsManager(
                User.withUsername(customerUsername).password(encoder.encode(customerPassword)).roles(CUSTOMER).build(),
                User.withUsername(operatorUsername).password(encoder.encode(operatorPassword)).roles(OPERATOR).build(),
                User.withUsername(auditorUsername).password(encoder.encode(auditorPassword)).roles(AUDITOR).build(),
                User.withUsername(adminUsername).password(encoder.encode(adminPassword)).roles(ADMIN).build());
    }

    private void writeProblem(HttpServletResponse response, HttpStatus status,
                              String code, String message) throws IOException {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(status, message);
        detail.setTitle(code);
        detail.setType(URI.create("https://aurum.dev/problems/" + code.toLowerCase().replace('_', '-')));
        detail.setProperty("code", code);
        detail.setProperty("timestamp", Instant.now());
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        if (status == HttpStatus.UNAUTHORIZED) {
            response.setHeader("WWW-Authenticate", "Basic realm=\"Aurum\"");
        }
        objectMapper.writeValue(response.getOutputStream(), detail);
    }
}
