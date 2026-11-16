package com.notraius.modules.engagement;

import com.notraius.database.entities.reference.Type;
import com.notraius.database.repositories.reference.TypeRepository;
import org.primarion.module.engagement.impression.ReactionTypes;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Notarius's reaction vocabulary is its own type catalogue.
 * <p>
 * A kind (LIKE, LOVE, INSIGHTFUL …) is a {@code config.types} row bound to
 * the {@code REACTIONS} entity — the same catalogue that names visit kinds,
 * notice types and deposit types. Adding or renaming a kind is a data
 * operation through the existing catalogue surface; nothing here enumerates a
 * deployment's reaction list, and no rebuild is needed.
 * <p>
 * The weight of a kind is not catalogue data: it is the module's
 * {@code impression.reaction_types} row, keyed by the type id this seam
 * returns. A live kind without that row is refused for new reactions (rather
 * than inventing a weight), while reactions already stored under a retired
 * kind keep resolving through {@link #findById(UUID)}.
 */
@Component
public class NotariusReactionTypes implements ReactionTypes {

    /** The entity code of the reaction-kind namespace in notraius.entities. */
    public static final String REACTIONS_ENTITY = "REACTIONS";
    private static final String ACTIVE = "ACTIVE";

    private final TypeRepository types;

    public NotariusReactionTypes(TypeRepository types) {
        this.types = types;
    }

    @Override
    public Optional<HostType> findLive(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        return types.findByEntityCodeAndCode(REACTIONS_ENTITY,
                        code.trim().toUpperCase(Locale.ROOT))
                .filter(NotariusReactionTypes::isActive)
                .map(NotariusReactionTypes::view);
    }

    @Override
    public Optional<HostType> findById(UUID typeId) {
        if (typeId == null) {
            return Optional.empty();
        }
        return types.findById(typeId)
                .filter(type -> type.getEntity() != null
                        && REACTIONS_ENTITY.equalsIgnoreCase(type.getEntity().getCode()))
                .map(NotariusReactionTypes::view);
    }

    @Override
    public List<HostType> live() {
        return types.findByEntityCodeAndStatusCodeOrderByCodeAsc(REACTIONS_ENTITY, ACTIVE)
                .stream()
                .map(NotariusReactionTypes::view)
                .toList();
    }

    private static boolean isActive(Type type) {
        return type.getStatus() != null && ACTIVE.equalsIgnoreCase(type.getStatus().getCode());
    }

    private static HostType view(Type type) {
        return new HostType(type.getId(), type.getCode(), type.getLabel(), type.getDescription());
    }
}
