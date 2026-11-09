package com.notraius;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The versioning law's second half, enforced: <b>the old side of a history entry
 * must be the old truth.</b>
 * <p>
 * Every versioned write stores {@code old_data} and {@code new_data} beside its
 * {@code version} bump, and the revision read answers a past version from that
 * chain. That only works while the two sides differ: a service that mutates the
 * entity first and snapshots "before" afterwards writes an entry whose old side
 * already states the new value, and the past becomes unrecoverable — silently,
 * because the write itself succeeds and the version is still correct.
 * <p>
 * Five handlers had exactly that shape when this test was written
 * ({@code PricingService.updatePricing}, {@code PropertyService.updateProperty},
 * {@code PolicyService.updatePolicy}, {@code VisitPricingService.updatePricing}
 * and {@code LeaseAgreementService.end}, which zeroed the deposits before
 * snapshotting them). The unit tests pin those five; this scan pins the rule, so
 * the sixth cannot be added by copying the wrong neighbour.
 * <p>
 * It scans source rather than bytecode for the reason the sibling architecture
 * tests do: ArchUnit cannot parse this JDK's class files and would pass
 * vacuously. Signatures must fit on one line — the house style — so a method
 * whose parameters wrap is simply not analysed rather than mis-analysed; the
 * analysed count is asserted below so the regex cannot rot into silence.
 */
class RevisionSnapshotArchitectureTest {

    private static final Path SOURCES = Path.of("src/main/java");

    /** A method whose signature closes on its own line — the house style. */
    private static final Pattern METHOD_START =
            Pattern.compile("^\\s{4}(?:public|private|protected)[\\w<>\\[\\], .]*\\w+\\s*\\([^;]*\\)\\s*\\{\\s*$");

    /** {@code PricingResponse before = mapper.toResponse(...)}, however named. */
    private static final Pattern BEFORE_ASSIGNMENT =
            Pattern.compile("\\b(before|previous\\w*)\\s*=\\s*.*(toResponse|valueToTree)\\(");

    /** Any setter on any receiver. */
    private static final Pattern MUTATION = Pattern.compile("\\b\\w+\\.set[A-Z]\\w*\\(");

    @Test
    @DisplayName("no service captures its 'before' snapshot after it already mutated the row")
    void beforeSnapshotPrecedesEveryMutation() throws IOException {
        List<String> offenders = new ArrayList<>();
        int analysed = 0;

        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file : files.filter(path -> path.toString().endsWith("Service.java")).toList()) {
                List<String> lines = readCode(file);
                analysed += scan(lines, file, offenders);
            }
        }

        assertTrue(offenders.isEmpty(),
                "a history entry must be written before the row is mutated, or its old side states the "
                        + "new truth: " + offenders);
        // Guard against a vacuous pass: the scanner must have seen the snapshots
        // it exists to police.
        assertTrue(analysed >= 20,
                "the scanner only recognised " + analysed + " before-snapshots; the source shape changed "
                        + "and this guard is no longer watching anything");
    }

    /** Comment-stripped lines, so a setter named in prose is not a mutation. */
    private List<String> readCode(Path file) throws IOException {
        List<String> code = new ArrayList<>();
        for (String line : Files.readAllLines(file)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("*") || trimmed.startsWith("/*") || trimmed.startsWith("//")) {
                code.add("");
                continue;
            }
            int comment = line.indexOf("//");
            code.add(comment >= 0 ? line.substring(0, comment) : line);
        }
        return code;
    }

    /** Returns how many before-snapshots the file stated; records violations. */
    private int scan(List<String> lines, Path file, List<String> offenders) {
        List<Integer> starts = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (METHOD_START.matcher(lines.get(i)).matches()) {
                starts.add(i);
            }
        }
        int snapshots = 0;
        for (int index = 0; index < starts.size(); index++) {
            int from = starts.get(index);
            int to = index + 1 < starts.size() ? starts.get(index + 1) : lines.size();
            for (int line = from; line < to; line++) {
                Matcher assignment = BEFORE_ASSIGNMENT.matcher(lines.get(line));
                if (!assignment.find()) {
                    continue;
                }
                snapshots++;
                for (int earlier = from; earlier < line; earlier++) {
                    if (MUTATION.matcher(lines.get(earlier)).find()) {
                        offenders.add(file + ":" + (line + 1) + " (method at " + (from + 1) + ")");
                        break;
                    }
                }
            }
        }
        return snapshots;
    }
}
