package com.notraius.config.web;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The anonymous read tier is ONE agreement now: the handler's declaration.
 * <p>
 * This test holds the new law from the source side:
 * <ol>
 *   <li>the door environment lists are gone — {@code PUBLIC_GET_ENDPOINTS},
 *       {@code AUTHENTICATED_GET_ENDPOINTS} and friends no longer exist, so a
 *       door cannot be opened from a config file;</li>
 *   <li>every controller handler declares a rung (or inherits a class-level
 *       one) — {@code DoorRegistry} enforces the same invariant at boot, this
 *       keeps it visible in the build;</li>
 *   <li>ANONYMOUS (the fully-open rung) is only ever declared by the
 *       intentional public read controllers — a write surface or an owner
 *       page must not drift open;</li>
 *   <li>the two sibling pages that used to need {@code AUTHENTICATED_GET_*}
 *       (an owner page and a moderation queue) do not declare the open rung:
 *       specificity resolves them to 401 by their own gate.</li>
 * </ol>
 */
class PublicSurfaceTest {

    private static final Path SOURCES = Path.of("src/main/java");
    private static final Path CONTROLLERS = Path.of("src/main/java/com/notraius");
    private static final Path ENV = Path.of(".env.example");

    private static final Pattern MAPPING =
            Pattern.compile("@(Get|Post|Put|Patch|Delete)Mapping(?:\\s*\\(([^)]*)\\))?");
    private static final Pattern CLASS_MAPPING =
            Pattern.compile("@RequestMapping\\s*\\(\\s*\"([^\"]*)\"");

    /** Controllers that may legitimately declare the fully-open rung. */
    private static final List<String> PUBLIC_READ_CONTROLLERS = List.of(
            "PropertyController",
            "PricingController",
            "TermsController",
            "RuleController",
            "ReferenceController",
            "PolicyController",
            "VisitPricingController",
            "PublicVerificationController");

    @Test
    void doorEnvironmentListsAreGone() throws IOException {
        String env = Files.readString(ENV);
        for (String banned : List.of("PUBLIC_GET_ENDPOINTS", "PUBLIC_POST_ENDPOINTS",
                "PUBLIC_ENDPOINTS", "AUTHENTICATED_GET_ENDPOINTS", "KEY_AUTHORIZED_ENDPOINTS",
                "PUBLIC_ACTUATOR_ENDPOINTS", "PUBLIC_AUTHENTICITY_ENDPOINTS")) {
            assertFalse(env.contains(banned),
                    banned + " still exists in .env.example — doors are declarations now");
        }
    }

    @Test
    void everyHandlerDeclaresARung() throws IOException {
        List<String> undeclared = new ArrayList<>();
        for (Path file : javaFiles()) {
            String source = Files.readString(file);
            if (!source.contains("@RestController")) {
                continue;
            }
            if (classLevelDeclaration(source)) {
                continue;
            }
            Matcher mapping = MAPPING.matcher(source);
            while (mapping.find()) {
                String block = source.substring(mapping.end(),
                        Math.min(source.length(), mapping.end() + 900));
                int signature = firstSignature(block);
                String declarationWindow = signature < 0 ? block : block.substring(0, signature);
                if (!declarationWindow.contains("@PreAuthorize")) {
                    undeclared.add(file.getFileName() + " near " + mapping.group());
                }
            }
        }
        assertTrue(undeclared.isEmpty(),
                "handlers with no rung declaration (DoorRegistry would fail the boot): " + undeclared);
    }

    @Test
    void onlyPublicReadControllersDeclareAnonymous() throws IOException {
        for (Path file : javaFiles()) {
            String source = Files.readString(file);
            if (!source.contains("'ANONYMOUS'")) {
                continue;
            }
            assertTrue(PUBLIC_READ_CONTROLLERS.contains(strip(file.getFileName().toString())),
                    file.getFileName() + " declares ANONYMOUS but is not a public read controller");
        }
    }

    @Test
    void authenticatedSiblingPagesDoNotDeclareTheOpenRung() throws IOException {
        for (Path file : javaFiles()) {
            String source = Files.readString(file);
            Matcher m = MAPPING.matcher(source);
            while (m.find()) {
                String path = m.group(2) == null ? "" : m.group(2);
                if (!path.contains("/mine") && !path.contains("submissions")) {
                    continue;
                }
                String block = source.substring(m.end(),
                        Math.min(source.length(), m.end() + 500));
                assertFalse(block.contains("'ANONYMOUS'"),
                        path + " sits inside a public wildcard but must keep its own gate "
                                + "(specificity gives the named door priority, so it answers 401)");
            }
        }
    }

    // ── source scanning ─────────────────────────────────────────────────────

    private List<Path> javaFiles() throws IOException {
        try (Stream<Path> stream = Files.walk(CONTROLLERS)) {
            return stream.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    private static String strip(String name) {
        return name.endsWith(".java") ? name.substring(0, name.length() - 5) : name;
    }

    private static boolean classLevelDeclaration(String source) {
        int classIndex = source.indexOf("public class");
        if (classIndex < 0) {
            return false;
        }
        int from = Math.max(0, classIndex - 600);
        return source.substring(from, classIndex).contains("@PreAuthorize");
    }

    private static int firstSignature(String block) {
        Matcher m = Pattern.compile("(?m)^\\s*(public|protected)\\s+[^;=]*\\(").matcher(block);
        return m.find() ? m.start() : -1;
    }
}
