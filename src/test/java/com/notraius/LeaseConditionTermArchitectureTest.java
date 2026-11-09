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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The terms a tenancy owes are contract facts, frozen at birth: the condition
 * flag (V38) and the billing cadence (V39).
 * <p>
 * Both used to be read back from the live pricing. {@code pricing.isCheckable()}
 * decided the activation stage, the repair report, the settlement's move-out
 * gate and {@code bypassesConditions}; {@code pricing.getDuration()} derived
 * every installment period and the grace window. Because a price is editable in
 * place — including re-pointing its duration — an owner could add or remove a
 * whole contractual stage, or turn a monthly tenancy into a yearly one, on a
 * running tenancy simply by editing the offer, with nothing on the lease
 * recording what was agreed.
 * <p>
 * The entity test and the creation test cover the snapshot; this one covers the
 * way the bug comes back: a future handler reading the pricing instead of the
 * lease because it is one call away. It scans source rather than bytecode —
 * ArchUnit cannot parse this JDK's class files and would pass vacuously (the
 * same reason {@code SqlLocationArchitectureTest} scans).
 */
class LeaseConditionTermArchitectureTest {

    private static final Path SOURCES = Path.of("src/main/java");

    /** {@code getPricing().isCheckable()}, however it is wrapped or spaced. */
    private static final Pattern LIVE_FLAG_READ =
            Pattern.compile("getPricing\\(\\)\\s*\\.\\s*isCheckable\\(\\)");

    /** {@code getPricing().getDuration()}, same rule: the cadence is the lease's. */
    private static final Pattern LIVE_CADENCE_READ =
            Pattern.compile("getPricing\\(\\)\\s*\\.\\s*getDuration\\(\\)");

    @Test
    @DisplayName("no handler answers the condition term by asking the live pricing")
    void theTermIsNeverReadFromThePricing() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                Matcher flag = LIVE_FLAG_READ.matcher(source);
                if (flag.find()) {
                    offenders.add(file + " → " + flag.group());
                }
                Matcher cadence = LIVE_CADENCE_READ.matcher(source);
                if (cadence.find()) {
                    offenders.add(file + " → " + cadence.group());
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "the condition term (V38) and the cadence (V39) belong to the lease, not to the live "
                        + "pricing: " + offenders);
    }

    @Test
    @DisplayName("the snapshot is actually read somewhere, so the scan above is not vacuous")
    void theTermIsReadFromTheLease() throws IOException {
        int reads = 0;
        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                Matcher matcher = Pattern.compile("lease\\.isCheckable\\(\\)")
                        .matcher(Files.readString(file));
                while (matcher.find()) {
                    reads++;
                }
            }
        }
        assertFalse(reads == 0, "nothing reads lease.isCheckable() — the term is frozen but never used");
    }
}
