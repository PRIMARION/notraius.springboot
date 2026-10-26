-- ═══════════════════════════════════════════════════════════════════════
-- V10 — entity_id becomes a real FK (reframe Phase 3, notraius port).
--
-- Same doctrine as contract V13 / pay V10 / primarion V11: every
-- registry-pointer column enforces referential integrity against the
-- host's LOCAL registry (notraius.entities — each host owns its own
-- registry copy; there is no cross-database FK). Two notraius tables were
-- already born with the FK (notraius.role, notraius.statuses); the rest
-- carried a bare uuid:
--
--   chat.messages.entity_id           -> fk_messages_entity
--   storage.attachments.entity_id     -> fk_attachments_entity
--   support.notifications.entity_id   -> fk_notifications_entity
--   support.support_ticket.entity_id  -> fk_support_ticket_entity
--
-- Pre-flight on every verification database: 0 dangling entity_id values.
-- ADD CONSTRAINT validates eagerly and aborts the transaction on any
-- orphan — no data mutation here.
-- ═══════════════════════════════════════════════════════════════════════

ALTER TABLE chat.messages
    ADD CONSTRAINT fk_messages_entity
    FOREIGN KEY (entity_id) REFERENCES notraius.entities (id);

ALTER TABLE storage.attachments
    ADD CONSTRAINT fk_attachments_entity
    FOREIGN KEY (entity_id) REFERENCES notraius.entities (id);

ALTER TABLE support.notifications
    ADD CONSTRAINT fk_notifications_entity
    FOREIGN KEY (entity_id) REFERENCES notraius.entities (id);

ALTER TABLE support.support_ticket
    ADD CONSTRAINT fk_support_ticket_entity
    FOREIGN KEY (entity_id) REFERENCES notraius.entities (id);
