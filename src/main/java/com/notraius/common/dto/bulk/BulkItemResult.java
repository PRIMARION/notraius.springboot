package com.notraius.common.dto.bulk;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * One item's fate inside a bulk call.
 * <p>
 * A bulk door does not answer "yes" or "no" for the whole list: the caller sent
 * N independent acts and gets N independent answers. {@link #isOk()} says which,
 * {@link #getValue()} carries the row the act produced when it worked, and
 * {@link #getCode()} carries the same {@code NT-*} code the single-item door
 * would have answered with when it did not — so a client that already handles
 * per-call errors needs no new vocabulary.
 * <p>
 * An item that failed did so in its own transaction: nothing it wrote survives,
 * and nothing it touched is locked for the next item.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class BulkItemResult<T> {

    /** The id the act was asked for, or {@code null} for a payload-shaped create. */
    private UUID id;

    private boolean ok;

    /** The resulting row (or read), null when the item failed. */
    private T value;

    /** The {@code NT-*} code of the refusal, null when the item succeeded. */
    private String code;

    /** The operator-readable refusal, null when the item succeeded. */
    private String message;

    public static <T> BulkItemResult<T> ok(UUID id, T value) {
        return new BulkItemResult<>(id, true, value, null, null);
    }

    public static <T> BulkItemResult<T> failed(UUID id, String code, String message) {
        return new BulkItemResult<>(id, false, null, code, message);
    }
}
