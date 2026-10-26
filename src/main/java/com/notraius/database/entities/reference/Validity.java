package com.notraius.database.entities.reference;

import org.primarion.database.entities.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * #EA-08: Validity record for time-bounded notraius rows (orders, courses,
 * shipments) — the platform-wide lifecycle language. The status drives every
 * lifecycle transition; the window carries the time bounds.
 * <p>
 * Quotes and incidents are event rows without a time window
 * and deliberately keep bare status_id (the documented notraius model for
 * them). Orders/courses/shipments are time-bounded and therefore carry a
 * full validity row, exactly like contract/subscription rows.
 */
@Getter
@Setter
@Entity
@Table(schema = "notraius", name = "validity", indexes = {
        @Index(name = "idx_validity_status_id", columnList = "status_id")
})
public class Validity extends BaseEntity {

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "status_id", nullable = false)
    private Status status;

    /** Window open instant — the row's FIRST ACTUAL ACTION instant, never null. */
    @Column(name = "from_date_time", nullable = false)
    private Instant fromDate;

    @Column(name = "to_date_time")
    private Instant toDate;

    /**
     * Doctrine backstop: from_date_time is the first actual action instant.
     * Any creation path that forgot to stamp it inherits the row's birth
     * instant — the column can never persist a NULL.
     */
    @PrePersist
    void stampFromDate() {
        if (fromDate == null) {
            fromDate = Instant.now();
        }
    }
}
