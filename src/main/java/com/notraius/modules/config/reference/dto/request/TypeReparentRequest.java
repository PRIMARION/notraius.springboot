package com.notraius.modules.config.reference.dto.request;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * Move a type in the hierarchy (admin tier).
 * <p>
 * The body states the type's parent outright — a PUT on the parent, not a
 * patch of one field — so a null {@code parentId} means "make this a root".
 * The service refuses a parent from another entity (the hierarchy stays
 * inside its namespace) and any move that would place the type beneath its
 * own descendant.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class TypeReparentRequest {

    /** New parent; null = promote to a root. */
    private UUID parentId;
}
