package com.notraius.common.dto.bulk;

import com.notraius.common.NotraiusErrors;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.primarion.module.errors.BadRequestException;

import java.util.List;
import java.util.UUID;

/**
 * The body of every bulk door: the ids the caller wants acted on, in one round
 * trip instead of N.
 * <p>
 * <b>The cap is a guard, not a convenience.</b> {@code @Size(max = 100)} is the
 * only thing standing between one authenticated request and an unbounded fan-out
 * of transactions: the endpoint's own rate rule, plan limit and consent checks
 * are charged <i>once</i> per HTTP call, so the batch size is what bounds the
 * work a single permit can buy. A client that needs more does more calls, which
 * is exactly the point of a rate limit.
 * <p>
 * The list is not a set: a repeated id is a repeated act, and the domain decides
 * whether the second one is a no-op or a refusal (both are per-item results, so
 * one bad id never hides the others). Order is preserved in
 * {@link BulkResponse#getResults()}.
 */
@Getter
@Setter
@NoArgsConstructor
public class BulkIdsRequest {

    /** The cap every bulk door shares. */
    public static final int MAX_ITEMS = 100;

    @NotEmpty(message = "A bulk call needs at least one id")
    @Size(max = MAX_ITEMS, message = "A bulk call carries at most " + MAX_ITEMS + " ids")
    private List<@NotNull(message = "A bulk id cannot be null") UUID> ids;

    /**
     * The same two limits for the query-string doors ({@code GET /batch?ids=} and
     * {@code DELETE /bulk?ids=}), which have no body to validate.
     * <p>
     * The cap is enforced here rather than by a bean-validation annotation on
     * the parameter so the refusal comes back as the same clean {@code ApiError}
     * as every other client fault: a violated method constraint would otherwise
     * escape as a {@code ConstraintViolationException} and answer 500.
     */
    public static List<UUID> checked(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new BadRequestException(NotraiusErrors.BULK_EMPTY, NotraiusErrors.BULK_EMPTY.message());
        }
        if (ids.size() > MAX_ITEMS) {
            throw new BadRequestException(NotraiusErrors.BULK_LIMIT_EXCEEDED,
                    NotraiusErrors.BULK_LIMIT_EXCEEDED.message());
        }
        if (ids.stream().anyMatch(java.util.Objects::isNull)) {
            throw new BadRequestException(NotraiusErrors.BULK_EMPTY, "A bulk id cannot be null");
        }
        return ids;
    }
}
