package com.notraius.modules.database;

import com.notraius.database.entities.asset.asset.Property;
import com.notraius.database.entities.lease.lease.records.Lease;
import com.notraius.database.entities.lease.visit.Visit;
import jakarta.persistence.Column;
import jakarta.persistence.Version;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.primarion.module.communication.errors.web.GlobalExceptionHandler;

/**
 * The concurrency contract of the three aggregates whose rows are money and
 * legal truth. It runs without a database, so the property is guarded in every
 * build, not only where someone exports {@code NOTRAIUS_SQL_URL}.
 * <p>
 * Two numbers live on each of them and they are not interchangeable:
 * <ul>
 *   <li>{@code version} is the <b>content revision</b> the client sees — birth
 *       is one, every accepted edit increments it. It is deliberately a plain
 *       column: hand-incrementing it proves nothing about races.</li>
 *   <li>{@code lock_version} is <b>Hibernate's</b> token — mapped by
 *       {@link Version}, it puts {@code AND lock_version = <loaded>} on every
 *       UPDATE, so a write that lost the race affects no row and raises
 *       {@code OptimisticLockException} (409, handled once, in the shared
 *       {@code GlobalExceptionHandler}).</li>
 * </ul>
 * Swapping them — or mapping the revision as the token — would either resurrect
 * the silent lost update or make the visible revision jump for reasons clients
 * cannot see. This test pins the separation.
 */
class OptimisticLockingContractTest {

    private static final List<Class<?>> AGGREGATES = List.of(Lease.class, Visit.class, Property.class);

    @Test
    @DisplayName("every money-and-legal aggregate carries Hibernate's token on lock_version")
    void everyAggregateCarriesTheToken() throws Exception {
        for (Class<?> aggregate : AGGREGATES) {
            Field token = field(aggregate, "lockVersion");

            assertTrue(token.isAnnotationPresent(Version.class),
                    aggregate.getSimpleName() + ".lockVersion must be @Version, or the row has no "
                            + "concurrency control at all");

            Column column = token.getAnnotation(Column.class);
            assertNotNull(column, aggregate.getSimpleName() + ".lockVersion needs an explicit column");
            assertEquals("lock_version", column.name(),
                    aggregate.getSimpleName() + " must not reuse the content revision column as the token");
            assertFalse(column.nullable(), "a null token cannot be compared");

            // It is internal: it must not leak into the API as a second "version".
            assertFalse(token.getType().isPrimitive(), "a primitive cannot distinguish 'unset' on insert");
        }
    }

    @Test
    @DisplayName("the client-visible revision stays a plain column, never the token")
    void theRevisionIsNotTheToken() throws Exception {
        for (Class<?> aggregate : AGGREGATES) {
            Field revision = field(aggregate, "version");

            assertFalse(revision.isAnnotationPresent(Version.class),
                    aggregate.getSimpleName() + ".version is the content revision clients read; mapping "
                            + "it as @Version would make it jump for reasons clients cannot observe");
        }
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        return type.getDeclaredField(name);
    }
}
