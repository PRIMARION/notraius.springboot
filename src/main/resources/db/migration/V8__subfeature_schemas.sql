-- V8__subfeature_schemas.sql (notraius) — subfeature schema alignment
-- Companion to the org.primarion.module.* reorganization. Append-only:
-- ALTER TABLE ... SET SCHEMA carries indexes, unique constraints and FKs.
--
-- The shared library's entities declare their own homes via @Table, so the
-- tables V1/V7 seeded in the misaligned schemas move now:
--
-- chat    — shared chat module tables shipped in the notraius DB
-- support — feedback, feedback_subjects, support_ticket, priority and
--           notifications (notification rides the support schema)
-- webhook — webhooks, webhook_executions
-- storage — attachments
-- notraius — absorbs communication.role (chat membership roles live as a
--            host reference entity, mirroring the logistic estate); no
--            rename needed, notraius.role does not pre-exist here
--
-- Notraius has NO delivery/credential/incident/location domain — those
-- logistic subfeature schemas do not exist here by design.

CREATE SCHEMA IF NOT EXISTS chat;
CREATE SCHEMA IF NOT EXISTS support;
CREATE SCHEMA IF NOT EXISTS webhook;
CREATE SCHEMA IF NOT EXISTS storage;

-- ── host table ──────────────────────────────────────────────────────────
ALTER TABLE notraius.attachments SET SCHEMA storage;

-- ── shared modules in the notraius DB ──────────────────────────────────
ALTER TABLE communication.chats          SET SCHEMA chat;
ALTER TABLE communication.chat_members   SET SCHEMA chat;
ALTER TABLE communication.chat_requests  SET SCHEMA chat;
ALTER TABLE communication.messages       SET SCHEMA chat;
ALTER TABLE communication.message_reads  SET SCHEMA chat;
ALTER TABLE communication.feedback          SET SCHEMA support;
ALTER TABLE communication.feedback_subjects SET SCHEMA support;
ALTER TABLE communication.support_ticket    SET SCHEMA support;
ALTER TABLE communication.priority          SET SCHEMA support;
ALTER TABLE communication.notifications     SET SCHEMA support;
ALTER TABLE security.webhooks           SET SCHEMA webhook;
ALTER TABLE security.webhook_executions SET SCHEMA webhook;

-- ── role anomaly fix ─────────────────────────────────────────────────────
ALTER TABLE communication.role SET SCHEMA notraius;
