package com.notraius.database.entities.reference;

import jakarta.persistence.Column;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.primarion.database.entities.BaseEntity;

import java.util.UUID;
import jakarta.persistence.Entity;

/**
 * Communication chat membership roles (MEMBER/MODERATOR/ADMIN).
 * Scoped to the CHAT_MEMBERS entity; replaces the cross-service
 * dependency on the shared primarion.role table.
 */
@Entity
@Table(schema = "notraius", name = "role")
@Getter
@Setter
public class CommunicationRole extends BaseEntity {

    @Column(nullable = false, length = 50)
    private String code;

    @Column(nullable = false, length = 100)
    private String label;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "entity_id")
    private Entities entity;

    public UUID getId() { return super.getId(); }
    public String getCode() { return code; }
    public String getLabel() { return label; }
}
