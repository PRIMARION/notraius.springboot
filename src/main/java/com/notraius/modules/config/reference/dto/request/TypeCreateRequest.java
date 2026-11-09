package com.notraius.modules.config.reference.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * Mint a type under an entity (admin tier).
 * <p>
 * {@code entityCode} is the namespace — the entity whose kinds this type
 * describes ({@code PROPERTY}, {@code PRICINGS}, {@code DURATION_UNIT}, …).
 * {@code parentId} is null for a root; when given, the parent's entity must
 * equal this type's entity, because the hierarchy never crosses namespaces.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class TypeCreateRequest {

    @NotBlank
    @Size(max = 50)
    private String entityCode;

    /** Parent kind; null = root. */
    private UUID parentId;

    /** Machine identity, unique inside the entity. Normalised to upper case. */
    @NotBlank
    @Size(max = 50)
    @Pattern(regexp = "^[A-Za-z][A-Za-z0-9_]*$",
            message = "code must start with a letter and contain only letters, digits and underscores")
    private String code;

    @NotBlank
    @Size(max = 100)
    private String label;

    @Size(max = 2000)
    private String description;
}
