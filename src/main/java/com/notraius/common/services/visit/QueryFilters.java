package com.notraius.common.services.visit;

import com.notraius.common.NotraiusErrors;
import com.notraius.database.repositories.lease.reference.duration.EntitiesRepository;
import com.notraius.database.repositories.lease.reference.duration.StatusRepository;
import org.primarion.module.communication.errors.client.BadRequestException;
import org.springframework.stereotype.Component;

import java.util.Locale;
import com.notraius.database.entities.lease.reference.amenity.Entities;

/**
 * The read doors' filter law — one place, so every catalogue page answers a
 * mistyped filter the same way.
 * <p>
 * <b>A filter that matches nothing must not look like "you have nothing".</b>
 * {@code GET /properties/mine?status=PUBLSIHED} used to be pushed straight into
 * SQL as an equality predicate and answered {@code 200} with an empty page: the
 * owner read that as "I have no properties", when the row they meant was right
 * there under a differently-spelled status. The same silence covered a typo in a
 * catalogue's {@code entityCode}, in the moderation queue's {@code statusCode},
 * and in a rule list's {@code status}. A filter is a question about the
 * catalogue, so the catalogue answers whether the value exists before the page
 * is built — the same ruling the visit and lease pages already follow with
 * {@code NT-0208}/{@code NT-0209}.
 * <p>
 * <b>Nothing here is a hardcoded list.</b> The known values are read from the
 * rows that own them ({@code notraius.statuses}, {@code notraius.entities}), so
 * a status added by a migration is a valid filter the moment it exists, and a
 * status removed is a refusal — no Java constant has to be remembered or
 * forgotten.
 * <p>
 * <b>Free text is bounded and normalised.</b> A search term becomes a
 * {@code tsquery}: inner whitespace is collapsed so a phrase is the words in it,
 * and the length is capped so one request cannot hand the text-search parser an
 * unbounded document to lex. Empty or blank text is "no filter", not "match the
 * empty string".
 */
@Component
public class QueryFilters {

    /**
     * The longest search term a read door accepts. Well beyond any title, code
     * or address a catalogue page searches on, and short enough that the parser
     * cost of one request stays bounded.
     */
    public static final int MAX_SEARCH_LENGTH = 200;

    private final StatusRepository statusRepository;
    private final EntitiesRepository entitiesRepository;

    public QueryFilters(StatusRepository statusRepository, EntitiesRepository entitiesRepository) {
        this.statusRepository = statusRepository;
        this.entitiesRepository = entitiesRepository;
    }

    /**
     * A status filter, validated against that entity's own statuses.
     *
     * @param entityCode the namespace the page lives in ({@code Entities.PROPERTY}, …)
     * @return the canonical status code, or {@code null} when no filter was asked for
     */
    public String status(String entityCode, String raw) {
        String code = upper(raw);
        if (code == null) {
            return null;
        }
        if (statusRepository.findByCodeAndEntityCode(code, entityCode).isEmpty()) {
            throw new BadRequestException(NotraiusErrors.FILTER_INVALID,
                    "'" + raw.trim() + "' is not a " + entityCode + " status");
        }
        return code;
    }

    /**
     * An entity filter, validated against the entity registry — the catalogue
     * namespaces a type or policy can belong to.
     *
     * @return the canonical entity code, or {@code null} when no filter was asked for
     */
    public String entity(String raw) {
        String code = upper(raw);
        if (code == null) {
            return null;
        }
        if (entitiesRepository.findByCode(code).isEmpty()) {
            throw new BadRequestException(NotraiusErrors.FILTER_INVALID,
                    "'" + raw.trim() + "' is not an entity namespace");
        }
        return code;
    }

    /**
     * A free-text filter: trimmed, whitespace-collapsed and length-bounded.
     *
     * @return the search term, or {@code null} when no search was asked for
     */
    public String search(String raw) {
        if (raw == null) {
            return null;
        }
        String collapsed = raw.trim().replaceAll("\\s+", " ");
        if (collapsed.isEmpty()) {
            return null;
        }
        if (collapsed.length() > MAX_SEARCH_LENGTH) {
            throw new BadRequestException(NotraiusErrors.FILTER_INVALID,
                    "search is " + collapsed.length() + " characters; the limit is "
                            + MAX_SEARCH_LENGTH);
        }
        return collapsed;
    }

    private String upper(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed.toUpperCase(Locale.ROOT);
    }
}
