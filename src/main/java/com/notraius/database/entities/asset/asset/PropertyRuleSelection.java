package com.notraius.database.entities.asset.asset;

import com.notraius.database.entities.asset.config.Rule;
import com.notraius.database.entities.lease.reference.amenity.Validity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.primarion.database.entities.BaseEntity;

import java.util.UUID;
import com.notraius.database.entities.asset.asset.Property;

/**
 * A rule the property holds, with its own window and its own version (PLAN.md
 * 4.1).
 * <p>
 * The pool an owner selects from is precisely defined: <b>his own rules plus
 * every public rule anyone created</b>. Private rules of other users are never
 * queried, not filtered after retrieval. And once a rule <i>is</i> selected, the
 * property's read path shows it to every viewer of the property regardless of
 * its visibility — selecting stays exclusive, viewing through the property does
 * not (the 3.1 chicken-and-egg ruling).
 * <p>
 * <b>No unique (property, rule) constraint</b>: one property may hold the same
 * rule for different windows without contradiction, so the row — not the pair —
 * is the identity of a selection. The lifecycle lives in the {@link Validity}
 * row, the estate's never-null validity model: the window says when the rule
 * applies (permanently when {@code toDate} is null).
 */
@Entity
@Table(name = "rules", schema = "property", indexes = {
        @Index(name = "idx_property_rules_property_id", columnList = "property_id"),
        @Index(name = "idx_property_rules_rule_id", columnList = "rule_id"),
        @Index(name = "idx_property_rules_validity_id", columnList = "validity_id")
})
@Getter
@Setter
public class PropertyRuleSelection extends BaseEntity {

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "property_id", nullable = false)
    private Property property;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "rule_id", nullable = false)
    private Rule rule;

    /** When this rule applies to this property (permanent when the end is null). */
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "validity_id", nullable = false)
    private Validity validity;

    @Column(nullable = false)
    private Integer version;

    @Column(name = "created_by")
    private UUID createdBy;
}
