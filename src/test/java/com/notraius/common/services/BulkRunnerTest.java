package com.notraius.common.services;

import com.notraius.common.NotraiusErrors;
import com.notraius.common.dto.bulk.BulkItemResult;
import com.notraius.common.dto.bulk.BulkResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.primarion.module.errors.BadRequestException;
import org.primarion.module.errors.ConflictException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The bulk envelope's contract: one item's refusal is one item's refusal.
 * <p>
 * The transaction template is stubbed (no database here) because what is under
 * test is the accounting, not Postgres: which faults become per-item results,
 * which become the envelope's own fault, and whether an expected refusal can
 * ever stop the items after it.
 */
class BulkRunnerTest {

    private static final UUID A = UUID.fromString("aaaaaaaa-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("bbbbbbbb-0000-0000-0000-00000000000b");
    private static final UUID C = UUID.fromString("cccccccc-0000-0000-0000-00000000000c");

    private final BulkRunner runner = new BulkRunner(noOpTransactions());

    @Test
    @DisplayName("a refused item is answered alone; the items around it still run")
    void refusedItemDoesNotStopTheBatch() {
        List<UUID> visited = new ArrayList<>();

        BulkResponse<String> response = runner.run(List.of(A, B, C), id -> {
            visited.add(id);
            if (id.equals(B)) {
                throw new ConflictException(NotraiusErrors.RULE_IN_USE, "Rule is in use");
            }
            return "done-" + id;
        });

        assertThat(visited).containsExactly(A, B, C);
        assertThat(response.getRequested()).isEqualTo(3);
        assertThat(response.getSucceeded()).isEqualTo(2);
        assertThat(response.getFailed()).isEqualTo(1);
        assertThat(response.values()).containsExactly("done-" + A, "done-" + C);

        BulkItemResult<String> failure = response.getResults().get(1);
        assertThat(failure.isOk()).isFalse();
        assertThat(failure.getId()).isEqualTo(B);
        assertThat(failure.getCode()).isEqualTo(NotraiusErrors.RULE_IN_USE.code());
        assertThat(failure.getMessage()).isEqualTo("Rule is in use");
    }

    @Test
    @DisplayName("an unnamed fault answers the bulk code, never the exception's own words")
    void unnamedFaultDoesNotLeakItsMessage() {
        BulkResponse<String> response = runner.run(List.of(A), id -> {
            throw new IllegalStateException("duplicate key value violates constraint uk_secret_column");
        });

        BulkItemResult<String> failure = response.getResults().get(0);
        assertThat(failure.isOk()).isFalse();
        assertThat(failure.getCode()).isEqualTo(NotraiusErrors.BULK_ITEM_FAILED.code());
        assertThat(failure.getMessage()).doesNotContain("uk_secret_column");
    }

    @Test
    @DisplayName("payload creates answer in the order they were sent, with no id to echo")
    void payloadsKeepTheirOrder() {
        AtomicInteger counter = new AtomicInteger();

        BulkResponse<Integer> response = runner.runPayloads(List.of("a", "b", "c"), payload -> switch (payload) {
            case "b" -> throw new BadRequestException(NotraiusErrors.RULE_VISIBILITY_INVALID,
                    "Unknown rule visibility: null");
            default -> counter.incrementAndGet();
        });

        assertThat(response.getResults()).extracting(BulkItemResult::getId).containsOnlyNulls();
        assertThat(response.getResults()).extracting(BulkItemResult::isOk)
                .containsExactly(true, false, true);
        assertThat(response.getResults().get(1).getCode())
                .isEqualTo(NotraiusErrors.RULE_VISIBILITY_INVALID.code());
        assertThat(counter.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("an empty list is an empty answer, not a failure")
    void emptyBatchIsEmpty() {
        BulkResponse<String> response = runner.run(List.of(), id -> "never");

        assertThat(response.getRequested()).isZero();
        assertThat(response.getResults()).isEmpty();
    }

    /** The template the runner really uses, minus the database behind it. */
    private static PlatformTransactionManager noOpTransactions() {
        return new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {
                // nothing to commit
            }

            @Override
            public void rollback(TransactionStatus status) {
                // nothing to undo
            }
        };
    }
}
