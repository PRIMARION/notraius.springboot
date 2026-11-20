package com.notraius.modules.modules.lease.common;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.notraius.modules.core.core.common.constants.NotraiusWebhookEvents;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import com.notraius.database.entities.asset.config.Rule;
import java.util.Locale;

/**
 * NEW-52 drift test: every (family, word) pair the dispatchers speak is a
 * word of {@link NotraiusWebhookEvents} — and every catalogue word is spoken.
 * A dispatcher word missing from the catalogue ships a webhook nobody can
 * subscribe to ({@code PermissionSeeder} seeds exactly this catalogue via
 * {@code app.activities.event-classes}); a catalogue word nothing
 * dispatches is a phantom subscription target.
 *
 * <p>How it reads: each rule is (call pattern → word-argument index, and
 * optional family-argument index). Arguments are taken balanced-paren and
 * split on top-level commas. A word argument must be a catalogue literal,
 * a host constant that resolves to one, or a bare identifier — deferred,
 * because its feeder call sites are rules too (e.g. {@code
 * dispatchPaymentEvent(payment, event)} is fed by the literal sites this
 * test also scans). Constants resolve through host-wide
 * {@code static final String} chains ({@code STATUS_RELEASED →
 * PayStatuses.RELEASED → "RELEASED"}). Lowercase identifiers and
 * unresolvable qualified refs are skipped, never guessed.
 */
class WebhookEventsDriftTest {

    private static final Path ROOT = Path.of("src/main/java");

    private record Rule(String scope, Pattern call, int wordIdx, Integer familyIdx) {
        static Rule of(String scope, String name, int wordIdx, Integer familyIdx) {
            return new Rule(scope, Pattern.compile("\\b" + name + "\\("), wordIdx, familyIdx);
        }
        /** A method name that must not match after a dot (e.g. {@code .send(}). */
        static Rule bare(String scope, String name, int wordIdx, Integer familyIdx) {
            return new Rule(scope, Pattern.compile("(?<![.\\w])" + name + "\\("), wordIdx, familyIdx);
        }
    }

    private static final List<Rule> RULES = List.of(
            Rule.of(null, "dispatchToUsers", 2, 1),
            Rule.of(null, "dispatchToUser", 2, 1),
            Rule.of(null, "dispatchToCaller", 1, 0),
            Rule.of(null, "announce", 1, 2),
            Rule.of(null, "announceToParties", 1, null),
            Rule.of(null, "dispatchPricing", 1, 2),
            Rule.bare(null, "moveTo", 1, null),
            Rule.bare(null, "end", 1, null),
            Rule.bare(null, "issue", 1, null));

    private static final Class<?> CATALOGUE = NotraiusWebhookEvents.class;

    // ── catalogue reflection ────────────────────────────────────────────

    private static Set<String> catalogueValues(Class<?> catalogue) {
        Set<String> values = new TreeSet<>();
        for (Class<?> inner : catalogue.getDeclaredClasses()) {
            for (Field f : inner.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) && f.getType() == String.class) {
                    try {
                        values.add((String) f.get(null));
                    } catch (IllegalAccessException e) {
                        throw new IllegalStateException(e);
                    }
                }
            }
        }
        return values;
    }

    private static final Set<String> CATALOGUE_VALUES = catalogueValues(CATALOGUE);
    private static final Set<String> CATALOGUE_WORDS = bareWords(CATALOGUE_VALUES);
    private static final Set<String> CATALOGUE_FAMILIES = families(CATALOGUE_VALUES);

    private static Set<String> bareWords(Set<String> dotted) {
        Set<String> words = new TreeSet<>();
        for (String v : dotted) {
            words.add(v.substring(v.indexOf('.') + 1));
        }
        return words;
    }

    private static Set<String> families(Set<String> dotted) {
        Set<String> fams = new TreeSet<>();
        for (String v : dotted) {
            fams.add(v.substring(0, v.indexOf('.')));
        }
        return fams;
    }

    // ── scan state ──────────────────────────────────────────────────────

    private record Finding(String where, String problem) {}

    private Map<Path, String> sources;
    private Map<String, Path> classFiles;
    private Map<Path, Map<String, String>> fileConsts;

    private void loadSources() {
        sources = new LinkedHashMap<>();
        try (Stream<Path> stream = Files.walk(ROOT)) {
            stream.filter(p -> p.toString().endsWith(".java")).sorted().forEach(p -> {
                try {
                    sources.put(p, stripComments(Files.readString(p)));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        classFiles = new HashMap<>();
        for (Path p : sources.keySet()) {
            classFiles.put(basename(p), p);
        }
        fileConsts = new HashMap<>();
        Pattern def = Pattern.compile("\\bString\\s+([A-Z][A-Z_0-9]+)\\s*=\\s*([^;]+);");
        for (var e : sources.entrySet()) {
            Map<String, String> consts = new HashMap<>();
            Matcher m = def.matcher(e.getValue());
            while (m.find()) {
                consts.putIfAbsent(m.group(1), m.group(2).trim());
            }
            fileConsts.put(e.getKey(), consts);
        }
    }

    private static String basename(Path p) {
        String name = p.getFileName().toString();
        return name.substring(0, name.length() - ".java".length());
    }

    private int lineOf(String src, int pos) {
        return (int) src.substring(0, pos).chars().filter(c -> c == '\n').count() + 1;
    }


    /** Comment- and string-aware strip: javadoc mentions of rule method names
     *  (e.g. this catalogue's own docs) must never look like call sites. */
    private static String stripComments(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int i = 0;
        while (i < src.length()) {
            char c = src.charAt(i);
            if (c == '"' || c == '\'') {
                char quote = c;
                out.append(c);
                i++;
                while (i < src.length()) {
                    char d = src.charAt(i);
                    out.append(d);
                    if (d == '\\' && i + 1 < src.length()) {
                        out.append(src.charAt(i + 1));
                        i += 2;
                        continue;
                    }
                    i++;
                    if (d == quote) {
                        break;
                    }
                }
                continue;
            }
            if (c == '/' && i + 1 < src.length() && src.charAt(i + 1) == '/') {
                while (i < src.length() && src.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }
            if (c == '/' && i + 1 < src.length() && src.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < src.length() && !(src.charAt(i) == '*' && src.charAt(i + 1) == '/')) {
                    if (src.charAt(i) == '\n') {
                        out.append('\n'); // keep line numbers stable
                    }
                    i++;
                }
                i += 2;
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /** Balanced-paren argument text of the call starting at {@code openIdx}. */
    private static String balancedArgs(String src, int openIdx) {
        int depth = 0;
        boolean inString = false;
        for (int i = openIdx; i < src.length() && i < openIdx + 8000; i++) {
            char c = src.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return src.substring(openIdx + 1, i);
                }
            }
        }
        return null; // unbalanced (annotation/generic noise) — skip
    }

    /** Split an argument list on top-level commas (brackets and strings respected). */
    private static List<String> splitArgs(String args) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        boolean inString = false;
        for (int i = 0; i < args.length(); i++) {
            char c = args.charAt(i);
            if (inString) {
                current.append(c);
                if (c == '\\' && i + 1 < args.length()) {
                    current.append(args.charAt(++i));
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
                current.append(c);
            } else if (c == '(' || c == '[' || c == '{') {
                depth++;
                current.append(c);
            } else if (c == ')' || c == ']' || c == '}') {
                depth--;
                current.append(c);
            } else if (c == ',' && depth == 0) {
                parts.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (!current.toString().isBlank()) {
            parts.add(current.toString());
        }
        return parts;
    }

    private static final Pattern LITERAL = Pattern.compile("\"([A-Z][A-Z_0-9]+)\"");
    private static final Pattern QUALIFIED = Pattern.compile("\\b([A-Za-z_$][\\w$]*)\\.([A-Z][A-Z_0-9]+)\\b");
    private static final Pattern BARE = Pattern.compile("\\b([A-Z][A-Z_0-9]+)\\b");

    /**
     * Every uppercase word a word/family argument resolves to. Throws on a
     * qualified constants-class ref whose field cannot be resolved (drift);
     * skips lowercase identifiers and refs to classes that are not host
     * constants classes ({@code Locale.ROOT} and friends).
     */
    private Set<String> resolveWords(String text, Path file, Set<String> visited) {
        Set<String> words = new LinkedHashSet<>();
        Matcher lit = LITERAL.matcher(text);
        while (lit.find()) {
            words.add(lit.group(1));
        }
        Matcher qual = QUALIFIED.matcher(text);
        while (qual.find()) {
            String cls = qual.group(1);
            String field = qual.group(2);
            Path def = classFiles.get(cls);
            if (def == null) {
                continue; // not a constants class (Locale.ROOT, Visibility.PUBLIC, ...)
            }
            if (!visited.add(cls + "." + field)) {
                continue;
            }
            String expr = fileConsts.get(def).get(field);
            if (expr == null) {
                // nested class? (e.g. PayActivities.TopUp.INITIATE)
                String nested = resolveNested(cls, field);
                if (nested == null) {
                    fail("cannot resolve constant " + cls + "." + field
                            + " (referenced in " + file + ") — catalogue drift test needs the real value");
                }
                words.add(nested);
                continue;
            }
            words.addAll(resolveWords(expr, file, visited));
        }
        Matcher bare = BARE.matcher(text);
        while (bare.find()) {
            String token = bare.group(1);
            String expr = fileConsts.get(file).get(token);
            if (expr == null) {
                continue; // not a file constant (enum names etc.) — never guessed
            }
            if (!visited.add(token)) {
                continue;
            }
            words.addAll(resolveWords(expr, file, visited));
        }
        return words;
    }

    /** A field of a NESTED class (e.g. class {@code Transfer} in PayActivities.java). */
    private String resolveNested(String enclosing, String field) {
        Pattern classDecl = Pattern.compile("\\b(?:class|interface)\\s+" + Pattern.quote(enclosing) + "\\b");
        Pattern fieldDecl = Pattern.compile(
                "\\bString\\s+" + Pattern.quote(field) + "\\s*=\\s*\"([A-Z_0-9]+)\"");
        for (String src : sources.values()) {
            Matcher cm = classDecl.matcher(src);
            while (cm.find()) {
                int end = nextClassBoundary(src, cm.end());
                Matcher fm = fieldDecl.matcher(src);
                while (fm.find()) {
                    if (fm.start() > cm.start() && fm.start() < end) {
                        return fm.group(1);
                    }
                }
            }
        }
        return null;
    }

    private static int nextClassBoundary(String src, int from) {
        Matcher m = Pattern.compile("\\b(?:class|interface)\\s").matcher(src);
        return m.find(from) ? m.start() : src.length();
    }

    // ── the two laws ────────────────────────────────────────────────────

    @Test
    void everyDispatchedWordIsACatalogueWord() throws IOException {
        loadSources();
        List<Finding> findings = new ArrayList<>();
        Set<String> spoken = new LinkedHashSet<>();

        for (var se : sources.entrySet()) {
            Path file = se.getKey();
            String src = se.getValue();
            String simple = basename(file);
            for (Rule rule : RULES) {
                if (rule.scope() != null && !rule.scope().equals(simple)) {
                    continue;
                }
                Matcher call = rule.call().matcher(src);
                while (call.find()) {
                    String args = balancedArgs(src, call.end() - 1);
                    if (args == null) {
                        continue;
                    }
                    List<String> parts = splitArgs(args);
                    int line = lineOf(src, call.start());
                    if (parts.size() <= rule.wordIdx()) {
                        findings.add(new Finding(simple + ":" + line,
                                rule.call().pattern() + " has " + parts.size()
                                        + " args, expected a word at index " + rule.wordIdx()));
                        continue;
                    }
                    for (String w : resolveWords(parts.get(rule.wordIdx()), file, new LinkedHashSet<>())) {
                        spoken.add(w);
                        if (!CATALOGUE_WORDS.contains(w)) {
                            findings.add(new Finding(simple + ":" + line,
                                    "dispatch word \"" + w + "\" is not in " + CATALOGUE.getSimpleName()
                                            + " — add it (the seeder seeds exactly that catalogue)"));
                        }
                    }
                    if (rule.familyIdx() != null && parts.size() > rule.familyIdx()) {
                        for (String f : resolveWords(parts.get(rule.familyIdx()), file, new LinkedHashSet<>())) {
                            String family = f.contains(".") ? f.substring(0, f.indexOf('.')) : f;
                            if (!CATALOGUE_FAMILIES.contains(family)) {
                                findings.add(new Finding(simple + ":" + line,
                                        "dispatch family \"" + family + "\" is not a "
                                                + CATALOGUE.getSimpleName() + " family"));
                            }
                        }
                    }
                }
            }
        }
        assertTrue(findings.isEmpty(), () -> {
            StringBuilder sb = new StringBuilder("webhook vocabulary drift:\n");
            for (Finding f : findings) {
                sb.append("  ").append(f.where()).append(" — ").append(f.problem()).append('\n');
            }
            return sb.toString();
        });
        // tripwire sanity: the scan must have seen dispatch sites at all
        assertFalse(spoken.isEmpty(), "scan found no dispatch words — the rules are stale");
    }

    @Test
    void everyCatalogueWordIsDispatched() throws IOException {
        loadSources();
        Set<String> spoken = new LinkedHashSet<>();
        for (var se : sources.entrySet()) {
            Path file = se.getKey();
            String src = se.getValue();
            String simple = basename(file);
            for (Rule rule : RULES) {
                if (rule.scope() != null && !rule.scope().equals(simple)) {
                    continue;
                }
                Matcher call = rule.call().matcher(src);
                while (call.find()) {
                    String args = balancedArgs(src, call.end() - 1);
                    if (args == null) {
                        continue;
                    }
                    List<String> parts = splitArgs(args);
                    if (parts.size() <= rule.wordIdx()) {
                        continue;
                    }
                    spoken.addAll(resolveWords(parts.get(rule.wordIdx()), file, new LinkedHashSet<>()));
                }
            }
        }
        Set<String> phantom = new TreeSet<>(CATALOGUE_WORDS);
        phantom.removeAll(spoken);
        assertTrue(phantom.isEmpty(), () -> "catalogue words nothing dispatches (phantom subscription "
                + "targets — remove them from " + CATALOGUE.getSimpleName() + "): " + phantom);
    }

    static {
        assertFalse(CATALOGUE_VALUES.isEmpty(), "empty catalogue");
    }
}
