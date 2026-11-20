package com.notraius.database.entities.asset.asset;

import com.notraius.database.entities.asset.config.Policy;
import com.notraius.database.entities.lease.reference.status.Status;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;
import org.primarion.database.entities.BaseEntity;

import java.math.BigDecimal;
import java.util.UUID;
import com.notraius.database.entities.asset.asset.Pricing;

/**
 * The owner's number against an admin standard, on one pricing (PLAN.md 4.3).
 * <p>
 * The worked example is the whole design: the admin sets ADVANCE with bounds
 * 3–6; the owner sets 4 on a monthly pricing — four <i>months</i> of advance,
 * because the pricing's duration supplies the unit. The standard is the admin's,
 * the number is the owner's, and the pricing is the context that makes the
 * number mean something.
 * <p>
 * <b>(pricing, policy) is the identity</b>: "the same policy valued twice on one
 * pricing is one row, not two" — re-setting edits this row and bumps
 * {@link #version}, it never accumulates a duplicate.
 * <p>
 * <b>Status is a conversation, not a verdict.</b> {@code PAUSED} means the
 * standard behind the value was disabled (the value is kept, not edited);
 * {@code FLAGGED} means the admin has since tightened the bounds and this number
 * now sits outside them — visible for review, never silently rewritten.
 */
@Entity
@Table(name = "policies", schema = "property",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_property_policies_pricing_policy", columnNames = {"pricing_id", "policy_id"}),
        indexes = {
                @Index(name = "idx_property_policies_pricing_id", columnList = "pricing_id"),
                @Index(name = "idx_property_policies_policy_id", columnList = "policy_id"),
                @Index(name = "idx_property_policies_status_id", columnList = "status_id")
        })
@Getter
@Setter
public class PropertyPolicyValue extends BaseEntity {

    /** The context that gives the number its unit and its currency. */
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "pricing_id", nullable = false)
    private Pricing pricing;

    /** The admin standard this number answers. */
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "policy_id", nullable = false)
    private Policy policy;

    @Column(precision = 15, scale = 2)
    private BigDecimal value;

    /** ACTIVE / PAUSED / FLAGGED. */
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "status_id", nullable = false)
    private Status status;

    @Column(nullable = false)
    private Integer version;

    @Column(name = "created_by")
    private UUID createdBy;
}
