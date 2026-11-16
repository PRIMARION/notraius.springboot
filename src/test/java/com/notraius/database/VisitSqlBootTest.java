package com.notraius.database;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.notraius.database.entities.asset.Pricing;
import jakarta.persistence.EntityManager;
import jakarta.persistence.OptimisticLockException;
import com.notraius.database.entities.lease.Lease;
import com.notraius.database.entities.visit.Visit;
import com.notraius.database.repositories.LogsRepository;
import com.notraius.database.repositories.visit.VisitRepository;
import com.notraius.modules.core.common.schedule.SweepKey;
import com.notraius.modules.core.common.schedule.SweepLedger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.primarion.module.infra.partition.LogTableResolver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.notraius.modules.core.common.schedule.SweepLedger.Cursor.NIL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Boot verification for PLAN part 5's SQL.
 * <p>
 * The unit suite mocks every repository, so it can never answer the two
 * questions a migration and a JPQL string really raise: does the schema the
 * migrations build match the entity mappings, and do the sweep queries run as
 * written against PostgreSQL? This test answers both, against a REAL database
 * that the migrations built.
 * <p>
 * It is opt-in — CI without a database skips it. Point it at a scratch
 * database created by applying {@code db/migration/V*.sql} in version order:
 * <pre>
 *   NOTRAIUS_SQL_URL=jdbc:postgresql://localhost:5432/notraius_sqlverify \
 *   NOTRAIUS_SQL_USER=postgres NOTRAIUS_SQL_PASSWORD=... \
 *   ./gradlew test --no-daemon --tests 'com.notraius.database.VisitSqlBootTest'
 * </pre>
 * {@code ddl-auto=validate} makes the context fail if any mapped column,
 * table or FK the scans touch disagrees with the migrated schema.
 */
@SpringBootTest(classes = VisitSqlBootTest.SqlConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                // application.yaml needs ~50 deployment placeholders; the
                // verification owns its properties, so no config file is read.
                "spring.config.location=optional:classpath:/sql-verify-none/",
                "spring.main.banner-mode=off",
                "spring.jpa.hibernate.ddl-auto=validate",
                "spring.jpa.properties.hibernate.default_schema=notraius",
                "spring.jpa.open-in-view=false",
                "spring.flyway.enabled=false"
        })
@EnabledIfEnvironmentVariable(named = "NOTRAIUS_SQL_URL", matches = "jdbc:postgresql:.+")
@Transactional
class VisitSqlBootTest {

    private static final UUID OWNER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID VISITOR = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ASSIGNEE = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID CURRENCY = UUID.fromString("44444444-4444-4444-4444-444444444444");

    /** The window filter's two open ends — the queries never bind a null date. */
    private static final Instant FIRST = Instant.EPOCH;
    private static final Instant FOREVER = Instant.parse("9999-12-31T23:59:59Z");

    /** Every visit status, in the order the story walks them. */
    private static final List<String> ALL_VISIT_STATUSES = List.of("UNPAID", "REQUESTED", "ASSIGNED",
            "PLANNING", "CONFIRMED", "STARTED", "COMPLETED", "EXPIRED", "CANCELLED");

    private static final UUID PROPERTY = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID OTHER_PROPERTY = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000002");
    private static final UUID DURATION = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001");
    private static final UUID LIVE_MONTHLY = UUID.fromString("cccccccc-0000-0000-0000-000000000001");
    private static final UUID LIVE_NIGHTLY = UUID.fromString("cccccccc-0000-0000-0000-000000000002");
    private static final UUID PAUSED_PRICE = UUID.fromString("cccccccc-0000-0000-0000-000000000003");
    private static final UUID EXPIRED_PRICE = UUID.fromString("cccccccc-0000-0000-0000-000000000004");
    private static final UUID FUTURE_PRICE = UUID.fromString("cccccccc-0000-0000-0000-000000000005");
    private static final UUID ENDED_JUST_NOW = UUID.fromString("cccccccc-0000-0000-0000-000000000006");
    private static final UUID PRICING = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000002");
    private static final UUID WINDOW_PAST = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000003");
    private static final UUID WINDOW_FUTURE = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000004");

    private static final UUID STALE = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001");
    private static final UUID FRESH = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
    private static final UUID LAPSED = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000003");
    private static final UUID CONFIRMED_ON_TIME = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000004");
    private static final UUID UNCLAIMED = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000005");
    private static final UUID CLAIMED = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000006");
    private static final UUID HOUR_PASSED = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000007");
    private static final UUID REUSABLE = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000008");

    private static final UUID TENANT = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final UUID LEASE_PENDING = UUID.fromString("eeeeeeee-0000-0000-0000-000000000001");
    private static final UUID LEASE_ENDED = UUID.fromString("eeeeeeee-0000-0000-0000-000000000002");
    private static final UUID LEASE_WINDOW_FUTURE = UUID.fromString("eeeeeeee-0000-0000-0000-000000000003");
    private static final UUID LEASE_WINDOW_PAST = UUID.fromString("eeeeeeee-0000-0000-0000-000000000004");

    private static final List<String> ALL_LEASE_STATUSES = List.of("PENDING", "CONFIRMED", "ACTIVE",
            "DISPUTED", "COMPLETED", "CANCELLED", "EXPIRED");

    private static final UUID PAYMENT = UUID.fromString("cccccccc-0000-0000-0000-000000000001");
    private static final UUID ACTIVITY = UUID.fromString("dddddddd-0000-0000-0000-000000000001");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("NOTRAIUS_SQL_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("NOTRAIUS_SQL_USER"));
        registry.add("spring.datasource.password", () -> System.getenv("NOTRAIUS_SQL_PASSWORD"));
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableJpaRepositories(basePackages = {"com.notraius.database.repositories.visit",
            "com.notraius.database.repositories.asset",
            "com.notraius.database.repositories.lease"})
    @EntityScan(basePackages = "com.notraius.database.entities")
    static class SqlConfig {
    }

    @Autowired
    private VisitRepository visits;

    @Autowired
    private com.notraius.database.repositories.asset.PricingRepository pricings;

    @Autowired
    private com.notraius.database.repositories.lease.LeaseRepository leases;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager entityManager;

    private LogsRepository logs;

    @BeforeEach
    void seed() {
        logs = new LogsRepository(jdbc, new LogTableResolver("notraius"), null, null, new ObjectMapper());

        UUID propertyStatus = lookup("PROPERTY", "PUBLISHED", "statuses");
        UUID pricingStatus = lookup("VISIT_PRICINGS", "ACTIVE", "statuses");
        UUID pricingType = lookup("VISIT_PRICINGS", "PHYSICAL", "types");
        UUID windowStatus = lookup("VISITS", "STARTED", "statuses");

        Instant now = Instant.now();

        exec("INSERT INTO property.properties (id, created_at, updated_at, title, user_id, status_id) "
                + "VALUES (?, ?, ?, ?, ?, ?)", PROPERTY, ts(now), ts(now), "SQL verify", OWNER, propertyStatus);

        window(WINDOW_PAST, windowStatus, now.minus(Duration.ofHours(3)), now.minus(Duration.ofHours(1)));
        window(WINDOW_FUTURE, windowStatus, now.plus(Duration.ofHours(1)), now.plus(Duration.ofHours(3)));

        exec("INSERT INTO visit.pricings (id, created_at, updated_at, property_id, type_id, amount, "
                        + "duration_minutes, status_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                PRICING, ts(now), ts(now), PROPERTY, pricingType, new BigDecimal("100.00"), 60, pricingStatus);

        visit(STALE, "REQUESTED", now.minus(Duration.ofHours(2)), WINDOW_PAST, null, null, null);
        visit(FRESH, "REQUESTED", now, WINDOW_FUTURE, null, null, null);
        visit(LAPSED, "STARTED", now.minus(Duration.ofHours(2)), WINDOW_FUTURE,
                now.minus(Duration.ofHours(1)), null, null);
        visit(CONFIRMED_ON_TIME, "STARTED", now.minus(Duration.ofHours(2)), WINDOW_FUTURE,
                now.minus(Duration.ofHours(1)), now.minus(Duration.ofMinutes(50)), null);
        visit(UNCLAIMED, "CONFIRMED", now.minus(Duration.ofHours(2)), WINDOW_PAST, null, null, null);
        visit(CLAIMED, "CONFIRMED", now.minus(Duration.ofHours(2)), WINDOW_PAST,
                now.minus(Duration.ofHours(1)), null, null);
        visit(HOUR_PASSED, "STARTED", now.minus(Duration.ofHours(4)), WINDOW_PAST,
                now.minus(Duration.ofHours(3)), now.minus(Duration.ofHours(2)), null);
        visit(REUSABLE, "UNPAID", now.minus(Duration.ofMinutes(5)), WINDOW_FUTURE, null, null, null);

        seededPricingRows(now);

        exec("UPDATE visit.visits SET payment_id = ?, user_id = ? WHERE id = ?", PAYMENT, ASSIGNEE, FRESH);

        leaseRows(now);

        exec("INSERT INTO notraius.logs (created_at, updated_at, reference_id, actor, created_by, activity_id) "
                + "VALUES (?, ?, ?, ?, ?, ?)", ts(now), ts(now), FRESH, "user", ASSIGNEE, ACTIVITY);
        exec("INSERT INTO notraius.logs (created_at, updated_at, reference_id, actor, created_by, activity_id) "
                + "VALUES (?, ?, ?, ?, ?, ?)", ts(now), ts(now), FRESH, "user", ASSIGNEE, ACTIVITY);
    }

    @Test
    @DisplayName("5.3 sweep A: only STARTED claims past the countersign window are lapsed")
    void lapsedClaimsReadTheClaimClock() {
        List<SweepKey> lapsed = visits.findLapsedClaimPage("STARTED",
                Instant.now().minus(Duration.ofMinutes(30)), Instant.EPOCH, NIL, PageRequest.of(0, 100));

        assertThat(lapsed).extracting(SweepKey::getId).containsExactly(LAPSED);
    }

    @Test
    @DisplayName("5.3 sweep B: only CONFIRMED visits nobody started past their window are unclaimed")
    void unclaimedReadsTheWindowEnd() {
        List<SweepKey> unclaimed = visits.findUnclaimedPastEndPage("CONFIRMED", Instant.now(),
                Instant.EPOCH, NIL, PageRequest.of(0, 100));

        assertThat(unclaimed).extracting(SweepKey::getId).containsExactly(UNCLAIMED);
    }

    @Test
    @DisplayName("5.3 sweep C: only confirmed starts whose window end has passed are released")
    void assessmentHourReadsTheWindowEnd() {
        List<SweepKey> passed = visits.findAssessmentHourPassedPage("STARTED", Instant.now(),
                Instant.EPOCH, NIL, PageRequest.of(0, 100));

        assertThat(passed).extracting(SweepKey::getId).containsExactly(HOUR_PASSED);
    }

    @Test
    @DisplayName("5.2 staleness and 5.1 reuse still read the clock the same way")
    void staleAndReusableQueriesRun() {
        assertThat(visits.findStalePage(List.of("REQUESTED"), Instant.now().minus(Duration.ofHours(1)),
                Instant.EPOCH, Instant.EPOCH, NIL, PageRequest.of(0, 100)))
                .extracting(SweepKey::getId).containsExactly(STALE);

        assertThat(visits.findReusable(VISITOR, PRICING, "UNPAID"))
                .extracting(Visit::getId).containsExactly(REUSABLE);
    }

    @Test
    @DisplayName("5.4 property gates count only the visit statuses still holding money")
    void propertyGatesSeeLiveVisits() {
        List<String> live = List.of("REQUESTED", "CONFIRMED", "STARTED");

        assertThat(visits.countLiveForProperty(PROPERTY, live)).isEqualTo(7L);
        assertThat(visits.findLiveForProperty(PROPERTY, live))
                .extracting(Visit::getId)
                .containsExactlyInAnyOrder(STALE, FRESH, LAPSED, CONFIRMED_ON_TIME, UNCLAIMED, CLAIMED, HOUR_PASSED);
        assertThat(visits.countLiveForProperty(UUID.randomUUID(), live)).isZero();
    }

    @Test
    @DisplayName("every eager graph the owner-facing doors use resolves in one pass")
    void entityGraphsLoad() {
        Optional<Visit> loaded = visits.findById(FRESH);
        assertThat(loaded).isPresent();
        assertThat(loaded.get().getPricing().getProperty().getStatus().getCode()).isEqualTo("PUBLISHED");
        assertThat(loaded.get().getPricing().getType().getCode()).isEqualTo("PHYSICAL");
        assertThat(loaded.get().getValidity().getStatus().getCode()).isEqualTo("STARTED");

        assertThat(visits.findByPaymentId(PAYMENT)).map(Visit::getId).contains(FRESH);
        assertThat(visits.findByPaymentId(UUID.randomUUID())).isEmpty();
        assertThat(visits.existsByPricingId(PRICING)).isTrue();

        Page<Visit> byVisitor = visits.searchAsVisitor(VISITOR, ALL_VISIT_STATUSES, FIRST, FOREVER,
                Pageable.ofSize(10));
        assertThat(byVisitor.getTotalElements()).isEqualTo(8);
        assertThat(byVisitor.getContent().getFirst().getPricing().getProperty().getTitle())
                .isEqualTo("SQL verify");

        Page<Visit> byAssignee = visits.searchAsParty(ASSIGNEE, ALL_VISIT_STATUSES, FIRST, FOREVER,
                Pageable.ofSize(10));
        assertThat(byAssignee.getTotalElements()).isEqualTo(1);
        assertThat(byAssignee.getContent().getFirst().getId()).isEqualTo(FRESH);
    }

    @Test
    @DisplayName("5.1/5.2 the two pages honour the status filter and the scheduled window")
    void listPagesHonourFilters() {
        assertThat(visits.searchAsVisitor(VISITOR, List.of("REQUESTED"), FIRST, FOREVER, Pageable.ofSize(50)))
                .extracting(Visit::getId)
                .containsExactlyInAnyOrder(STALE, FRESH);

        // "What is coming up": a visit counts until its own window has ended.
        assertThat(visits.searchAsVisitor(VISITOR, ALL_VISIT_STATUSES, Instant.now(), FOREVER, Pageable.ofSize(50)))
                .extracting(Visit::getId)
                .containsExactlyInAnyOrder(FRESH, LAPSED, CONFIRMED_ON_TIME, REUSABLE);

        // "What has already begun": the other side of the same window.
        assertThat(visits.searchAsVisitor(VISITOR, ALL_VISIT_STATUSES, FIRST, Instant.now(), Pageable.ofSize(50)))
                .extracting(Visit::getId)
                .containsExactlyInAnyOrder(STALE, UNCLAIMED, CLAIMED, HOUR_PASSED);
    }

    @Test
    @DisplayName("5.2 the owner sees requests nobody assigned yet — the inbox that had no door")
    void ownerInboxSeesUnassignedRequests() {
        List<String> requests = List.of("REQUESTED");

        // Every visit in this fixture is on OWNER's property; FRESH is the one
        // stamped to ASSIGNEE. An owner who is not the assignee still sees both.
        assertThat(visits.searchAsParty(OWNER, requests, FIRST, FOREVER, Pageable.ofSize(50)))
                .extracting(Visit::getId)
                .containsExactlyInAnyOrder(STALE, FRESH);
        assertThat(visits.searchAsParty(ASSIGNEE, requests, FIRST, FOREVER, Pageable.ofSize(50)))
                .extracting(Visit::getId)
                .containsExactly(FRESH);
        assertThat(visits.searchAsParty(VISITOR, requests, FIRST, FOREVER, Pageable.ofSize(50))).isEmpty();
    }

    @Test
    @DisplayName("5.5 evidence counting reads the partitioned audit parent, not a stale partition")
    void auditEvidenceCountsThroughTheParent() {
        assertThat(logs.countByReferenceActivityAndCreator(FRESH, ACTIVITY, ASSIGNEE)).isEqualTo(2L);
        assertThat(logs.countByReferenceActivityAndCreator(FRESH, ACTIVITY, VISITOR)).isZero();
        assertThat(logs.countByReferenceActivityAndCreator(FRESH, UUID.randomUUID(), ASSIGNEE)).isZero();
    }

    @Test
    @DisplayName("6.1/6.2 the tenancy pages honour the status set and the term window")
    void leasePagesHonourFilters() {
        // Everything the tenant ever signed, then only what still runs.
        assertThat(leases.searchAsTenant(TENANT, ALL_LEASE_STATUSES, FIRST, FOREVER, Pageable.ofSize(10)))
                .extracting(Lease::getId)
                .containsExactlyInAnyOrder(LEASE_PENDING, LEASE_ENDED);
        assertThat(leases.searchAsTenant(TENANT, List.of("PENDING"), FIRST, FOREVER, Pageable.ofSize(10)))
                .extracting(Lease::getId)
                .containsExactly(LEASE_PENDING);

        // `from=now` is "current and future": the ended term drops out.
        assertThat(leases.searchAsTenant(TENANT, ALL_LEASE_STATUSES, Instant.now(), FOREVER,
                Pageable.ofSize(10)))
                .extracting(Lease::getId)
                .containsExactly(LEASE_PENDING);

        // The beneficiary's page reads the same rows from the other side.
        assertThat(leases.searchAsOwner(OWNER, ALL_LEASE_STATUSES, FIRST, FOREVER, Pageable.ofSize(10)))
                .extracting(Lease::getId)
                .containsExactlyInAnyOrder(LEASE_PENDING, LEASE_ENDED);
        assertThat(leases.searchAsOwner(TENANT, ALL_LEASE_STATUSES, FIRST, FOREVER, Pageable.ofSize(10)))
                .isEmpty();
    }

    @Test
    @DisplayName("a revision is answered only by the entry that states it — and junk rows cannot abort the read")
    void revisionReadAnswersOnlyWhatTheChainHolds() throws Exception {
        UUID entity = UUID.randomUUID();
        UUID record = UUID.randomUUID();
        Instant base = Instant.now().minus(Duration.ofMinutes(10));

        // Birth, then two edits. The chain states each version once — as the
        // new side of the write that made it, and as the old side of the write
        // that replaced it.
        revisionRow(entity, record, base, null, "{\"version\":1,\"amount\":250.00}");
        revisionRow(entity, record, base.plusSeconds(1),
                "{\"version\":1,\"amount\":250.00}", "{\"version\":2,\"amount\":300.00}");
        // A payload that is not an object (a batch id array) and one whose
        // version is not a number: the SQL guards must skip both instead of
        // aborting the whole read on a cast.
        revisionRow(entity, record, base.plusSeconds(2), null, "[1,2,3]");
        revisionRow(entity, record, base.plusSeconds(3), null, "{\"version\":\"three\"}");
        revisionRow(entity, record, base.plusSeconds(4),
                "{\"version\":2,\"amount\":300.00}", "{\"version\":3,\"amount\":350.00}");

        assertThat(amountOf(logs.findRevisionSnapshot(entity, record, 1)))
                .isEqualByComparingTo("250.00");
        assertThat(amountOf(logs.findRevisionSnapshot(entity, record, 2)))
                .isEqualByComparingTo("300.00");
        assertThat(amountOf(logs.findRevisionSnapshot(entity, record, 3)))
                .isEqualByComparingTo("350.00");
        assertThat(logs.findRevisionSnapshot(entity, record, 4)).isEmpty();
        assertThat(logs.findRevisionSnapshot(entity, UUID.randomUUID(), 1)).isEmpty();

        // The listing is the same chain read as a list: one row per revision the
        // chain states, newest first, and the malformed payloads (a JSON array, a
        // non-numeric version) contribute nothing — V41's generated columns are
        // what makes both of those true.
        assertThat(logs.findRevisions(entity, record))
                .extracting(LogsRepository.RevisionRow::version)
                .containsExactly(3, 2, 1);
        assertThat(logs.findRevisions(entity, record).getFirst().recordedAt())
                .isAfter(base.plusSeconds(3));
        assertThat(logs.findRevisions(entity, UUID.randomUUID())).isEmpty();
    }

    @Test
    @DisplayName("an as-of read answers each record's state at the instant — and a removal is a state")
    void asOfReadReplaysTheNewestStateAtTheInstant() {
        UUID entity = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID later = UUID.randomUUID();
        UUID removed = UUID.randomUUID();
        Instant base = Instant.now().minus(Duration.ofMinutes(10));

        revisionRow(entity, first, base, null, "{\"version\":1,\"amount\":250.00}");
        revisionRow(entity, first, base.plusSeconds(60),
                "{\"version\":1,\"amount\":250.00}", "{\"version\":2,\"amount\":300.00}");
        // Born after the instant asked for below: it did not answer then.
        revisionRow(entity, later, base.plusSeconds(120), null, "{\"version\":1,\"amount\":90.00}");
        revisionRow(entity, removed, base.plusSeconds(5), null, "{\"version\":1,\"amount\":10.00}");
        // A removal states no after-data: the record did not exist afterwards.
        revisionRow(entity, removed, base.plusSeconds(180),
                "{\"version\":1,\"amount\":10.00}", null);

        List<UUID> page = List.of(first, later, removed);

        // Half-way through: the first price is at version 1, the second does not
        // exist yet, the removed one still does.
        List<LogsRepository.StateRow> early = logs.findStatesAt(entity, page, base.plusSeconds(30));
        assertThat(early).extracting(LogsRepository.StateRow::referenceId)
                .containsExactlyInAnyOrder(first, removed);
        assertThat(early).filteredOn(row -> row.referenceId().equals(first))
                .extracting(LogsRepository.StateRow::snapshot)
                .singleElement().asString().contains("250.00");

        // After the edit: the same record answers with the newer amount.
        assertThat(logs.findStatesAt(entity, List.of(first), base.plusSeconds(90)))
                .extracting(LogsRepository.StateRow::snapshot)
                .singleElement().asString().contains("300.00");

        // After the removal: the row is still returned (it is the record's answer)
        // but its snapshot is null, which the reader must treat as "gone".
        List<LogsRepository.StateRow> afterRemoval = logs.findStatesAt(entity, List.of(removed),
                base.plusSeconds(240));
        assertThat(afterRemoval).hasSize(1);
        assertThat(afterRemoval.getFirst().snapshot()).isNull();

        // Nothing at all before the first write, and an unrelated id never appears.
        assertThat(logs.findStatesAt(entity, page, base.minusSeconds(60))).isEmpty();
        assertThat(logs.findStatesAt(entity, List.of(UUID.randomUUID()), base.plusSeconds(300))).isEmpty();
        assertThat(logs.findStatesAt(entity, List.of(), base)).isEmpty();
    }

    // ─── fixtures ───────────────────────────────────────────────────────────

    /**
     * Two tenancies on the same asset pricing (a lease prices property.pricings,
     * not the visit's own price row): one still running for its tenant, one whose
     * term ended. Same owner, so the beneficiary's page must see both and
     * the tenant filter must be the only thing that separates them.
     */
    private void leaseRows(Instant now) {
        window(LEASE_WINDOW_FUTURE, lookup("LEASES", "PENDING", "statuses"),
                now.minus(Duration.ofDays(1)), now.plus(Duration.ofDays(30)));
        window(LEASE_WINDOW_PAST, lookup("LEASES", "COMPLETED", "statuses"),
                now.minus(Duration.ofDays(60)), now.minus(Duration.ofDays(1)));

        lease(LEASE_PENDING, LEASE_WINDOW_FUTURE, "PENDING", now.minus(Duration.ofDays(1)));
        lease(LEASE_ENDED, LEASE_WINDOW_PAST, "COMPLETED", now.minus(Duration.ofDays(60)));
    }

    private void lease(UUID id, UUID windowId, String status, Instant createdAt) {
        exec("INSERT INTO lease.leases (id, created_at, updated_at, user_id, owner_id, pricing_id, "
                        + "validity_id, status_id, origin, version, checkable, duration_id) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, ts(createdAt), ts(createdAt), TENANT, OWNER, LIVE_MONTHLY, windowId,
                lookup("LEASES", status, "statuses"), "NATIVE", 1, true, DURATION);
    }

    /**
     * One property priced six ways, plus a second property with a live price of
     * its own, so the page read can be checked for both its window law (only
     * what answers now) and its id bound (only what the page asked for).
     */
    private void seededPricingRows(Instant now) {
        UUID propertyStatus = lookup("PROPERTY", "PUBLISHED", "statuses");
        UUID durationStatus = lookup("DURATIONS", "ACTIVE", "statuses");
        UUID durationType = typeOf("DURATION_UNIT", "MONTH");
        UUID monthlyType = typeOf("PRICINGS", "MONTHLY");
        UUID nightlyType = typeOf("PRICINGS", "NIGHTLY");

        exec("INSERT INTO notraius.durations (id, created_at, updated_at, type_id, value, status_id) "
                + "VALUES (?, ?, ?, ?, ?, ?)", DURATION, ts(now), ts(now), durationType, 1, durationStatus);

        exec("INSERT INTO property.properties (id, created_at, updated_at, title, user_id, status_id) "
                + "VALUES (?, ?, ?, ?, ?, ?)",
                OTHER_PROPERTY, ts(now), ts(now), "SQL verify 2", OWNER, propertyStatus);

        pricingRow(LIVE_MONTHLY, PROPERTY, monthlyType, now.minus(Duration.ofDays(1)), null, "LIVE");
        pricingRow(LIVE_NIGHTLY, PROPERTY, nightlyType, now.minus(Duration.ofHours(1)),
                now.plus(Duration.ofDays(30)), "LIVE");
        pricingRow(PAUSED_PRICE, OTHER_PROPERTY, monthlyType, now.minus(Duration.ofDays(1)), null, "PAUSED");
        pricingRow(EXPIRED_PRICE, PROPERTY, typeOf("PRICINGS", "WEEKLY"),
                now.minus(Duration.ofDays(30)), now.minus(Duration.ofDays(1)), "LIVE");
        pricingRow(FUTURE_PRICE, PROPERTY, typeOf("PRICINGS", "YEARLY"),
                now.plus(Duration.ofDays(1)), null, "LIVE");
        pricingRow(ENDED_JUST_NOW, PROPERTY, typeOf("PRICINGS", "DAILY"),
                now.minus(Duration.ofDays(2)), now.minus(Duration.ofSeconds(1)), "LIVE");
    }

    private void pricingRow(UUID id, UUID propertyId, UUID typeId, Instant from, Instant to, String status) {
        UUID validityStatus = lookup("PRICINGS", "LIVE", "statuses");
        window(UUID.nameUUIDFromBytes(("window-" + id).getBytes()), validityStatus, from, to);
        exec("INSERT INTO property.pricings (id, created_at, updated_at, property_id, type_id, duration_id, "
                        + "amount, checkable, version, validity_id, status_id) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, ts(from), ts(from), propertyId, typeId, DURATION,
                new BigDecimal("100.00"), true, 1,
                UUID.nameUUIDFromBytes(("window-" + id).getBytes()), lookup("PRICINGS", status, "statuses"));
    }

    /** A type under an entity, created once per call and reused by (entity, code). */
    private UUID typeOf(String entityCode, String code) {
        List<UUID> existing = jdbc.queryForList(
                "SELECT t.id FROM config.types t JOIN notraius.entities e ON e.id = t.entity_id "
                        + "WHERE e.code = ? AND t.code = ?", UUID.class, entityCode, code);
        if (!existing.isEmpty()) {
            return existing.getFirst();
        }
        UUID entityId = jdbc.queryForObject("SELECT id FROM notraius.entities WHERE code = ?",
                UUID.class, entityCode);
        UUID typeId = UUID.randomUUID();
        exec("INSERT INTO config.types (id, created_at, updated_at, entity_id, code, label, status_id) "
                        + "VALUES (?, now(), now(), ?, ?, ?, ?)",
                typeId, entityId, code, code, lookup("TYPES", "ACTIVE", "statuses"));
        return typeId;
    }

    private void visit(UUID id, String status, Instant createdAt, UUID windowId,
                       Instant startedAt, Instant startConfirmedAt, Instant completedAt) {
        UUID statusId = lookup("VISITS", status, "statuses");
        exec("INSERT INTO visit.visits (id, created_at, updated_at, visitor_id, owner_id, pricing_id, "
                        + "validity_id, amount, currency_id, status_id, started_at, start_confirmed_at, completed_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, ts(createdAt), ts(createdAt), VISITOR, OWNER, PRICING, windowId,
                new BigDecimal("100.00"), CURRENCY, statusId, ts(startedAt), ts(startConfirmedAt), ts(completedAt));
    }

    private void window(UUID id, UUID statusId, Instant from, Instant to) {
        exec("INSERT INTO notraius.validity (id, created_at, updated_at, status_id, from_date_time, to_date_time) "
                + "VALUES (?, ?, ?, ?, ?, ?)", id, ts(from), ts(from), statusId, ts(from), ts(to));
    }

    private UUID lookup(String entityCode, String code, String table) {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT r.id FROM notraius." + table + " r JOIN notraius.entities e ON e.id = r.entity_id "
                        + "WHERE e.code = ? AND r.code = ?", entityCode, code);
        return (UUID) row.get("id");
    }

    private void exec(String sql, Object... args) {
        jdbc.update(sql, args);
    }

    /** One audit-chain entry, written with the jsonb casts the real sink uses. */
    private void revisionRow(UUID entityId, UUID referenceId, Instant at, String oldData, String newData) {
        exec("INSERT INTO notraius.logs (created_at, updated_at, entity_id, reference_id, old_data, "
                        + "new_data, actor, created_by) VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb, 'user', ?)",
                ts(at), ts(at), entityId, referenceId, oldData, newData, OWNER);
    }

    private BigDecimal amountOf(Optional<String> snapshot) throws Exception {
        assertThat(snapshot).isPresent();
        return new ObjectMapper().readTree(snapshot.get()).get("amount").decimalValue();
    }

    // ══════════════════════════════════════════════════════════════════════
    // The concurrency token (V37) — proved against the real schema, because
    // `ddl-auto=validate` only proves the column exists, not that Hibernate
    // fences a stale write with it.
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("a write that lost the race is rejected instead of overwriting the winner")
    void aStaleWriteIsRejected() {
        UUID visitId = jdbc.queryForObject(
                "SELECT id FROM visit.visits ORDER BY id LIMIT 1", UUID.class);
        Visit loaded = visits.findById(visitId).orElseThrow();
        Integer seen = loaded.getLockVersion();
        assertThat(seen).as("V37 seeds every existing row at zero").isNotNull();

        // The winner commits first: the row is now one token ahead of what this
        // caller read. `AND lock_version = seen` can no longer match it.
        jdbc.update("UPDATE visit.visits SET lock_version = lock_version + 1 WHERE id = ?", visitId);

        loaded.setCompletedAt(Instant.now());
        assertThrows(OptimisticLockException.class, () -> entityManager.flush(),
                "a stale write must fail loudly, never silently drop the other edit");
    }

    private static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
