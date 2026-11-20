package com.notraius.common.dto.bulk;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;
import com.notraius.common.dto.bulk.BulkIdsRequest;

/**
 * The body of a bulk <i>create</i>: the payloads, in one round trip.
 * <p>
 * It is the same envelope as {@link BulkIdsRequest} with the ids replaced by
 * whole payloads, because a create has no id yet — the item's answer is the row
 * it made. {@code @Valid} on the list is what makes each payload answer for
 * itself before any of them runs: a malformed item refuses the whole envelope
 * (400, nothing executed), while a well-formed item the domain later refuses
 * (a duplicate, a missing reference) is a per-item result.
 */
@Getter
@Setter
@NoArgsConstructor
public class BulkItemsRequest<T> {

    @NotEmpty(message = "A bulk call needs at least one item")
    @Size(max = BulkIdsRequest.MAX_ITEMS,
            message = "A bulk call carries at most " + BulkIdsRequest.MAX_ITEMS + " items")
    @Valid
    private List<T> items;
}
