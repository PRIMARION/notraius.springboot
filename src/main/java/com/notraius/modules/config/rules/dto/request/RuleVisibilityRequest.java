package com.notraius.modules.config.rules.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Flip a rule's visibility (creator only).
 * <p>
 * PRIVATE → PUBLIC is always allowed: sharing more is the creator's
 * sovereignty and only adds readers. PUBLIC → PRIVATE is refused while another
 * person's property still references the rule, because the flip would orphan a
 * selection that owner could no longer see or manage; when only the creator's
 * own properties reference it, the flip is the creator's to make.
 * <p>
 * The test is "does anyone else reference it" — deliberately different from the
 * disable lock's "does anyone at all reference it".
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class RuleVisibilityRequest {

    /** PUBLIC or PRIVATE. */
    @NotBlank
    @Size(max = 12)
    private String visibility;
}
