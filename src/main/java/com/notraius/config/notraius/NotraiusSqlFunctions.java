package com.notraius.config.notraius;

import org.hibernate.boot.model.FunctionContributions;
import org.hibernate.boot.model.FunctionContributor;
import org.hibernate.dialect.function.StandardSQLFunction;
import org.hibernate.type.StandardBasicTypes;

/**
 * The database functions the domain's HQL calls.
 *
 * <p>HQL has no way to write Postgres' {@code @@} operator, so V35's full-text
 * predicate is asked through {@code public.tsvector_matches(tsvector, text)}.
 * Registering it here is what makes that call a <em>typed</em> function: the
 * return type is Boolean, the arguments pass through as written, and the query
 * can be a normal JPQL one — with its entity graph, its count query and its
 * {@code Pageable} sort intact. A {@code function('tsvector_matches', …)} call
 * would work too, but an unregistered function has no declared return type and
 * would be compared to a boolean literal blindly.
 *
 * <p>Discovered through {@code META-INF/services/org.hibernate.boot.model.FunctionContributor},
 * so it applies to every {@code EntityManagerFactory} in the host and needs no
 * property to be switched on.
 */
public class NotraiusSqlFunctions implements FunctionContributor {

    /** The one function V35 adds: the trigger-maintained vector against a query. */
    public static final String TSVECTOR_MATCHES = "tsvector_matches";

    @Override
    public void contributeFunctions(FunctionContributions functionContributions) {
        functionContributions.getFunctionRegistry().register(
                TSVECTOR_MATCHES,
                new StandardSQLFunction(TSVECTOR_MATCHES, StandardBasicTypes.BOOLEAN));
    }
}
