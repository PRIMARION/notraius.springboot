package com.notraius;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SQL-LOCATION guard: raw SQL lives ONLY in repositories.
 * <p>
 * Services and jobs call repositories at 100% — they never hold SQL. This is a
 * SOURCE scan rather than a bytecode rule on purpose: ArchUnit cannot parse
 * Java 25 class files (major 69) and silently imports nothing, so a bytecode
 * rule passes vacuously on this toolchain (see {@code ArchitectureTest}).
 * <p>
 * Allowed to contain SQL / {@code JdbcTemplate}:
 * <ul>
 *   <li>files under a {@code repositories} package, plus files named
 *       {@code *Repository.java} / {@code *Repositories.java} (nested
 *       {@code @Repository} interfaces such as {@code PlatformRepositories)
 *       live outside the package by design);</li>
 *   <li>{@code @Configuration} classes holding a {@code JdbcTemplate}
 *       <em>parameter</em> purely as bean wiring — they still must not
 *       contain any SQL of their own.</li>
 * </ul>
 * The patterns below mirror the repo-wide audit grep exactly, so "test green"
 * and "audit clean" are the same statement.
 */
class SqlLocationArchitectureTest {

    private static final Path MAIN_SOURCES = Path.of("src", "main", "java");

    /** Statement-start SQL string literals. */
    private static final Pattern SQL_LITERAL = Pattern.compile(
            "\"(SELECT |INSERT INTO |UPDATE [a-z_\"]|DELETE FROM |"
                    + "CREATE TABLE|DROP TABLE|TRUNCATE |ALTER TABLE)");

    /** Statement-start SQL at the head of a text block ({@code """}). */
    private static final Pattern SQL_TEXT_BLOCK = Pattern.compile(
            "\"\"\"\\s*(SELECT |INSERT INTO |UPDATE [a-z_\"]|DELETE FROM |"
                    + "CREATE TABLE|DROP TABLE|TRUNCATE |ALTER TABLE)");

    /** Hibernate native-query entry points. */
    private static final Pattern NATIVE_QUERY = Pattern.compile(
            "\\b(createNativeQuery|createSQLQuery)\\b");

    private static List<Path> mainSources() throws IOException {
        if (!Files.exists(MAIN_SOURCES)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(MAIN_SOURCES)) {
            return walk.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.toString().contains("module-info"))
                    .filter(p -> !isRepositoryCode(p))
                    .toList();
        }
    }

    /** Repositories own SQL: the {@code repositories} package or *Repository(s).java. */
    private static boolean isRepositoryCode(Path p) {
        String s = p.toString().replace('\\', '/');
        if (s.contains("/repositories/")) {
            return true;
        }
        String name = p.getFileName().toString();
        return name.endsWith("Repository.java") || name.endsWith("Repositories.java");
    }

    private static String read(Path p) {
        try {
            return Files.readString(p, UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static List<String> firstMatchPerFile(Pattern pattern) throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path p : mainSources()) {
            String src = read(p);
            Matcher m = pattern.matcher(src);
            if (m.find()) {
                long line = src.substring(0, m.start()).lines().count() + 1;
                violations.add(p + ":" + line + " -> " + m.group().strip());
            }
        }
        return violations;
    }

    @Test
    void noJdbcTemplateOutsideRepositories() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path p : mainSources()) {
            String src = read(p);
            // @Configuration may inject JdbcTemplate purely as bean wiring;
            // the SQL-literal checks below still police what it does with it.
            if (src.contains("JdbcTemplate") && !src.contains("@Configuration")) {
                violations.add(p.toString());
            }
        }
        assertTrue(violations.isEmpty(), () ->
                "JdbcTemplate must live in repositories — services/jobs call "
                        + "repositories, never SQL. Offenders: " + violations);
    }

    @Test
    void noSqlLiteralsOutsideRepositories() throws IOException {
        List<String> violations = firstMatchPerFile(SQL_LITERAL);
        assertTrue(violations.isEmpty(), () ->
                "Raw SQL string literals must live in repositories. Offenders: " + violations);
    }

    @Test
    void noTextBlockSqlOutsideRepositories() throws IOException {
        List<String> violations = firstMatchPerFile(SQL_TEXT_BLOCK);
        assertTrue(violations.isEmpty(), () ->
                "Raw SQL text blocks must live in repositories. Offenders: " + violations);
    }

    @Test
    void noHibernateNativeQueriesOutsideRepositories() throws IOException {
        List<String> violations = firstMatchPerFile(NATIVE_QUERY);
        assertTrue(violations.isEmpty(), () ->
                "createNativeQuery/createSQLQuery must live in repositories. "
                        + "Offenders: " + violations);
    }
}
