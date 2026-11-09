package com.notraius.config.web;

import org.junit.jupiter.api.Test;
import org.primarion.module.configs.PublicOperationMatcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The anonymous read tier is three agreements, and this test holds all three
 * together so they cannot drift apart silently:
 * <ol>
 *   <li>the path is named in {@code PUBLIC_GET_ENDPOINTS} (.env.example) — the
 *       filter lets it through without an {@code X-Service-Public-Key};</li>
 *   <li>the servlet chain permits it for GET — every listed path must reach a
 *       real handler, so a stale entry is caught here;</li>
 *   <li>the handler carries <b>no</b> {@code @PreAuthorize} — with no session
 *       there is no principal for an authority gate to resolve, so an
 *       annotation on an anonymous door can only deny the visitor again
 *       (or fail the boot: {@code PermissionSeeder} refuses an authority code
 *       that has no catalog row).</li>
 * </ol>
 * It also enforces the 401 rule: a path that is <i>not</i> public but sits
 * inside a public wildcard must be named in {@code AUTHENTICATED_GET_ENDPOINTS},
 * otherwise the chain permits it and method security answers 403 to a caller
 * who is merely unauthenticated.
 * <p>
 * Sources are read, not models: the annotations in the controllers are the
 * truth this test compares the environment against.
 */
class PublicSurfaceTest {

    private static final Path SOURCES = Path.of("src/main/java");
    private static final Path ENV = Path.of(".env.example");
    private static final String OWN_PREFIX = "/api/v1/notraius/";

    private static final Pattern MAPPING =
            Pattern.compile("@(Get|Post|Put|Patch|Delete)Mapping(?:\\s*\\(([^)]*)\\))?");
    private static final Pattern CLASS_MAPPING =
            Pattern.compile("@RequestMapping\\s*\\(\\s*\"([^\"]*)\"");
    private static final Pattern QUOTED = Pattern.compile("\"([^\"]*)\"");

    @Test
    void everyAnonymousGetHandlerIsListedInTheEnvironment() throws IOException {
        List<Handler> publicGets = handlers().stream()
                .filter(Handler::get)
                .filter(h -> !h.guarded())
                .filter(h -> h.path().startsWith(OWN_PREFIX))
                .toList();
        List<String> configured = envList("PUBLIC_GET_ENDPOINTS").stream()
                .filter(p -> p.startsWith(OWN_PREFIX))
                .toList();

        // Anti-vacuity: a parser that stopped finding handlers must fail loudly.
        assertTrue(publicGets.size() >= 20,
                "expected the public read tier to still be ~23 handlers, found " + publicGets.size());

        for (Handler handler : publicGets) {
            assertTrue(configured.stream().anyMatch(p -> PublicOperationMatcher.matches(p, handler.path())),
                    handler + " has no @PreAuthorize (so it IS anonymous) but is not in "
                            + "PUBLIC_GET_ENDPOINTS — either list it or annotate it");
        }
    }

    @Test
    void everyListedGetPathReachesAPublicHandler() throws IOException {
        List<Handler> gets = handlers().stream().filter(Handler::get).toList();
        List<String> configured = envList("PUBLIC_GET_ENDPOINTS").stream()
                .filter(p -> p.startsWith(OWN_PREFIX))
                .toList();

        for (String pattern : configured) {
            List<Handler> matching = gets.stream()
                    .filter(h -> PublicOperationMatcher.matches(pattern, h.path()))
                    .toList();
            assertFalse(matching.isEmpty(),
                    pattern + " is listed as public but reaches no handler — a stale entry");
            assertTrue(matching.stream().anyMatch(h -> !h.guarded()),
                    pattern + " is listed as public but every handler it reaches has "
                            + "@PreAuthorize, so anonymous callers are still denied");
        }
    }

    @Test
    void aProtectedSiblingInsideAPublicWildcardIsNamedAsAuthenticated() throws IOException {
        List<String> publicPatterns = envList("PUBLIC_GET_ENDPOINTS").stream()
                .filter(p -> p.startsWith(OWN_PREFIX))
                .toList();
        List<String> authenticated = envList("AUTHENTICATED_GET_ENDPOINTS");

        for (Handler handler : handlers()) {
            if (!handler.get() || !handler.guarded() || !handler.path().startsWith(OWN_PREFIX)) {
                continue;
            }
            boolean swallowed = publicPatterns.stream()
                    .anyMatch(p -> PublicOperationMatcher.matches(p, handler.path()));
            if (!swallowed) {
                continue;
            }
            assertTrue(authenticated.contains(handler.path()),
                    handler + " sits inside a public wildcard and keeps its @PreAuthorize, but is not "
                            + "in AUTHENTICATED_GET_ENDPOINTS — an unauthenticated caller would get 403 "
                            + "instead of 401");
        }
    }

    @Test
    void noHandlerDeclaresAnAuthorityForAnonymousCallers() throws IOException {
        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                assertFalse(source.contains("UNAUTHENTICATED"),
                        file + " names an anonymous authority tier. Anonymous means no @PreAuthorize "
                                + "at all: there is no principal to resolve, and an authority code "
                                + "without a catalog row fails the boot.");
            }
        }
    }

    @Test
    void theParserSeesTheWholeSurface() throws IOException {
        List<Handler> all = handlers();
        assertTrue(all.size() >= 120,
                "expected the whole controller surface (~150 handlers), parsed " + all.size());
        assertTrue(all.stream().anyMatch(Handler::guarded),
                "no @PreAuthorize was detected at all — the parser is broken, not the code");
        assertTrue(all.stream().anyMatch(h -> !h.guarded()),
                "every handler looks guarded — the parser is broken, not the code");
    }

    // ── source parsing ────────────────────────────────────────────────────

    private record Handler(String source, String path, String method, boolean guarded) {
        boolean get() {
            return "GET".equals(method);
        }

        @Override
        public String toString() {
            return method + " " + path + " (" + source + ")";
        }
    }

    private static List<Handler> handlers() throws IOException {
        List<Handler> handlers = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file : files.filter(p -> p.toString().endsWith("Controller.java")).toList()) {
                String source = Files.readString(file);
                String base = classBasePath(source);
                Matcher mapping = MAPPING.matcher(source);
                while (mapping.find()) {
                    String own = extractPath(mapping.group(2));
                    handlers.add(new Handler(file.getFileName().toString(),
                            join(base, own), mapping.group(1).toUpperCase(), guarded(source, mapping)));
                }
            }
        }
        return handlers;
    }

    /**
     * A handler is guarded when a {@code @PreAuthorize} sits in its own
     * annotation block — from the previous member boundary to the method's
     * opening brace — whichever side of the mapping annotation it is on.
     */
    private static boolean guarded(String source, Matcher mapping) {
        int boundary = Math.max(source.lastIndexOf('}', mapping.start()),
                source.lastIndexOf(';', mapping.start()));
        int brace = source.indexOf('{', mapping.end());
        String window = source.substring(Math.max(boundary, 0) + 1,
                brace < 0 ? source.length() : brace);
        return window.contains("@PreAuthorize");
    }

    private static String classBasePath(String source) {
        Matcher matcher = CLASS_MAPPING.matcher(source);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String extractPath(String annotationArguments) {
        if (annotationArguments == null) {
            return "";
        }
        Matcher quoted = QUOTED.matcher(annotationArguments);
        return quoted.find() ? quoted.group(1) : "";
    }

    private static String join(String base, String own) {
        if (own.isEmpty()) {
            return base;
        }
        if (own.startsWith("/")) {
            return base + own;
        }
        return base + "/" + own;
    }

    private static List<String> envList(String key) throws IOException {
        for (String line : Files.readAllLines(ENV)) {
            if (line.startsWith(key + "=")) {
                String value = line.substring(key.length() + 1).trim();
                if (value.isEmpty()) {
                    return List.of();
                }
                return Arrays.stream(value.split(","))
                        .map(String::trim)
                        .filter(entry -> !entry.isEmpty())
                        .toList();
            }
        }
        throw new AssertionError(key + " is missing from .env.example");
    }
}
