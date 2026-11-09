package com.notraius.config.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.primarion.module.security.auth.filter.key.AppKeyAuthenticationFilter;
import org.primarion.module.security.auth.filter.key.PublicKeyValidationFilter;
import org.primarion.module.security.auth.filter.key.ServiceKeyAuthenticationFilter;
import org.primarion.module.security.auth.filter.SessionPropagationFilter;
import org.primarion.module.security.auth.filter.SignedTokenAuthenticationFilter;
import org.primarion.module.security.auth.session.SessionContextManager;
import jakarta.servlet.http.HttpServletResponse;
import org.primarion.module.security.auth.session.SecurityProperties;
import org.primarion.module.security.auth.door.DoorRegistry;
import org.primarion.module.security.auth.door.InfraPublicPaths;
import org.primarion.module.utils.security.users.SessionIntrospector;
import org.primarion.module.security.crypto.key.Keyring;
import org.primarion.module.security.ratelimit.redis.HttpSecurityHeadersFilter;
import org.primarion.module.utils.security.users.UserService;
import org.primarion.module.app.database.repositories.AppKeyRepository;
import org.primarion.module.app.database.repositories.AppRepository;
import org.primarion.module.app.service.registry.PlatformRepositories;
import org.primarion.module.security.token.database.repositories.TokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.core.GrantedAuthorityDefaults;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfigurationSource;
import java.util.List;
import java.util.function.Consumer;
import org.primarion.module.utils.security.identity.AppIdentityProperties;
import org.primarion.module.app.service.registry.AppStatusResolver;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Bean
    public SignedTokenAuthenticationFilter signedTokenAuthenticationFilter(SessionContextManager sessionContextManager,
                                                                           SessionIntrospector sessionIntrospector) {
        // #AUTH-CENTRAL: hosts authenticate by ASKING primarion (the identity
        // authority) — no shared HMAC signing-key trust on this host.
        return new SignedTokenAuthenticationFilter(sessionContextManager, sessionIntrospector);
    }

    @Bean
    public AppKeyAuthenticationFilter appKeyAuthenticationFilter(AppKeyRepository appKeyRepository,
                                                                 AppRepository appRepository,
                                                                 TokenRepository tokenRepository,
                                                                 SessionContextManager sessionContextManager,
                                                                 AppStatusResolver appStatusResolver) {
        return new AppKeyAuthenticationFilter(appKeyRepository, appRepository, tokenRepository,
                sessionContextManager, appStatusResolver);
    }

    @Bean
    public ServiceKeyAuthenticationFilter serviceKeyAuthenticationFilter(PlatformRepositories.PlatformTokenRepository platformTokenRepository,
                                                                         PlatformRepositories.PlatformAppKeyRepository platformAppKeyRepository,
                                                                         PlatformRepositories.PlatformAppRepository platformAppRepository,
                                                                         SessionContextManager sessionContextManager,
                                                                         AppStatusResolver appStatusResolver) {
        return new ServiceKeyAuthenticationFilter(platformTokenRepository, platformAppKeyRepository, platformAppRepository,
                sessionContextManager, appStatusResolver);
    }

    @Bean
    public SessionPropagationFilter sessionPropagationFilter(SessionContextManager sessionContextManager,
                                                             SessionIntrospector sessionIntrospector) {
        // #AUTH-CENTRAL: X-Session validity is decided by primarion
        // introspection, never by a locally verified shared-key signature.
        return new SessionPropagationFilter(sessionContextManager, sessionIntrospector);
    }

    @Bean
    public PublicKeyValidationFilter publicKeyValidationFilter(PlatformRepositories.PlatformTokenRepository platformTokenRepository,
                                                              PlatformRepositories.PlatformAppKeyRepository platformAppKeyRepository,
                                                              PlatformRepositories.PlatformAppRepository platformAppRepository,
                                                              TokenRepository tokenRepository,
                                                              AppKeyRepository appKeyRepository,
                                                              AppRepository appRepository,
                                                              SecurityProperties securityProperties,
                                                              DoorRegistry doorRegistry,
                                                              AppIdentityProperties appIdentityProperties,
                                                              Keyring encryptionKeyring,
                                                              AppStatusResolver appStatusResolver,
                                                              @Qualifier("revokedKeyListeners")
                                                              List<Consumer<String>> revokedKeyListeners) {
        PublicKeyValidationFilter filter = new PublicKeyValidationFilter(platformTokenRepository, platformAppKeyRepository, platformAppRepository,
                tokenRepository, appKeyRepository, appRepository,
                securityProperties, doorRegistry, appIdentityProperties, encryptionKeyring,
                appStatusResolver);
        // Immediate cache invalidation when any key service soft-revokes a key
        revokedKeyListeners.add(filter::invalidate);
        return filter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   SecurityProperties securityProperties,
                                                   DoorRegistry doorRegistry,
                                                   HttpSecurityHeadersFilter httpSecurityHeadersFilter,
                                                   PublicKeyValidationFilter publicKeyValidationFilter,
                                                   SignedTokenAuthenticationFilter signedTokenAuthenticationFilter,
                                                   AppKeyAuthenticationFilter appKeyAuthenticationFilter,
                                                   ServiceKeyAuthenticationFilter serviceKeyAuthenticationFilter,
                                                   SessionPropagationFilter sessionPropagationFilter,
                                                   CorsConfigurationSource corsConfigurationSource) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(publicKeyValidationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(appKeyAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(serviceKeyAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(sessionPropagationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(signedTokenAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(httpSecurityHeadersFilter, UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> {
                    // Infrastructure surface is the only residual allowlist: actuator,
                    // API docs and Swagger assets have no MVC handler to declare themselves.
                    auth.requestMatchers(InfraPublicPaths.matcher()).permitAll();
                    // Doors are declared ON the handler. UNAUTHENTICATED = no credential at
                    // all; ANONYMOUS = validated public key, no session.
                    auth.requestMatchers(doorRegistry.unauthenticatedMatcher()).permitAll();
                    auth.requestMatchers(doorRegistry.anonymousMatcher())
                            .access((authentication, object) -> new AuthorizationDecision(
                                    object.getRequest().getAttribute(
                                            PublicKeyValidationFilter.VALIDATED_PUBLIC_KEY_ATTR) != null));
                    // Everything else needs a session / app / service identity. A named
                    // handler inside a public wildcard wins on specificity, so it answers
                    // 401 (not 403) to a caller who is merely unauthenticated.
                    auth.anyRequest().authenticated();
                })
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) -> {
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                            response.getWriter().write("{\"error\":\"UNAUTHORIZED\",\"message\":\"Authentication required\"}");
                        })
                        .accessDeniedHandler((request, response, accessDeniedException) -> {
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                            response.getWriter().write("{\"error\":\"FORBIDDEN\",\"message\":\"Access denied\"}");
                        })
                );
        return http.build();
    }

    @Bean
    public UserDetailsService userDetailsService() {
        return username -> {
            throw new UsernameNotFoundException("Notraius uses signed bearer tokens - not Spring Security UserDetailsService");
        };
    }

    @Bean
    public GrantedAuthorityDefaults grantedAuthorityDefaults() {
        return new GrantedAuthorityDefaults("");
    }
}
