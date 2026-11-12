-- ═══════════════════════════════════════════════════════════════════════════
-- V2__seeders.sql — NOTRAIUS microservice: reference data (SQUASHED)
-- ═══════════════════════════════════════════════════════════════════════════
-- Pure vocabulary rows only: notraius.entities (18) + notraius.statuses (44),
-- -- communication.role (MEMBER/MODERATOR/ADMIN), security.token_purposes (7).
-- Runtime tables stay EMPTY.
-- ═══════════════════════════════════════════════════════════════════════════

--
-- PostgreSQL database dump
--

--restrict LyQc7wNIPP3Ialhd7Qc9HC7hB7oQraz5eSs73fdd9X5rUitoIMMuQJfTUFgEyzk

-- Dumped from database version 18.6 (Ubuntu 18.6-0ubuntu0.26.04.1)
-- Dumped by pg_dump version 18.6 (Ubuntu 18.6-0ubuntu0.26.04.1)

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET transaction_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

--
-- Data for Name: feedback_subjects; Type: TABLE DATA; Schema: communication; Owner: -
--

INSERT INTO communication.feedback_subjects (id, created_at, updated_at, label)
VALUES (gen_random_uuid(), '2026-10-01 08:11:17.412317', '2026-10-01 08:11:17.412317', 'Bug report');
INSERT INTO communication.feedback_subjects (id, created_at, updated_at, label)
VALUES (gen_random_uuid(), '2026-10-01 08:11:17.412317', '2026-10-01 08:11:17.412317', 'Feature request');
INSERT INTO communication.feedback_subjects (id, created_at, updated_at, label)
VALUES (gen_random_uuid(), '2026-10-01 08:11:17.412317', '2026-10-01 08:11:17.412317', 'General feedback');


--
-- Data for Name: priority; Type: TABLE DATA; Schema: communication; Owner: -
--

INSERT INTO communication.priority (id, created_at, updated_at, code, label)
VALUES (gen_random_uuid(), '2026-10-01 08:11:17.408331', '2026-10-01 08:11:17.408331', 'HIGH', 'High');
INSERT INTO communication.priority (id, created_at, updated_at, code, label)
VALUES (gen_random_uuid(), '2026-10-01 08:11:17.408331', '2026-10-01 08:11:17.408331', 'URGENT', 'Urgent');
INSERT INTO communication.priority (id, created_at, updated_at, code, label)
VALUES (gen_random_uuid(), '2026-10-01 08:11:17.408331', '2026-10-01 08:11:17.408331', 'LOW', 'Low');
INSERT INTO communication.priority (id, created_at, updated_at, code, label)
VALUES (gen_random_uuid(), '2026-10-01 08:11:17.408331', '2026-10-01 08:11:17.408331', 'MEDIUM', 'Medium');


--
-- Data for Name: entities; Type: TABLE DATA; Schema: notraius; Owner: -
--

INSERT INTO notraius.entities (id, created_at, updated_at, code)
VALUES (gen_random_uuid(), '2026-10-01 08:11:18.094598', '2026-10-01 08:11:18.094598', 'APPS');
INSERT INTO notraius.entities (id, created_at, updated_at, code)
VALUES (gen_random_uuid(), '2026-10-01 08:11:18.094598', '2026-10-01 08:11:18.094598', 'ATTACHMENTS');
INSERT INTO notraius.entities (id, created_at, updated_at, code)
VALUES (gen_random_uuid(), '2026-10-01 08:11:18.158225', '2026-10-01 08:11:18.158225', 'WEBHOOKS');
INSERT INTO notraius.entities (id, created_at, updated_at, code)
VALUES (gen_random_uuid(), '2026-10-01 08:11:18.773328', '2026-10-01 08:11:18.773328', 'CONSENTS');
INSERT INTO notraius.entities (id, created_at, updated_at, code)
VALUES (gen_random_uuid(), '2026-10-01 08:11:17.393949', '2026-10-01 08:11:17.393949', 'CHAT_MEMBERS');
INSERT INTO notraius.entities (id, created_at, updated_at, code)
VALUES (gen_random_uuid(), '2026-10-01 08:11:17.393949', '2026-10-01 08:11:17.393949', 'CHAT_REQUESTS');
INSERT INTO notraius.entities (id, created_at, updated_at, code)
VALUES (gen_random_uuid(), '2026-10-01 08:11:17.393949', '2026-10-01 08:11:17.393949', 'CHATS');
INSERT INTO notraius.entities (id, created_at, updated_at, code)
VALUES (gen_random_uuid(), '2026-10-01 08:11:17.393949', '2026-10-01 08:11:17.393949', 'FEEDBACK');
INSERT INTO notraius.entities (id, created_at, updated_at, code)
VALUES (gen_random_uuid(), '2026-10-01 08:11:17.393949', '2026-10-01 08:11:17.393949', 'MESSAGES');
INSERT INTO notraius.entities (id, created_at, updated_at, code)
VALUES (gen_random_uuid(), '2026-10-01 08:11:17.393949', '2026-10-01 08:11:17.393949', 'NOTIFICATIONS');
INSERT INTO notraius.entities (id, created_at, updated_at, code)
VALUES (gen_random_uuid(), '2026-10-01 08:11:17.393949', '2026-10-01 08:11:17.393949', 'PRIORITY');
INSERT INTO notraius.entities (id, created_at, updated_at, code)
VALUES (gen_random_uuid(), '2026-10-01 08:11:17.393949', '2026-10-01 08:11:17.393949', 'SUPPORT_TICKET');
INSERT INTO notraius.entities (id, created_at, updated_at, code)
VALUES (gen_random_uuid(), '2026-10-01 08:11:18.68856', '2026-10-01 08:11:18.68856', 'EVENTS');
INSERT INTO notraius.entities (id, created_at, updated_at, code)
VALUES (gen_random_uuid(), '2026-10-01 08:11:17.393949', '2026-10-01 08:11:17.393949', 'LOGS');
INSERT INTO notraius.entities (id, created_at, updated_at, code)
VALUES (gen_random_uuid(), '2026-10-01 08:11:17.393949', '2026-10-01 08:11:17.393949', 'TOKEN_PURPOSES');
INSERT INTO notraius.entities (id, created_at, updated_at, code)
VALUES (gen_random_uuid(), '2026-10-01 08:11:17.393949', '2026-10-01 08:11:17.393949', 'TOKENS');
INSERT INTO notraius.entities (id, created_at, updated_at, code)
VALUES (gen_random_uuid(), '2026-10-01 08:11:18.422714', '2026-10-01 08:11:18.422714', 'SETTINGS');
INSERT INTO notraius.entities (id, created_at, updated_at, code)
VALUES (gen_random_uuid(), '2026-10-01 08:11:18.151333', '2026-10-01 08:11:18.151333', 'SUBSCRIPTIONS');


--
-- Data for Name: role; Type: TABLE DATA; Schema: communication; Owner: -
--

INSERT INTO communication.role (id, created_at, updated_at, code, label, entity_id)
VALUES (gen_random_uuid(), '2026-10-01 08:11:17.681249', '2026-10-01 08:11:17.681249', 'MEMBER', 'Member', (SELECT id FROM notraius.entities WHERE code = 'CHAT_MEMBERS'));
INSERT INTO communication.role (id, created_at, updated_at, code, label, entity_id)
VALUES (gen_random_uuid(), '2026-10-01 08:11:17.681249', '2026-10-01 08:11:17.681249', 'MODERATOR', 'Moderator', (SELECT id FROM notraius.entities WHERE code = 'CHAT_MEMBERS'));
INSERT INTO communication.role (id, created_at, updated_at, code, label, entity_id)
VALUES (gen_random_uuid(), '2026-10-01 08:11:17.681249', '2026-10-01 08:11:17.681249', 'ADMIN', 'Admin', (SELECT id FROM notraius.entities WHERE code = 'CHAT_MEMBERS'));


--
-- Data for Name: course_types; Type: TABLE DATA; Schema: notraius; Owner: -
--



--
-- Data for Name: incident_types; Type: TABLE DATA; Schema: notraius; Owner: -
--



--
-- Data for Name: operations; Type: TABLE DATA; Schema: notraius; Owner: -
--



--
-- Data for Name: statuses; Type: TABLE DATA; Schema: notraius; Owner: -
--

INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id)
VALUES (gen_random_uuid(), '2026-10-01 08:11:17.398746', '2026-10-01 08:11:17.398746', 'BANNED', 'Member banned', (SELECT id FROM notraius.entities WHERE code = 'CHAT_MEMBERS'));
INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id)
VALUES (gen_random_uuid(), '2026-10-01 08:11:17.398746', '2026-10-01 08:11:17.398746', 'CANCELLED', 'Ticket cancelled by owner', (SELECT id FROM notraius.entities WHERE code = 'SUPPORT_TICKET'));
INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id)
VALUES (gen_random_uuid(), '2026-10-01 08:11:17.398746', '2026-10-01 08:11:17.398746', 'READ', 'Notification read', (SELECT id FROM notraius.entities WHERE code = 'NOTIFICATIONS'));
INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id)
VALUES (gen_random_uuid(), '2026-10-01 08:11:17.398746', '2026-10-01 08:11:17.398746', 'ACCEPTED', 'Request accepted', (SELECT id FROM notraius.entities WHERE code = 'CHAT_REQUESTS'));
INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id)
VALUES (gen_random_uuid(), '2026-10-01 08:11:17.398746', '2026-10-01 08:11:17.398746', 'DELETED', 'Ticket soft-deleted', (SELECT id FROM notraius.entities WHERE code = 'SUPPORT_TICKET'));
INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id)
VALUES (gen_random_uuid(), '2026-10-01 08:11:17.398746', '2026-10-01 08:11:17.398746', 'PENDING', 'Request pending', (SELECT id FROM notraius.entities WHERE code = 'CHAT_REQUESTS'));
INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id)
VALUES (gen_random_uuid(), '2026-10-01 08:11:17.398746', '2026-10-01 08:11:17.398746', 'IN_PROGRESS', 'Ticket assigned to an agent', (SELECT id FROM notraius.entities WHERE code = 'SUPPORT_TICKET'));
INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id)
VALUES (gen_random_uuid(), '2026-10-01 08:11:17.398746', '2026-10-01 08:11:17.398746', 'EDITED', 'Message edited', (SELECT id FROM notraius.entities WHERE code = 'MESSAGES'));
INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id)
VALUES (gen_random_uuid(), '2026-10-01 08:11:17.398746', '2026-10-01 08:11:17.398746', 'DELETED', 'Notification soft-deleted', (SELECT id FROM notraius.entities WHERE code = 'NOTIFICATIONS'));
INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id)
VALUES (gen_random_uuid(), '2026-10-01 08:11:17.398746', '2026-10-01 08:11:17.398746', 'DELETED', 'Message soft-deleted', (SELECT id FROM notraius.entities WHERE code = 'MESSAGES'));
INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id)
VALUES (gen_random_uuid(), '2026-10-01 08:11:17.398746', '2026-10-01 08:11:17.398746', 'ACTIVE', 'Member active', (SELECT id FROM notraius.entities WHERE code = 'CHAT_MEMBERS'));
INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id)
VALUES (gen_random_uuid(), '2026-10-01 08:11:17.398746', '2026-10-01 08:11:17.398746', 'UNREAD', 'Notification not yet read', (SELECT id FROM notraius.entities WHERE code = 'NOTIFICATIONS'));
INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id)
VALUES (gen_random_uuid(), '2026-10-01 08:11:17.398746', '2026-10-01 08:11:17.398746', 'REJECTED', 'Request rejected', (SELECT id FROM notraius.entities WHERE code = 'CHAT_REQUESTS'));
INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id)
VALUES (gen_random_uuid(), '2026-10-01 08:11:17.398746', '2026-10-01 08:11:17.398746', 'DELETED', 'Chat soft-deleted', (SELECT id FROM notraius.entities WHERE code = 'CHATS'));
INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id)
VALUES (gen_random_uuid(), '2026-10-01 08:11:17.398746', '2026-10-01 08:11:17.398746', 'ACTIVE', 'Chat active', (SELECT id FROM notraius.entities WHERE code = 'CHATS'));
INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id)
VALUES (gen_random_uuid(), '2026-10-01 08:11:17.398746', '2026-10-01 08:11:17.398746', 'OPEN', 'Ticket created, awaiting support', (SELECT id FROM notraius.entities WHERE code = 'SUPPORT_TICKET'));
INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id)
VALUES (gen_random_uuid(), '2026-10-01 08:11:17.398746', '2026-10-01 08:11:17.398746', 'CLOSED', 'Ticket closed', (SELECT id FROM notraius.entities WHERE code = 'SUPPORT_TICKET'));
INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id)
VALUES (gen_random_uuid(), '2026-10-01 08:11:17.398746', '2026-10-01 08:11:17.398746', 'SENT', 'Message sent', (SELECT id FROM notraius.entities WHERE code = 'MESSAGES'));
INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id)
VALUES (gen_random_uuid(), '2026-10-01 08:11:17.398746', '2026-10-01 08:11:17.398746', 'DELETED', 'Feedback soft-deleted', (SELECT id FROM notraius.entities WHERE code = 'FEEDBACK'));
INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id)
VALUES (gen_random_uuid(), '2026-10-01 08:11:17.398746', '2026-10-01 08:11:17.398746', 'CANCELLED', 'Request cancelled', (SELECT id FROM notraius.entities WHERE code = 'CHAT_REQUESTS'));
INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id)
VALUES (gen_random_uuid(), '2026-10-01 08:11:17.398746', '2026-10-01 08:11:17.398746', 'LEFT', 'Member left the chat', (SELECT id FROM notraius.entities WHERE code = 'CHAT_MEMBERS'));
INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id)
VALUES (gen_random_uuid(), '2026-10-01 08:11:17.692534', '2026-10-01 08:11:17.692534', 'OPEN', 'Feedback submitted, awaiting review', (SELECT id FROM notraius.entities WHERE code = 'FEEDBACK'));
INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id)
VALUES (gen_random_uuid(), '2026-10-01 08:11:18.098833', '2026-10-01 08:11:18.098833', 'DELETED', 'App soft-deleted (restorable)', (SELECT id FROM notraius.entities WHERE code = 'APPS'));
INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id)
VALUES (gen_random_uuid(), '2026-10-01 08:11:18.098833', '2026-10-01 08:11:18.098833', 'SUSPENDED', 'App temporarily disabled', (SELECT id FROM notraius.entities WHERE code = 'APPS'));
INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id)
VALUES (gen_random_uuid(), '2026-10-01 08:11:18.098833', '2026-10-01 08:11:18.098833', 'ACTIVE', 'App live and usable', (SELECT id FROM notraius.entities WHERE code = 'APPS'));
INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id)
VALUES (gen_random_uuid(), '2026-10-01 08:11:18.098833', '2026-10-01 08:11:18.098833', 'DELETED', 'Attachment soft-deleted', (SELECT id FROM notraius.entities WHERE code = 'ATTACHMENTS'));
INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id)
VALUES (gen_random_uuid(), '2026-10-01 08:11:18.098833', '2026-10-01 08:11:18.098833', 'ACTIVE', 'Attachment live and referenced', (SELECT id FROM notraius.entities WHERE code = 'ATTACHMENTS'));
INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id)
VALUES (gen_random_uuid(), '2026-10-01 08:11:18.15467', '2026-10-01 08:11:18.15467', 'EXPIRED', 'Subscription period ended', (SELECT id FROM notraius.entities WHERE code = 'SUBSCRIPTIONS'));
INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id)
VALUES (gen_random_uuid(), '2026-10-01 08:11:18.15467', '2026-10-01 08:11:18.15467', 'ACTIVE', 'Subscription live', (SELECT id FROM notraius.entities WHERE code = 'SUBSCRIPTIONS'));
INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id)
VALUES (gen_random_uuid(), '2026-10-01 08:11:18.161595', '2026-10-01 08:11:18.161595', 'DELETED', 'Webhook soft-deleted (registration retained for audit)', (SELECT id FROM notraius.entities WHERE code = 'WEBHOOKS'));
INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id)
VALUES (gen_random_uuid(), '2026-10-01 08:11:18.161595', '2026-10-01 08:11:18.161595', 'ACTIVE', 'Webhook live and dispatching', (SELECT id FROM notraius.entities WHERE code = 'WEBHOOKS'));
INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id)
VALUES (gen_random_uuid(), '2026-10-01 08:11:18.426102', '2026-10-01 08:11:18.426102', 'ACTIVE', 'Platform setting active', (SELECT id FROM notraius.entities WHERE code = 'SETTINGS'));
INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id)
VALUES (gen_random_uuid(), '2026-10-01 08:11:18.556766', '2026-10-01 08:11:18.556766', 'DELETED', 'Platform membership removed', (SELECT id FROM notraius.entities WHERE code = 'SETTINGS'));
INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id)
VALUES (gen_random_uuid(), '2026-10-01 08:11:18.692614', '2026-10-01 08:11:18.692614', 'DISABLED', 'Event paused by its owner', (SELECT id FROM notraius.entities WHERE code = 'EVENTS'));
INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id)
VALUES (gen_random_uuid(), '2026-10-01 08:11:18.692614', '2026-10-01 08:11:18.692614', 'PENDING', 'Event confirmed by the user, waiting to activate', (SELECT id FROM notraius.entities WHERE code = 'EVENTS'));
INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id)
VALUES (gen_random_uuid(), '2026-10-01 08:11:18.692614', '2026-10-01 08:11:18.692614', 'REQUESTED', 'Event requested by an actor, awaiting user confirmation', (SELECT id FROM notraius.entities WHERE code = 'EVENTS'));
INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id)
VALUES (gen_random_uuid(), '2026-10-01 08:11:18.692614', '2026-10-01 08:11:18.692614', 'EXPIRED', 'Event window closed', (SELECT id FROM notraius.entities WHERE code = 'EVENTS'));
INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id)
VALUES (gen_random_uuid(), '2026-10-01 08:11:18.692614', '2026-10-01 08:11:18.692614', 'CANCELLED', 'Event withdrawn', (SELECT id FROM notraius.entities WHERE code = 'EVENTS'));
INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id)
VALUES (gen_random_uuid(), '2026-10-01 08:11:18.692614', '2026-10-01 08:11:18.692614', 'ACTIVE', 'Event live and executing', (SELECT id FROM notraius.entities WHERE code = 'EVENTS'));
INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id)
VALUES (gen_random_uuid(), '2026-10-01 08:11:18.778189', '2026-10-01 08:11:18.778189', 'PENDING', 'Consent requested, awaiting grantor decision', (SELECT id FROM notraius.entities WHERE code = 'CONSENTS'));
INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id)
VALUES (gen_random_uuid(), '2026-10-01 08:11:18.778189', '2026-10-01 08:11:18.778189', 'GRANTED', 'Consent granted and live inside its window', (SELECT id FROM notraius.entities WHERE code = 'CONSENTS'));
INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id)
VALUES (gen_random_uuid(), '2026-10-01 08:11:18.778189', '2026-10-01 08:11:18.778189', 'REVOKED', 'Consent revoked (by grantor, demotion or window close)', (SELECT id FROM notraius.entities WHERE code = 'CONSENTS'));
INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id)
VALUES (gen_random_uuid(), '2026-10-01 08:11:18.778189', '2026-10-01 08:11:18.778189', 'DENIED', 'Consent denied by the grantor', (SELECT id FROM notraius.entities WHERE code = 'CONSENTS'));
INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id)
VALUES (gen_random_uuid(), '2026-10-01 08:11:19.199123', '2026-10-01 08:11:19.199123', 'INACTIVE', 'Row replaced by a newer one', (SELECT id FROM notraius.entities WHERE code = 'SUBSCRIPTIONS'));


--
-- Data for Name: token_purposes; Type: TABLE DATA; Schema: security; Owner: -
--

INSERT INTO security.token_purposes (id, created_at, updated_at, code, description, label, timeframe)
VALUES (gen_random_uuid(), '2026-10-01 08:11:17.4045', '2026-10-01 08:11:17.4045', 'PRIVATE_KEY', 'Enterprise Private Key', 'Hash of an enterprise-owned API private key', NULL);
INSERT INTO security.token_purposes (id, created_at, updated_at, code, description, label, timeframe)
VALUES (gen_random_uuid(), '2026-10-01 08:11:17.4045', '2026-10-01 08:11:17.4045', 'PUBLIC_KEY', 'Enterprise Public Key', 'Public key of an enterprise-owned API keypair', NULL);
INSERT INTO security.token_purposes (id, created_at, updated_at, code, description, label, timeframe)
VALUES (gen_random_uuid(), '2026-10-01 08:11:17.4045', '2026-10-01 08:11:17.4045', 'ACCESS_TOKEN', 'Access Token', 'Short-lived JWT access token (15 min)', 15);
INSERT INTO security.token_purposes (id, created_at, updated_at, code, description, label, timeframe)
VALUES (gen_random_uuid(), '2026-10-01 08:11:17.4045', '2026-10-01 08:11:17.4045', 'REFRESH_TOKEN', 'Refresh Token', 'Long-lived refresh token for rotation (30 days)', 43200);
INSERT INTO security.token_purposes (id, created_at, updated_at, code, description, label, timeframe)
VALUES (gen_random_uuid(), '2026-10-01 08:11:17.4045', '2026-10-01 08:11:17.4045', 'MFA_BACKUP', 'MFA Backup Code', 'One-time backup code for MFA recovery', NULL);
INSERT INTO security.token_purposes (id, created_at, updated_at, code, description, label, timeframe)
VALUES (gen_random_uuid(), '2026-10-01 08:11:17.4045', '2026-10-01 08:11:17.4045', 'NOTRAIUS_QR', 'Token for pickup/dropoff notraius QR verification', 'Logistic QR', 60);
INSERT INTO security.token_purposes (id, created_at, updated_at, code, description, label, timeframe)
VALUES (gen_random_uuid(), '2026-10-01 08:11:18.34262', '2026-10-01 08:11:18.34262', 'SHIPMENT', 'Token for a shipment leg delivery verification (pickup/dropoff QR)', 'Shipment', 60);


--
-- PostgreSQL database dump complete
--

--unrestrict LyQc7wNIPP3Ialhd7Qc9HC7hB7oQraz5eSs73fdd9X5rUitoIMMuQJfTUFgEyzk

