package com.notraius.common.dto.bulk;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;
import com.notraius.common.dto.bulk.BulkIdsRequest;
import com.notraius.common.dto.bulk.BulkItemResult;

/**
 * The answer to a bulk call: the counts, and every item's own result in the
 * order the ids were given.
 * <p>
 * <b>Why 200 and not 207.</b> A bulk call is many independent single calls
 * folded into one round trip; the envelope succeeded — it was understood,
 * authorised and executed — even when some of the items were refused. Those
 * refusals are business answers the caller must read per item
 * ({@link BulkItemResult#getCode()}), not a transport-level story. A 4xx is
 * still answered for the envelope itself: an empty list, more than
 * {@link BulkIdsRequest#MAX_ITEMS} ids, a missing permission or a rate limit
 * refusal never reaches an item.
 * <p>
 * {@code requested == succeeded + failed} always holds; a client can assert it
 * and page through {@link #getResults()} to retry exactly the failed ids.
 */
@Getter
@Setter
@NoArgsConstructor
public class BulkResponse<T> {

    private int requested;
    private int succeeded;
    private int failed;
    private List<BulkItemResult<T>> results;

    public BulkResponse(List<BulkItemResult<T>> results) {
        this.results = results;
        this.requested = results.size();
        this.succeeded = (int) results.stream().filter(BulkItemResult::isOk).count();
        this.failed = requested - succeeded;
    }

    /** The successful values, in order — what a caller who ignores failures wants. */
    public List<T> values() {
        return results.stream().filter(BulkItemResult::isOk)
                .map(BulkItemResult::getValue).toList();
    }
}
