package com.notraius.database.entities.reference;

/**
 * What kind of answer an amenity takes (PLAN.md Part 3.3): boolean for
 * yes/no amenities (a pool exists or not), integer for counts (parking
 * spaces), string for free descriptions (heating type).
 * <p>
 * The type is fixed at birth and immutable afterwards — flipping a boolean
 * amenity to a string one would reinterpret every holding's stored value.
 * A different type therefore means a different amenity, not an edited one.
 */
public enum AmenityDataType {
    BOOLEAN,
    INTEGER,
    STRING;

    /** Case-insensitive lookup; null when the token names no data type. */
    public static AmenityDataType from(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            return valueOf(token.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
