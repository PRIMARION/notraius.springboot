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

INSERT INTO communication.feedback_subjects (id, created_at, updated_at, label) VALUES ('71e26b56-3686-4495-b023-6c70d9721906', '2026-10-01 08:11:17.412317', '2026-10-01 08:11:17.412317', 'Bug report');
INSERT INTO communication.feedback_subjects (id, created_at, updated_at, label) VALUES ('6363a54b-19aa-40cf-9378-c00c90d99afc', '2026-10-01 08:11:17.412317', '2026-10-01 08:11:17.412317', 'Feature request');
INSERT INTO communication.feedback_subjects (id, created_at, updated_at, label) VALUES ('ab7cb799-70ff-4a03-952a-3d843fb629eb', '2026-10-01 08:11:17.412317', '2026-10-01 08:11:17.412317', 'General feedback');


--
-- Data for Name: priority; Type: TABLE DATA; Schema: communication; Owner: -
--

INSERT INTO communication.priority (id, created_at, updated_at, code, label) VALUES ('b5d30745-9ab1-4268-9a3c-e0dd040b197f', '2026-10-01 08:11:17.408331', '2026-10-01 08:11:17.408331', 'HIGH', 'High');
INSERT INTO communication.priority (id, created_at, updated_at, code, label) VALUES ('2bd938f8-bea4-4f8d-a813-bdcc30d4b2ea', '2026-10-01 08:11:17.408331', '2026-10-01 08:11:17.408331', 'URGENT', 'Urgent');
INSERT INTO communication.priority (id, created_at, updated_at, code, label) VALUES ('7cb1bbb4-619d-45d7-9166-cd689e6b62ac', '2026-10-01 08:11:17.408331', '2026-10-01 08:11:17.408331', 'LOW', 'Low');
INSERT INTO communication.priority (id, created_at, updated_at, code, label) VALUES ('4f1d2074-f742-40e3-8666-69c90c2389f3', '2026-10-01 08:11:17.408331', '2026-10-01 08:11:17.408331', 'MEDIUM', 'Medium');


--
-- Data for Name: entities; Type: TABLE DATA; Schema: notraius; Owner: -
--

INSERT INTO notraius.entities (id, created_at, updated_at, code) VALUES ('10dd0c50-6b9b-4e4d-8311-8dbf3b3c73b4', '2026-10-01 08:11:18.094598', '2026-10-01 08:11:18.094598', 'APPS');
INSERT INTO notraius.entities (id, created_at, updated_at, code) VALUES ('d282952f-f822-4594-85da-cd6b62c7ca21', '2026-10-01 08:11:18.094598', '2026-10-01 08:11:18.094598', 'ATTACHMENTS');
INSERT INTO notraius.entities (id, created_at, updated_at, code) VALUES ('012645ca-7936-4444-a32e-b4d6679b86b7', '2026-10-01 08:11:18.158225', '2026-10-01 08:11:18.158225', 'WEBHOOKS');
INSERT INTO notraius.entities (id, created_at, updated_at, code) VALUES ('ed2bf644-9c29-49f3-9244-4a8a6ce48cdb', '2026-10-01 08:11:18.773328', '2026-10-01 08:11:18.773328', 'CONSENTS');
INSERT INTO notraius.entities (id, created_at, updated_at, code) VALUES ('3284de2b-8a4f-44e5-822c-65c88c9bc09b', '2026-10-01 08:11:17.393949', '2026-10-01 08:11:17.393949', 'CHAT_MEMBERS');
INSERT INTO notraius.entities (id, created_at, updated_at, code) VALUES ('e1b11718-cead-436b-be0f-0a2aa57a76a3', '2026-10-01 08:11:17.393949', '2026-10-01 08:11:17.393949', 'CHAT_REQUESTS');
INSERT INTO notraius.entities (id, created_at, updated_at, code) VALUES ('c6af475a-4529-4c6b-a552-e5d75d06f64a', '2026-10-01 08:11:17.393949', '2026-10-01 08:11:17.393949', 'CHATS');
INSERT INTO notraius.entities (id, created_at, updated_at, code) VALUES ('1fb7c159-c66c-4fd9-aa67-54bc514dd5cf', '2026-10-01 08:11:17.393949', '2026-10-01 08:11:17.393949', 'FEEDBACK');
INSERT INTO notraius.entities (id, created_at, updated_at, code) VALUES ('f5262381-9975-4f94-9628-5d383f684d7f', '2026-10-01 08:11:17.393949', '2026-10-01 08:11:17.393949', 'MESSAGES');
INSERT INTO notraius.entities (id, created_at, updated_at, code) VALUES ('952e750c-71e0-4bda-b3a8-de072e555540', '2026-10-01 08:11:17.393949', '2026-10-01 08:11:17.393949', 'NOTIFICATIONS');
INSERT INTO notraius.entities (id, created_at, updated_at, code) VALUES ('30ae8727-00eb-4a02-8eb2-0bec2e433566', '2026-10-01 08:11:17.393949', '2026-10-01 08:11:17.393949', 'PRIORITY');
INSERT INTO notraius.entities (id, created_at, updated_at, code) VALUES ('fcadbb0a-737c-4b94-a6f6-4129abc22928', '2026-10-01 08:11:17.393949', '2026-10-01 08:11:17.393949', 'SUPPORT_TICKET');
INSERT INTO notraius.entities (id, created_at, updated_at, code) VALUES ('61d29810-c760-4669-922c-c34caa8c433d', '2026-10-01 08:11:18.68856', '2026-10-01 08:11:18.68856', 'EVENTS');
INSERT INTO notraius.entities (id, created_at, updated_at, code) VALUES ('ffb70344-0b63-43eb-850a-d0928fbc9c72', '2026-10-01 08:11:17.393949', '2026-10-01 08:11:17.393949', 'LOGS');
INSERT INTO notraius.entities (id, created_at, updated_at, code) VALUES ('cc8c0ab1-75ef-44dc-8c1f-00e412456fa3', '2026-10-01 08:11:17.393949', '2026-10-01 08:11:17.393949', 'TOKEN_PURPOSES');
INSERT INTO notraius.entities (id, created_at, updated_at, code) VALUES ('82e9fcde-4c9f-45a9-9e9b-9a432d68a3d6', '2026-10-01 08:11:17.393949', '2026-10-01 08:11:17.393949', 'TOKENS');
INSERT INTO notraius.entities (id, created_at, updated_at, code) VALUES ('f8e4dd50-f256-4003-8626-5a912285b38f', '2026-10-01 08:11:18.422714', '2026-10-01 08:11:18.422714', 'SETTINGS');
INSERT INTO notraius.entities (id, created_at, updated_at, code) VALUES ('f0d2f2f3-d69e-4f70-83cf-98aa9c886c66', '2026-10-01 08:11:18.151333', '2026-10-01 08:11:18.151333', 'SUBSCRIPTIONS');


--
-- Data for Name: role; Type: TABLE DATA; Schema: communication; Owner: -
--

INSERT INTO communication.role (id, created_at, updated_at, code, label, entity_id) VALUES ('7a148e89-91b6-4978-87bf-978aead14b9b', '2026-10-01 08:11:17.681249', '2026-10-01 08:11:17.681249', 'MEMBER', 'Member', '3284de2b-8a4f-44e5-822c-65c88c9bc09b');
INSERT INTO communication.role (id, created_at, updated_at, code, label, entity_id) VALUES ('bcc1e9ef-3342-48fa-a693-13983cc82cff', '2026-10-01 08:11:17.681249', '2026-10-01 08:11:17.681249', 'MODERATOR', 'Moderator', '3284de2b-8a4f-44e5-822c-65c88c9bc09b');
INSERT INTO communication.role (id, created_at, updated_at, code, label, entity_id) VALUES ('00dc3dd2-51d4-40cd-886d-2af47603959a', '2026-10-01 08:11:17.681249', '2026-10-01 08:11:17.681249', 'ADMIN', 'Admin', '3284de2b-8a4f-44e5-822c-65c88c9bc09b');


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

INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id) VALUES ('038484e8-f1f5-49ba-9bdd-b2d5000f8094', '2026-10-01 08:11:17.398746', '2026-10-01 08:11:17.398746', 'BANNED', 'Member banned', '3284de2b-8a4f-44e5-822c-65c88c9bc09b');
INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id) VALUES ('8c0d218c-1a57-44c5-93d0-862a55991550', '2026-10-01 08:11:17.398746', '2026-10-01 08:11:17.398746', 'CANCELLED', 'Ticket cancelled by owner', 'fcadbb0a-737c-4b94-a6f6-4129abc22928');
INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id) VALUES ('5319852a-8412-413e-9e3f-3da9d873c45a', '2026-10-01 08:11:17.398746', '2026-10-01 08:11:17.398746', 'READ', 'Notification read', '952e750c-71e0-4bda-b3a8-de072e555540');
INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id) VALUES ('a3c57d80-b2e7-4c52-9a32-47fb676efefb', '2026-10-01 08:11:17.398746', '2026-10-01 08:11:17.398746', 'ACCEPTED', 'Request accepted', 'e1b11718-cead-436b-be0f-0a2aa57a76a3');
INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id) VALUES ('4129cc9b-b13b-4cc3-9acc-7c56e0877340', '2026-10-01 08:11:17.398746', '2026-10-01 08:11:17.398746', 'DELETED', 'Ticket soft-deleted', 'fcadbb0a-737c-4b94-a6f6-4129abc22928');
INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id) VALUES ('d33e9649-5479-4e68-b111-956a57451c7c', '2026-10-01 08:11:17.398746', '2026-10-01 08:11:17.398746', 'PENDING', 'Request pending', 'e1b11718-cead-436b-be0f-0a2aa57a76a3');
INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id) VALUES ('f35f7114-aca7-4723-aeef-e96660a20283', '2026-10-01 08:11:17.398746', '2026-10-01 08:11:17.398746', 'IN_PROGRESS', 'Ticket assigned to an agent', 'fcadbb0a-737c-4b94-a6f6-4129abc22928');
INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id) VALUES ('d6b776df-23d6-4b5d-bc71-8a5733e18e25', '2026-10-01 08:11:17.398746', '2026-10-01 08:11:17.398746', 'EDITED', 'Message edited', 'f5262381-9975-4f94-9628-5d383f684d7f');
INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id) VALUES ('87d5cada-8163-47c0-a175-fb826e2939b5', '2026-10-01 08:11:17.398746', '2026-10-01 08:11:17.398746', 'DELETED', 'Notification soft-deleted', '952e750c-71e0-4bda-b3a8-de072e555540');
INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id) VALUES ('10f632a0-bf23-4c12-ad49-8f8956dd490f', '2026-10-01 08:11:17.398746', '2026-10-01 08:11:17.398746', 'DELETED', 'Message soft-deleted', 'f5262381-9975-4f94-9628-5d383f684d7f');
INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id) VALUES ('8338328c-de42-44b2-9cd0-6097f4afed1d', '2026-10-01 08:11:17.398746', '2026-10-01 08:11:17.398746', 'ACTIVE', 'Member active', '3284de2b-8a4f-44e5-822c-65c88c9bc09b');
INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id) VALUES ('005bd286-c3cf-42ba-aec3-62c879bbba08', '2026-10-01 08:11:17.398746', '2026-10-01 08:11:17.398746', 'UNREAD', 'Notification not yet read', '952e750c-71e0-4bda-b3a8-de072e555540');
INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id) VALUES ('54da60ff-ce77-4927-83f0-3ebf6bb2fb67', '2026-10-01 08:11:17.398746', '2026-10-01 08:11:17.398746', 'REJECTED', 'Request rejected', 'e1b11718-cead-436b-be0f-0a2aa57a76a3');
INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id) VALUES ('38e9e7be-2930-427d-bd1a-5b2ce8e8471f', '2026-10-01 08:11:17.398746', '2026-10-01 08:11:17.398746', 'DELETED', 'Chat soft-deleted', 'c6af475a-4529-4c6b-a552-e5d75d06f64a');
INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id) VALUES ('6d7dad19-0402-481f-988c-e6f41fcc8c5d', '2026-10-01 08:11:17.398746', '2026-10-01 08:11:17.398746', 'ACTIVE', 'Chat active', 'c6af475a-4529-4c6b-a552-e5d75d06f64a');
INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id) VALUES ('809544eb-7371-4e79-9ab4-5d5c340bd643', '2026-10-01 08:11:17.398746', '2026-10-01 08:11:17.398746', 'OPEN', 'Ticket created, awaiting support', 'fcadbb0a-737c-4b94-a6f6-4129abc22928');
INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id) VALUES ('a78c03d3-1de0-4d2e-b9a1-e11e5a9fc83e', '2026-10-01 08:11:17.398746', '2026-10-01 08:11:17.398746', 'CLOSED', 'Ticket closed', 'fcadbb0a-737c-4b94-a6f6-4129abc22928');
INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id) VALUES ('d93df782-4ef1-4da0-9b8d-16a29be93764', '2026-10-01 08:11:17.398746', '2026-10-01 08:11:17.398746', 'SENT', 'Message sent', 'f5262381-9975-4f94-9628-5d383f684d7f');
INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id) VALUES ('2ec30650-912d-4c66-a568-64a515ffc7c9', '2026-10-01 08:11:17.398746', '2026-10-01 08:11:17.398746', 'DELETED', 'Feedback soft-deleted', '1fb7c159-c66c-4fd9-aa67-54bc514dd5cf');
INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id) VALUES ('ef21dded-b703-4ffe-a02b-c713ac29a6c5', '2026-10-01 08:11:17.398746', '2026-10-01 08:11:17.398746', 'CANCELLED', 'Request cancelled', 'e1b11718-cead-436b-be0f-0a2aa57a76a3');
INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id) VALUES ('9e92c630-23b7-409a-bcfa-62cda84d4176', '2026-10-01 08:11:17.398746', '2026-10-01 08:11:17.398746', 'LEFT', 'Member left the chat', '3284de2b-8a4f-44e5-822c-65c88c9bc09b');
INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id) VALUES ('b77aef05-9969-4c49-99ec-9a8b8e4bc516', '2026-10-01 08:11:17.692534', '2026-10-01 08:11:17.692534', 'OPEN', 'Feedback submitted, awaiting review', '1fb7c159-c66c-4fd9-aa67-54bc514dd5cf');
INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id) VALUES ('a7a1cf09-e12e-4c08-9950-7fb57e01d596', '2026-10-01 08:11:18.098833', '2026-10-01 08:11:18.098833', 'DELETED', 'App soft-deleted (restorable)', '10dd0c50-6b9b-4e4d-8311-8dbf3b3c73b4');
INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id) VALUES ('8b0039bf-8382-4c60-b4f6-a2f906195653', '2026-10-01 08:11:18.098833', '2026-10-01 08:11:18.098833', 'SUSPENDED', 'App temporarily disabled', '10dd0c50-6b9b-4e4d-8311-8dbf3b3c73b4');
INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id) VALUES ('a9382a40-3c86-4c0e-a5e5-db0e82e1a523', '2026-10-01 08:11:18.098833', '2026-10-01 08:11:18.098833', 'ACTIVE', 'App live and usable', '10dd0c50-6b9b-4e4d-8311-8dbf3b3c73b4');
INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id) VALUES ('e48ab746-224f-4176-b896-b635fe20cb46', '2026-10-01 08:11:18.098833', '2026-10-01 08:11:18.098833', 'DELETED', 'Attachment soft-deleted', 'd282952f-f822-4594-85da-cd6b62c7ca21');
INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id) VALUES ('49f2d819-d3b3-466b-b157-7312763c1d58', '2026-10-01 08:11:18.098833', '2026-10-01 08:11:18.098833', 'ACTIVE', 'Attachment live and referenced', 'd282952f-f822-4594-85da-cd6b62c7ca21');
INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id) VALUES ('1da968b3-592a-4254-9ea6-4c6422c2c340', '2026-10-01 08:11:18.15467', '2026-10-01 08:11:18.15467', 'EXPIRED', 'Subscription period ended', 'f0d2f2f3-d69e-4f70-83cf-98aa9c886c66');
INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id) VALUES ('f404dc84-af3e-449b-88e7-4e2b12ca7717', '2026-10-01 08:11:18.15467', '2026-10-01 08:11:18.15467', 'ACTIVE', 'Subscription live', 'f0d2f2f3-d69e-4f70-83cf-98aa9c886c66');
INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id) VALUES ('a76ac055-1634-4346-bb2e-7c42ef424726', '2026-10-01 08:11:18.161595', '2026-10-01 08:11:18.161595', 'DELETED', 'Webhook soft-deleted (registration retained for audit)', '012645ca-7936-4444-a32e-b4d6679b86b7');
INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id) VALUES ('ca60fe9f-b9b3-4ed6-a163-d81d5a5c9f75', '2026-10-01 08:11:18.161595', '2026-10-01 08:11:18.161595', 'ACTIVE', 'Webhook live and dispatching', '012645ca-7936-4444-a32e-b4d6679b86b7');
INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id) VALUES ('df002d5c-10bd-4141-88f1-25d71c1ffafd', '2026-10-01 08:11:18.426102', '2026-10-01 08:11:18.426102', 'ACTIVE', 'Platform setting active', 'f8e4dd50-f256-4003-8626-5a912285b38f');
INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id) VALUES ('0ad3cb56-2f04-40dd-87fc-2d30b8215d51', '2026-10-01 08:11:18.556766', '2026-10-01 08:11:18.556766', 'DELETED', 'Platform membership removed', 'f8e4dd50-f256-4003-8626-5a912285b38f');
INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id) VALUES ('5899a0c2-249c-4bec-984a-d2bbc7608880', '2026-10-01 08:11:18.692614', '2026-10-01 08:11:18.692614', 'DISABLED', 'Event paused by its owner', '61d29810-c760-4669-922c-c34caa8c433d');
INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id) VALUES ('f6cffa71-2049-48ed-90de-052689dac6b5', '2026-10-01 08:11:18.692614', '2026-10-01 08:11:18.692614', 'PENDING', 'Event confirmed by the user, waiting to activate', '61d29810-c760-4669-922c-c34caa8c433d');
INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id) VALUES ('59da8cee-9c9f-4fca-aafe-249f17e1dd37', '2026-10-01 08:11:18.692614', '2026-10-01 08:11:18.692614', 'REQUESTED', 'Event requested by an actor, awaiting user confirmation', '61d29810-c760-4669-922c-c34caa8c433d');
INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id) VALUES ('d6234536-eb0c-4c78-8455-6353c7475016', '2026-10-01 08:11:18.692614', '2026-10-01 08:11:18.692614', 'EXPIRED', 'Event window closed', '61d29810-c760-4669-922c-c34caa8c433d');
INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id) VALUES ('29bba8da-5c7f-4de1-8c67-b1d92790402b', '2026-10-01 08:11:18.692614', '2026-10-01 08:11:18.692614', 'CANCELLED', 'Event withdrawn', '61d29810-c760-4669-922c-c34caa8c433d');
INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id) VALUES ('acb927af-2811-457d-9d69-70415b32efa2', '2026-10-01 08:11:18.692614', '2026-10-01 08:11:18.692614', 'ACTIVE', 'Event live and executing', '61d29810-c760-4669-922c-c34caa8c433d');
INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id) VALUES ('064d6aa8-7ce0-495b-9fab-cebdd4713733', '2026-10-01 08:11:18.778189', '2026-10-01 08:11:18.778189', 'PENDING', 'Consent requested, awaiting grantor decision', 'ed2bf644-9c29-49f3-9244-4a8a6ce48cdb');
INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id) VALUES ('aa71dd39-202e-4a79-be7e-b82e0ac7b1ab', '2026-10-01 08:11:18.778189', '2026-10-01 08:11:18.778189', 'GRANTED', 'Consent granted and live inside its window', 'ed2bf644-9c29-49f3-9244-4a8a6ce48cdb');
INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id) VALUES ('04236ee0-4ad1-443c-a8a5-c91d33647a2a', '2026-10-01 08:11:18.778189', '2026-10-01 08:11:18.778189', 'REVOKED', 'Consent revoked (by grantor, demotion or window close)', 'ed2bf644-9c29-49f3-9244-4a8a6ce48cdb');
INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id) VALUES ('a3fd008b-fc79-4f3d-a836-057ff6badfcf', '2026-10-01 08:11:18.778189', '2026-10-01 08:11:18.778189', 'DENIED', 'Consent denied by the grantor', 'ed2bf644-9c29-49f3-9244-4a8a6ce48cdb');
INSERT INTO notraius.statuses (id, created_at, updated_at, code, description, entity_id) VALUES ('59ebc99e-3fc4-4576-8609-5f80e8196398', '2026-10-01 08:11:19.199123', '2026-10-01 08:11:19.199123', 'INACTIVE', 'Row replaced by a newer one', 'f0d2f2f3-d69e-4f70-83cf-98aa9c886c66');


--
-- Data for Name: token_purposes; Type: TABLE DATA; Schema: security; Owner: -
--

INSERT INTO security.token_purposes (id, created_at, updated_at, code, description, label, timeframe) VALUES ('c6867f4e-2209-41f6-81f0-78b9020cd9ce', '2026-10-01 08:11:17.4045', '2026-10-01 08:11:17.4045', 'PRIVATE_KEY', 'Enterprise Private Key', 'Hash of an enterprise-owned API private key', NULL);
INSERT INTO security.token_purposes (id, created_at, updated_at, code, description, label, timeframe) VALUES ('b654795a-387e-4b16-b229-c5adc1090220', '2026-10-01 08:11:17.4045', '2026-10-01 08:11:17.4045', 'PUBLIC_KEY', 'Enterprise Public Key', 'Public key of an enterprise-owned API keypair', NULL);
INSERT INTO security.token_purposes (id, created_at, updated_at, code, description, label, timeframe) VALUES ('7c48b295-3a14-4de6-9283-8a0169b09f06', '2026-10-01 08:11:17.4045', '2026-10-01 08:11:17.4045', 'ACCESS_TOKEN', 'Access Token', 'Short-lived JWT access token (15 min)', 15);
INSERT INTO security.token_purposes (id, created_at, updated_at, code, description, label, timeframe) VALUES ('a6b941c9-66d8-4dd3-ad77-a94a81c93dd6', '2026-10-01 08:11:17.4045', '2026-10-01 08:11:17.4045', 'REFRESH_TOKEN', 'Refresh Token', 'Long-lived refresh token for rotation (30 days)', 43200);
INSERT INTO security.token_purposes (id, created_at, updated_at, code, description, label, timeframe) VALUES ('fa35bc13-0b3d-41b5-9f39-38b0bcaac396', '2026-10-01 08:11:17.4045', '2026-10-01 08:11:17.4045', 'MFA_BACKUP', 'MFA Backup Code', 'One-time backup code for MFA recovery', NULL);
INSERT INTO security.token_purposes (id, created_at, updated_at, code, description, label, timeframe) VALUES ('fcbded63-3442-4780-b989-6c25d3e5fe3d', '2026-10-01 08:11:17.4045', '2026-10-01 08:11:17.4045', 'NOTRAIUS_QR', 'Token for pickup/dropoff notraius QR verification', 'Logistic QR', 60);
INSERT INTO security.token_purposes (id, created_at, updated_at, code, description, label, timeframe) VALUES ('0cbb37ba-d0b5-4c8a-96f0-1337578714a7', '2026-10-01 08:11:18.34262', '2026-10-01 08:11:18.34262', 'SHIPMENT', 'Token for a shipment leg delivery verification (pickup/dropoff QR)', 'Shipment', 60);


--
-- PostgreSQL database dump complete
--

--unrestrict LyQc7wNIPP3Ialhd7Qc9HC7hB7oQraz5eSs73fdd9X5rUitoIMMuQJfTUFgEyzk

