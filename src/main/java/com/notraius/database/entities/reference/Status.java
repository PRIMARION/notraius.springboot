package com.notraius.database.entities.reference;

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

@Entity
@Table(schema = "notraius", name = "statuses",
        uniqueConstraints = @UniqueConstraint(name = "uk_statuses_code_entity", columnNames = {"code", "entity_id"}),
        indexes = {
                @Index(name = "idx_statuses_entity_id", columnList = "entity_id")
        })
@Getter
@Setter
public class Status extends BaseEntity {

    @Column(nullable = false, length = 50)
    private String code;

    @Column(length = 255)
    private String description;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "entity_id", nullable = false)
    private Entities entity;
}
