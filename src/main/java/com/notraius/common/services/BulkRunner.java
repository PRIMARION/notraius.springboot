package com.notraius.common.services;

import com.notraius.common.NotraiusErrors;
import com.notraius.common.dto.bulk.BulkItemResult;
import com.notraius.common.dto.bulk.BulkResponse;
import lombok.extern.slf4j.Slf4j;
import org.primarion.module.errors.AppException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * Runs a bulk door as N ordinary single-item acts.
 * <p>
 * <b>One item, one transaction.</b> The reason is the whole point of the
 * feature: folding a hundred calls into one must not turn a hundred
 * independent refusals into one shared fate. Each item runs in its own
 * {@code REQUIRES_NEW} transaction, so an item that fails — a missing row, a
 * refused state, a deadlock — rolls back only its own writes and leaves the
 * previous items committed and the next ones untouched. A bulk call is
 * therefore not atomic, and the response says so item by item
 * ({@link BulkItemResult#isOk()}), which is exactly the contract a caller needs
 * to retry the failures it cares about.
 * <p>
 * <b>No new authorisation surface.</b> The runner never decides who may do
 * what: it calls the very same service method the single-item endpoint calls,
 * so every ownership check, status law, consent delegation and audit row is
 * the one the single door runs. The endpoint's declared permission is charged
 * once for the batch, and {@code BulkIdsRequest.MAX_ITEMS} is the cap that
 * bounds what that one permit can buy.
 * <p>
 * <b>Reads are read-only.</b> {@link #read(List, Function)} runs each item
 * inside a read-only transaction template, so a batch read cannot dirty the
 * session and a per-item refusal (a private rule, someone else's property)
 * still answers for itself.
 */
@Component
@Slf4j
public class BulkRunner {

    private final TransactionTemplate writeTemplate;
    private final TransactionTemplate readTemplate;

    public BulkRunner(PlatformTransactionManager transactionManager) {
        this.writeTemplate = new TransactionTemplate(transactionManager);
        this.writeTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.readTemplate = new TransactionTemplate(transactionManager);
        this.readTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.readTemplate.setReadOnly(true);
    }

    /** One act per id, each in its own write transaction. */
    public <T> BulkResponse<T> run(List<UUID> ids, Function<UUID, T> act) {
        List<BulkItemResult<T>> results = new ArrayList<>(ids.size());
        for (UUID id : ids) {
            results.add(execute(id, id, act, writeTemplate));
        }
        return new BulkResponse<>(results);
    }

    /** One act per payload (a create), each in its own write transaction. */
    public <P, T> BulkResponse<T> runPayloads(List<P> payloads, Function<P, T> act) {
        List<BulkItemResult<T>> results = new ArrayList<>(payloads.size());
        for (P payload : payloads) {
            results.add(execute(null, payload, act, writeTemplate));
        }
        return new BulkResponse<>(results);
    }

    /** One read per id, each in its own read-only transaction. */
    public <T> BulkResponse<T> read(List<UUID> ids, Function<UUID, T> read) {
        List<BulkItemResult<T>> results = new ArrayList<>(ids.size());
        for (UUID id : ids) {
            results.add(execute(id, id, read, readTemplate));
        }
        return new BulkResponse<>(results);
    }

    private <P, T> BulkItemResult<T> execute(UUID id, P payload, Function<P, T> act,
                                             TransactionTemplate template) {
        try {
            T value = template.execute(status -> act.apply(payload));
            return BulkItemResult.ok(id, value);
        } catch (AppException e) {
            // The domain refused this item on purpose: its own code, its own
            // message, and the caller can act on it without reading logs.
            return BulkItemResult.failed(id, e.getError().code(), detail(e));
        } catch (RuntimeException e) {
            // An unnamed fault: never echo the exception text to a client
            // (it can carry SQL, ids or stack fragments). The log keeps it.
            log.warn("Bulk item {} failed with an unnamed fault", id, e);
            return BulkItemResult.failed(id, NotraiusErrors.BULK_ITEM_FAILED.code(),
                    NotraiusErrors.BULK_ITEM_FAILED.message());
        }
    }

    /** The refusal's own words when it carried any, else the code's message. */
    private String detail(AppException e) {
        Object carried = e.getDetails() == null ? null : e.getDetails().get("message");
        return carried instanceof String text && !text.isBlank() ? text : e.getError().message();
    }
}
