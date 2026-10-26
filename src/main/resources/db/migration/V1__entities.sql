-- ═══════════════════════════════════════════════════════════════════════════
-- V1__entities.sql — NOTRAIUS microservice: complete schema (SQUASHED)
-- ═══════════════════════════════════════════════════════════════════════════
-- Single source of truth for a fresh notraius database — the deterministic
-- merge of the retired chain V1..V24 (squash of 2026-10):
--   • courses/orders/shipments/agreements in final shape (agreements V24,
--     bare status_id lifecycles V16, GPS columns V5, course type
--     hierarchy V21),
--   • subscription module final (currency_id, numeric(18,6), features.code,
--     subscriptions.metadata — V7/V17/V19/V20),
--   • event module (V12/V17), consent validity (V13), entity linking (V15),
--     app.keys without type (V18), tokens without used_at/app_id (V7/V9),
--   • registry entities/statuses carry POST-V23 table-mirroring codes;
--     party/chat roles MEMBER/MODERATOR/ADMIN (V22).
-- Reference DATA lives in V2__seeders.sql. Runtime tables stay EMPTY.
-- ═══════════════════════════════════════════════════════════════════════════

--
-- PostgreSQL database dump
--

--restrict msScbTH3QkwB8zEWNxU8eLNxAfpJN92sJje0ND3jbppMNuZLV9XEWpeOZJOCvd3

-- Dumped from database version 18.6 (Ubuntu 18.6-0ubuntu0.26.04.1)
-- Dumped by pg_dump version 18.6 (Ubuntu 18.6-0ubuntu0.26.04.1)

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET transaction_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

--
-- Name: access; Type: SCHEMA; Schema: -; Owner: -
--

CREATE SCHEMA access;


--
-- Name: app; Type: SCHEMA; Schema: -; Owner: -
--

CREATE SCHEMA app;


--
-- Name: communication; Type: SCHEMA; Schema: -; Owner: -
--

CREATE SCHEMA communication;


--
-- Name: event; Type: SCHEMA; Schema: -; Owner: -
--

CREATE SCHEMA event;


--
-- Name: notraius; Type: SCHEMA; Schema: -; Owner: -
--

CREATE SCHEMA notraius;


--
-- Name: security; Type: SCHEMA; Schema: -; Owner: -
--

CREATE SCHEMA security;


--
-- Name: subscription; Type: SCHEMA; Schema: -; Owner: -
--

CREATE SCHEMA subscription;


--
-- Name: tsvector_update_chat_requests(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.tsvector_update_chat_requests() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    NEW.vector :=
        setweight(to_tsvector('english', COALESCE(NEW.message, '')), 'A');
    RETURN NEW;
END;
$$;


--
-- Name: tsvector_update_chats(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.tsvector_update_chats() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    NEW.vector :=
        setweight(to_tsvector('english', COALESCE(NEW.name, '')), 'A');
    RETURN NEW;
END;
$$;


--
-- Name: tsvector_update_messages(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.tsvector_update_messages() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    NEW.vector :=
        setweight(to_tsvector('english', COALESCE(NEW.content, '')), 'A');
    RETURN NEW;
END;
$$;


SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: activities; Type: TABLE; Schema: access; Owner: -
--

CREATE TABLE access.activities (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone NOT NULL,
    code character varying(100) NOT NULL,
    operations text,
    description character varying(255),
    consentable boolean DEFAULT false NOT NULL
);


--
-- Name: consents; Type: TABLE; Schema: access; Owner: -
--

CREATE TABLE access.consents (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    user_id uuid NOT NULL,
    permission_id uuid NOT NULL,
    entity character varying(10) NOT NULL,
    reference_id uuid,
    created_by uuid,
    validity_id uuid NOT NULL
);


--
-- Name: permissions; Type: TABLE; Schema: access; Owner: -
--

CREATE TABLE access.permissions (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    activity_code character varying(100) NOT NULL,
    operation character varying(100) NOT NULL,
    authority_code character varying(50) NOT NULL,
    authority_access character varying(3) DEFAULT 'GTE'::character varying NOT NULL,
    description character varying(255),
    default_ttl_minutes integer
);


--
-- Name: apps; Type: TABLE; Schema: app; Owner: -
--

CREATE TABLE app.apps (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid NOT NULL,
    code character varying(60) NOT NULL,
    name character varying(120) NOT NULL,
    description text,
    url character varying(255),
    logo_path character varying(255),
    status_id uuid,
    deleted boolean DEFAULT false NOT NULL
);


--
-- Name: callbacks; Type: TABLE; Schema: app; Owner: -
--

CREATE TABLE app.callbacks (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    app_id uuid NOT NULL,
    idx integer NOT NULL,
    url character varying(500) NOT NULL,
    metadata jsonb
);


--
-- Name: keys; Type: TABLE; Schema: app; Owner: -
--

CREATE TABLE app.keys (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    app_id uuid NOT NULL,
    token_id uuid NOT NULL
);


--
-- Name: chat_members; Type: TABLE; Schema: communication; Owner: -
--

CREATE TABLE communication.chat_members (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone NOT NULL,
    chat_id uuid NOT NULL,
    user_id uuid NOT NULL,
    role_id uuid NOT NULL,
    status_id uuid NOT NULL,
    version bigint
);


--
-- Name: chat_requests; Type: TABLE; Schema: communication; Owner: -
--

CREATE TABLE communication.chat_requests (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone NOT NULL,
    chat_id uuid NOT NULL,
    user_id uuid NOT NULL,
    created_by uuid,
    status_id uuid NOT NULL,
    request_type character varying(20) DEFAULT 'JOIN'::character varying NOT NULL,
    message text,
    vector tsvector
);


--
-- Name: chats; Type: TABLE; Schema: communication; Owner: -
--

CREATE TABLE communication.chats (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone NOT NULL,
    name character varying(255),
    created_by uuid NOT NULL,
    status_id uuid NOT NULL,
    type character varying(20) NOT NULL,
    visibility character varying(20) NOT NULL,
    last_message_id uuid,
    version bigint DEFAULT 0 NOT NULL,
    vector tsvector,
    CONSTRAINT ck_chats_type CHECK (((type)::text = ANY ((ARRAY['DIRECT'::character varying, 'GROUP'::character varying])::text[]))),
    CONSTRAINT ck_chats_visibility CHECK (((visibility)::text = ANY ((ARRAY['PUBLIC'::character varying, 'PRIVATE'::character varying])::text[])))
);


--
-- Name: feedback; Type: TABLE; Schema: communication; Owner: -
--

CREATE TABLE communication.feedback (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone NOT NULL,
    user_id uuid NOT NULL,
    subject_id uuid,
    title character varying(255) NOT NULL,
    message text NOT NULL,
    status_id uuid NOT NULL
);


--
-- Name: feedback_subjects; Type: TABLE; Schema: communication; Owner: -
--

CREATE TABLE communication.feedback_subjects (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone NOT NULL,
    label character varying(100) NOT NULL
);


--
-- Name: message_reads; Type: TABLE; Schema: communication; Owner: -
--

CREATE TABLE communication.message_reads (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone NOT NULL,
    message_id uuid NOT NULL,
    user_id uuid NOT NULL,
    read_at timestamp without time zone NOT NULL
);


--
-- Name: messages; Type: TABLE; Schema: communication; Owner: -
--

CREATE TABLE communication.messages (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone NOT NULL,
    chat_id uuid NOT NULL,
    member_id uuid NOT NULL,
    content text,
    metadata jsonb,
    status_id uuid NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    vector tsvector,
    entity_id uuid,
    reference_id uuid
);


--
-- Name: notifications; Type: TABLE; Schema: communication; Owner: -
--

CREATE TABLE communication.notifications (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone NOT NULL,
    user_id uuid NOT NULL,
    entity_id uuid,
    reference_id uuid,
    log_id uuid,
    title character varying(255) NOT NULL,
    message text NOT NULL,
    status_id uuid NOT NULL,
    version bigint
);


--
-- Name: priority; Type: TABLE; Schema: communication; Owner: -
--

CREATE TABLE communication.priority (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone NOT NULL,
    code character varying(50) NOT NULL,
    label character varying(100) NOT NULL
);


--
-- Name: role; Type: TABLE; Schema: communication; Owner: -
--

CREATE TABLE communication.role (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone NOT NULL,
    code character varying(50) NOT NULL,
    label character varying(100) NOT NULL,
    entity_id uuid
);


--
-- Name: support_ticket; Type: TABLE; Schema: communication; Owner: -
--

CREATE TABLE communication.support_ticket (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone NOT NULL,
    user_id uuid NOT NULL,
    priority_id uuid,
    subject character varying(255) NOT NULL,
    message text NOT NULL,
    status_id uuid NOT NULL,
    agent jsonb,
    entity_id uuid,
    reference_id uuid
);


--
-- Name: events; Type: TABLE; Schema: event; Owner: -
--

CREATE TABLE event.events (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    user_id uuid NOT NULL,
    type_id uuid NOT NULL,
    frequency_id uuid,
    actor character varying(20),
    actor_id uuid,
    "values" jsonb,
    validity_id uuid NOT NULL,
    CONSTRAINT uc_events_actor_pair CHECK ((((actor IS NULL) AND (actor_id IS NULL)) OR ((actor IS NOT NULL) AND (actor_id IS NOT NULL)))),
    CONSTRAINT uc_events_actor_type CHECK (((actor IS NULL) OR ((actor)::text = ANY ((ARRAY['USER'::character varying, 'APP'::character varying, 'SERVICE'::character varying])::text[]))))
);


--
-- Name: frequencies; Type: TABLE; Schema: event; Owner: -
--

CREATE TABLE event.frequencies (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    code character varying(50) NOT NULL,
    description character varying(255),
    value integer NOT NULL,
    unit character varying(10) NOT NULL,
    created_by uuid NOT NULL,
    visibility character varying(20) NOT NULL,
    status_id uuid NOT NULL,
    CONSTRAINT uc_event_frequencies_unit CHECK (((unit)::text = ANY ((ARRAY['DAY'::character varying, 'WEEK'::character varying, 'MONTH'::character varying, 'YEAR'::character varying])::text[]))),
    CONSTRAINT uc_event_frequencies_visibility CHECK (((visibility)::text = ANY ((ARRAY['PUBLIC'::character varying, 'PRIVATE'::character varying])::text[])))
);


--
-- Name: types; Type: TABLE; Schema: event; Owner: -
--

CREATE TABLE event.types (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    code character varying(50) NOT NULL,
    description character varying(255),
    activity_id uuid NOT NULL,
    operations text NOT NULL,
    schema jsonb,
    status_id uuid NOT NULL
);


--
-- Name: agreements; Type: TABLE; Schema: notraius; Owner: -
--


--
-- Name: attachments; Type: TABLE; Schema: notraius; Owner: -
--

CREATE TABLE notraius.attachments (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    entity_id uuid,
    reference_id uuid,
    path character varying(2048) NOT NULL,
    media_id uuid,
    metadata jsonb,
    created_by uuid,
    status_id uuid NOT NULL
);


--
-- Name: course_types; Type: TABLE; Schema: notraius; Owner: -
--


--
-- Name: courses; Type: TABLE; Schema: notraius; Owner: -
--


--
-- Name: entities; Type: TABLE; Schema: notraius; Owner: -
--

CREATE TABLE notraius.entities (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone NOT NULL,
    code character varying(50) NOT NULL
);


--
-- Name: incident_types; Type: TABLE; Schema: notraius; Owner: -
--


--
-- Name: incidents; Type: TABLE; Schema: notraius; Owner: -
--


--
-- Name: locations; Type: TABLE; Schema: notraius; Owner: -
--


--
-- Name: operations; Type: TABLE; Schema: notraius; Owner: -
--


--
-- Name: orders; Type: TABLE; Schema: notraius; Owner: -
--


--
-- Name: qualifications; Type: TABLE; Schema: notraius; Owner: -
--


--
-- Name: quotes; Type: TABLE; Schema: notraius; Owner: -
--


--
-- Name: shipments; Type: TABLE; Schema: notraius; Owner: -
--


--
-- Name: statuses; Type: TABLE; Schema: notraius; Owner: -
--

CREATE TABLE notraius.statuses (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone NOT NULL,
    code character varying(50) NOT NULL,
    description character varying(255),
    entity_id uuid NOT NULL
);


--
-- Name: validity; Type: TABLE; Schema: notraius; Owner: -
--

CREATE TABLE notraius.validity (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    status_id uuid NOT NULL,
    from_date_time timestamp with time zone NOT NULL,
    to_date_time timestamp with time zone
);


--
-- Name: preferences; Type: TABLE; Schema: security; Owner: -
--

CREATE TABLE security.preferences (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone NOT NULL,
    user_id uuid NOT NULL,
    notice jsonb,
    messages jsonb,
    security jsonb,
    theme character varying(50),
    language character varying(10),
    version bigint DEFAULT 0 NOT NULL
);


--
-- Name: rate_limit; Type: TABLE; Schema: security; Owner: -
--

CREATE TABLE security.rate_limit (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone NOT NULL,
    user_id uuid NOT NULL,
    activity_code character varying(100) NOT NULL,
    refresh_at timestamp without time zone NOT NULL,
    count integer NOT NULL,
    operation character varying(50) DEFAULT 'GENERAL'::character varying NOT NULL
);


--
-- Name: settings; Type: TABLE; Schema: security; Owner: -
--

CREATE TABLE security.settings (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone NOT NULL,
    user_id uuid NOT NULL,
    platform_code character varying(50) NOT NULL,
    preference_id uuid,
    status_id uuid NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    platform_role character varying(20) DEFAULT 'AGENT'::character varying NOT NULL
);


--
-- Name: token_purposes; Type: TABLE; Schema: security; Owner: -
--

CREATE TABLE security.token_purposes (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone NOT NULL,
    code character varying(50) NOT NULL,
    description character varying(255),
    label character varying(100),
    timeframe bigint
);


--
-- Name: tokens; Type: TABLE; Schema: security; Owner: -
--

CREATE TABLE security.tokens (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    created_at timestamp without time zone NOT NULL,
    updated_at timestamp without time zone NOT NULL,
    user_id uuid,
    purpose_id uuid NOT NULL,
    token text NOT NULL,
    expires_at timestamp without time zone
);


--
-- Name: COLUMN tokens.expires_at; Type: COMMENT; Schema: security; Owner: -
--

COMMENT ON COLUMN security.tokens.expires_at IS 'Sole liveness marker: NULL = non-expiring by design (API keys, backup codes); past value = dead (consumed one-time tokens are stamped dead at the consumption instant).';


--
-- Name: webhooks; Type: TABLE; Schema: security; Owner: -
--

CREATE TABLE security.webhooks (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid NOT NULL,
    activity_id uuid,
    operation character varying(255) NOT NULL,
    url text NOT NULL,
    params jsonb,
    status_id uuid NOT NULL,
    secret character varying(255),
    actor character varying(20) NOT NULL
);


--
-- Name: features; Type: TABLE; Schema: subscription; Owner: -
--

CREATE TABLE subscription.features (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    title character varying(100) NOT NULL,
    description text,
    code character varying(100) NOT NULL
);


--
-- Name: plan_features; Type: TABLE; Schema: subscription; Owner: -
--

CREATE TABLE subscription.plan_features (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    feature_id uuid NOT NULL,
    plan_id uuid NOT NULL,
    "limit" bigint,
    activity_id uuid,
    status_id uuid NOT NULL
);


--
-- Name: plans; Type: TABLE; Schema: subscription; Owner: -
--

CREATE TABLE subscription.plans (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    code character varying(50) NOT NULL,
    description text,
    validity_id uuid NOT NULL
);


--
-- Name: pricing; Type: TABLE; Schema: subscription; Owner: -
--

CREATE TABLE subscription.pricing (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    plan_id uuid NOT NULL,
    amount numeric(18,6) NOT NULL,
    currency_id uuid NOT NULL,
    status_id uuid NOT NULL
);


--
-- Name: subscriptions; Type: TABLE; Schema: subscription; Owner: -
--

CREATE TABLE subscription.subscriptions (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    plan_id uuid NOT NULL,
    user_id uuid NOT NULL,
    validity uuid NOT NULL,
    payment_id uuid,
    amount numeric(18,6) DEFAULT 0 NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    currency_id uuid NOT NULL,
    metadata jsonb
);


--
-- Name: consents consents_pkey; Type: CONSTRAINT; Schema: access; Owner: -
--

ALTER TABLE ONLY access.consents
    ADD CONSTRAINT consents_pkey PRIMARY KEY (id);


--
-- Name: permissions permissions_pkey; Type: CONSTRAINT; Schema: access; Owner: -
--

ALTER TABLE ONLY access.permissions
    ADD CONSTRAINT permissions_pkey PRIMARY KEY (id);


--
-- Name: activities pk_activities; Type: CONSTRAINT; Schema: access; Owner: -
--

ALTER TABLE ONLY access.activities
    ADD CONSTRAINT pk_activities PRIMARY KEY (id);


--
-- Name: activities uc_activities_code; Type: CONSTRAINT; Schema: access; Owner: -
--

ALTER TABLE ONLY access.activities
    ADD CONSTRAINT uc_activities_code UNIQUE (code);


--
-- Name: consents uk_consents_grant; Type: CONSTRAINT; Schema: access; Owner: -
--

ALTER TABLE ONLY access.consents
    ADD CONSTRAINT uk_consents_grant UNIQUE (user_id, permission_id, entity, reference_id);


--
-- Name: permissions uk_permission_policy; Type: CONSTRAINT; Schema: access; Owner: -
--

ALTER TABLE ONLY access.permissions
    ADD CONSTRAINT uk_permission_policy UNIQUE (activity_code, operation, authority_code, authority_access);


--
-- Name: apps apps_pkey; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.apps
    ADD CONSTRAINT apps_pkey PRIMARY KEY (id);


--
-- Name: callbacks callbacks_pkey; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.callbacks
    ADD CONSTRAINT callbacks_pkey PRIMARY KEY (id);


--
-- Name: keys keys_pkey; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.keys
    ADD CONSTRAINT keys_pkey PRIMARY KEY (id);


--
-- Name: callbacks uc_app_callbacks; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.callbacks
    ADD CONSTRAINT uc_app_callbacks UNIQUE (app_id, idx);


--
-- Name: keys uc_app_keys_token; Type: CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.keys
    ADD CONSTRAINT uc_app_keys_token UNIQUE (token_id);


--
-- Name: chat_members pk_chat_members; Type: CONSTRAINT; Schema: communication; Owner: -
--

ALTER TABLE ONLY communication.chat_members
    ADD CONSTRAINT pk_chat_members PRIMARY KEY (id);


--
-- Name: chat_requests pk_chat_requests; Type: CONSTRAINT; Schema: communication; Owner: -
--

ALTER TABLE ONLY communication.chat_requests
    ADD CONSTRAINT pk_chat_requests PRIMARY KEY (id);


--
-- Name: chats pk_chats; Type: CONSTRAINT; Schema: communication; Owner: -
--

ALTER TABLE ONLY communication.chats
    ADD CONSTRAINT pk_chats PRIMARY KEY (id);


--
-- Name: role pk_communication_role; Type: CONSTRAINT; Schema: communication; Owner: -
--

ALTER TABLE ONLY communication.role
    ADD CONSTRAINT pk_communication_role PRIMARY KEY (id);


--
-- Name: feedback pk_feedback; Type: CONSTRAINT; Schema: communication; Owner: -
--

ALTER TABLE ONLY communication.feedback
    ADD CONSTRAINT pk_feedback PRIMARY KEY (id);


--
-- Name: feedback_subjects pk_feedback_subjects; Type: CONSTRAINT; Schema: communication; Owner: -
--

ALTER TABLE ONLY communication.feedback_subjects
    ADD CONSTRAINT pk_feedback_subjects PRIMARY KEY (id);


--
-- Name: message_reads pk_message_reads; Type: CONSTRAINT; Schema: communication; Owner: -
--

ALTER TABLE ONLY communication.message_reads
    ADD CONSTRAINT pk_message_reads PRIMARY KEY (id);


--
-- Name: messages pk_messages; Type: CONSTRAINT; Schema: communication; Owner: -
--

ALTER TABLE ONLY communication.messages
    ADD CONSTRAINT pk_messages PRIMARY KEY (id);


--
-- Name: notifications pk_notifications; Type: CONSTRAINT; Schema: communication; Owner: -
--

ALTER TABLE ONLY communication.notifications
    ADD CONSTRAINT pk_notifications PRIMARY KEY (id);


--
-- Name: priority pk_priority; Type: CONSTRAINT; Schema: communication; Owner: -
--

ALTER TABLE ONLY communication.priority
    ADD CONSTRAINT pk_priority PRIMARY KEY (id);


--
-- Name: support_ticket pk_support_ticket; Type: CONSTRAINT; Schema: communication; Owner: -
--

ALTER TABLE ONLY communication.support_ticket
    ADD CONSTRAINT pk_support_ticket PRIMARY KEY (id);


--
-- Name: role uc_communication_role_code; Type: CONSTRAINT; Schema: communication; Owner: -
--

ALTER TABLE ONLY communication.role
    ADD CONSTRAINT uc_communication_role_code UNIQUE (code, entity_id);


--
-- Name: priority uc_priority_code; Type: CONSTRAINT; Schema: communication; Owner: -
--

ALTER TABLE ONLY communication.priority
    ADD CONSTRAINT uc_priority_code UNIQUE (code);


--
-- Name: chat_members uk_chat_members_chat_user; Type: CONSTRAINT; Schema: communication; Owner: -
--

ALTER TABLE ONLY communication.chat_members
    ADD CONSTRAINT uk_chat_members_chat_user UNIQUE (chat_id, user_id);


--
-- Name: message_reads uk_message_reads_message_user; Type: CONSTRAINT; Schema: communication; Owner: -
--

ALTER TABLE ONLY communication.message_reads
    ADD CONSTRAINT uk_message_reads_message_user UNIQUE (message_id, user_id);


--
-- Name: events events_pkey; Type: CONSTRAINT; Schema: event; Owner: -
--

ALTER TABLE ONLY event.events
    ADD CONSTRAINT events_pkey PRIMARY KEY (id);


--
-- Name: frequencies frequencies_pkey; Type: CONSTRAINT; Schema: event; Owner: -
--

ALTER TABLE ONLY event.frequencies
    ADD CONSTRAINT frequencies_pkey PRIMARY KEY (id);


--
-- Name: types types_pkey; Type: CONSTRAINT; Schema: event; Owner: -
--

ALTER TABLE ONLY event.types
    ADD CONSTRAINT types_pkey PRIMARY KEY (id);


--
-- Name: agreements pk_agreements; Type: CONSTRAINT; Schema: notraius; Owner: -
--


--
-- Name: attachments pk_attachments; Type: CONSTRAINT; Schema: notraius; Owner: -
--

ALTER TABLE ONLY notraius.attachments
    ADD CONSTRAINT pk_attachments PRIMARY KEY (id);


--
-- Name: course_types pk_course_types; Type: CONSTRAINT; Schema: notraius; Owner: -
--


--
-- Name: courses pk_courses; Type: CONSTRAINT; Schema: notraius; Owner: -
--


--
-- Name: entities pk_entities; Type: CONSTRAINT; Schema: notraius; Owner: -
--

ALTER TABLE ONLY notraius.entities
    ADD CONSTRAINT pk_entities PRIMARY KEY (id);


--
-- Name: incident_types pk_incident_types; Type: CONSTRAINT; Schema: notraius; Owner: -
--


--
-- Name: incidents pk_incidents; Type: CONSTRAINT; Schema: notraius; Owner: -
--


--
-- Name: locations pk_locations; Type: CONSTRAINT; Schema: notraius; Owner: -
--


--
-- Name: operations pk_operations; Type: CONSTRAINT; Schema: notraius; Owner: -
--


--
-- Name: orders pk_orders; Type: CONSTRAINT; Schema: notraius; Owner: -
--


--
-- Name: qualifications pk_qualifications; Type: CONSTRAINT; Schema: notraius; Owner: -
--


--
-- Name: quotes pk_quotes; Type: CONSTRAINT; Schema: notraius; Owner: -
--


--
-- Name: shipments pk_shipments; Type: CONSTRAINT; Schema: notraius; Owner: -
--


--
-- Name: statuses pk_statuses; Type: CONSTRAINT; Schema: notraius; Owner: -
--

ALTER TABLE ONLY notraius.statuses
    ADD CONSTRAINT pk_statuses PRIMARY KEY (id);


--
-- Name: course_types uc_course_types_code; Type: CONSTRAINT; Schema: notraius; Owner: -
--


--
-- Name: entities uc_entities_code; Type: CONSTRAINT; Schema: notraius; Owner: -
--

ALTER TABLE ONLY notraius.entities
    ADD CONSTRAINT uc_entities_code UNIQUE (code);


--
-- Name: incident_types uc_incident_types_code; Type: CONSTRAINT; Schema: notraius; Owner: -
--


--
-- Name: operations uc_operations_code; Type: CONSTRAINT; Schema: notraius; Owner: -
--


--
-- Name: shipments uc_shipments_token; Type: CONSTRAINT; Schema: notraius; Owner: -
--


--
-- Name: locations uq_locations_user_label; Type: CONSTRAINT; Schema: notraius; Owner: -
--


--
-- Name: qualifications uq_qualifications_title_created_by; Type: CONSTRAINT; Schema: notraius; Owner: -
--


--
-- Name: validity validity_pkey; Type: CONSTRAINT; Schema: notraius; Owner: -
--

ALTER TABLE ONLY notraius.validity
    ADD CONSTRAINT validity_pkey PRIMARY KEY (id);


--
-- Name: preferences pk_preferences; Type: CONSTRAINT; Schema: security; Owner: -
--

ALTER TABLE ONLY security.preferences
    ADD CONSTRAINT pk_preferences PRIMARY KEY (id);


--
-- Name: rate_limit pk_rate_limit; Type: CONSTRAINT; Schema: security; Owner: -
--

ALTER TABLE ONLY security.rate_limit
    ADD CONSTRAINT pk_rate_limit PRIMARY KEY (id);


--
-- Name: settings pk_settings; Type: CONSTRAINT; Schema: security; Owner: -
--

ALTER TABLE ONLY security.settings
    ADD CONSTRAINT pk_settings PRIMARY KEY (id);


--
-- Name: token_purposes pk_token_purposes; Type: CONSTRAINT; Schema: security; Owner: -
--

ALTER TABLE ONLY security.token_purposes
    ADD CONSTRAINT pk_token_purposes PRIMARY KEY (id);


--
-- Name: tokens pk_tokens; Type: CONSTRAINT; Schema: security; Owner: -
--

ALTER TABLE ONLY security.tokens
    ADD CONSTRAINT pk_tokens PRIMARY KEY (id);


--
-- Name: webhooks pk_webhooks; Type: CONSTRAINT; Schema: security; Owner: -
--

ALTER TABLE ONLY security.webhooks
    ADD CONSTRAINT pk_webhooks PRIMARY KEY (id);


--
-- Name: preferences uc_preferences_user; Type: CONSTRAINT; Schema: security; Owner: -
--

ALTER TABLE ONLY security.preferences
    ADD CONSTRAINT uc_preferences_user UNIQUE (user_id);


--
-- Name: rate_limit uc_rate_limit_user_op_activity; Type: CONSTRAINT; Schema: security; Owner: -
--

ALTER TABLE ONLY security.rate_limit
    ADD CONSTRAINT uc_rate_limit_user_op_activity UNIQUE (user_id, operation, activity_code);


--
-- Name: token_purposes uc_token_purposes_code; Type: CONSTRAINT; Schema: security; Owner: -
--

ALTER TABLE ONLY security.token_purposes
    ADD CONSTRAINT uc_token_purposes_code UNIQUE (code);


--
-- Name: settings uk_settings_user_platform; Type: CONSTRAINT; Schema: security; Owner: -
--

ALTER TABLE ONLY security.settings
    ADD CONSTRAINT uk_settings_user_platform UNIQUE (user_id, platform_code);


--
-- Name: features features_pkey; Type: CONSTRAINT; Schema: subscription; Owner: -
--

ALTER TABLE ONLY subscription.features
    ADD CONSTRAINT features_pkey PRIMARY KEY (id);


--
-- Name: plan_features plan_features_pkey; Type: CONSTRAINT; Schema: subscription; Owner: -
--

ALTER TABLE ONLY subscription.plan_features
    ADD CONSTRAINT plan_features_pkey PRIMARY KEY (id);


--
-- Name: plans plans_pkey; Type: CONSTRAINT; Schema: subscription; Owner: -
--

ALTER TABLE ONLY subscription.plans
    ADD CONSTRAINT plans_pkey PRIMARY KEY (id);


--
-- Name: pricing pricing_pkey; Type: CONSTRAINT; Schema: subscription; Owner: -
--

ALTER TABLE ONLY subscription.pricing
    ADD CONSTRAINT pricing_pkey PRIMARY KEY (id);


--
-- Name: subscriptions subscriptions_pkey; Type: CONSTRAINT; Schema: subscription; Owner: -
--

ALTER TABLE ONLY subscription.subscriptions
    ADD CONSTRAINT subscriptions_pkey PRIMARY KEY (id);


--
-- Name: plan_features uc_plan_features_plan_feature; Type: CONSTRAINT; Schema: subscription; Owner: -
--

ALTER TABLE ONLY subscription.plan_features
    ADD CONSTRAINT uc_plan_features_plan_feature UNIQUE (plan_id, feature_id);


--
-- Name: features uc_subscription_features_code; Type: CONSTRAINT; Schema: subscription; Owner: -
--

ALTER TABLE ONLY subscription.features
    ADD CONSTRAINT uc_subscription_features_code UNIQUE (code);


--
-- Name: idx_consents_entity_ref; Type: INDEX; Schema: access; Owner: -
--

CREATE INDEX idx_consents_entity_ref ON access.consents USING btree (entity, reference_id);


--
-- Name: idx_consents_permission; Type: INDEX; Schema: access; Owner: -
--

CREATE INDEX idx_consents_permission ON access.consents USING btree (permission_id);


--
-- Name: idx_consents_user_id; Type: INDEX; Schema: access; Owner: -
--

CREATE INDEX idx_consents_user_id ON access.consents USING btree (user_id);


--
-- Name: idx_consents_user_permission; Type: INDEX; Schema: access; Owner: -
--

CREATE INDEX idx_consents_user_permission ON access.consents USING btree (user_id, permission_id);


--
-- Name: idx_consents_validity_id; Type: INDEX; Schema: access; Owner: -
--

CREATE INDEX idx_consents_validity_id ON access.consents USING btree (validity_id);


--
-- Name: idx_perm_activity_op; Type: INDEX; Schema: access; Owner: -
--

CREATE INDEX idx_perm_activity_op ON access.permissions USING btree (activity_code, operation);


--
-- Name: idx_perm_authority; Type: INDEX; Schema: access; Owner: -
--

CREATE INDEX idx_perm_authority ON access.permissions USING btree (authority_code);


--
-- Name: idx_app_callbacks_app_id; Type: INDEX; Schema: app; Owner: -
--

CREATE INDEX idx_app_callbacks_app_id ON app.callbacks USING btree (app_id);


--
-- Name: idx_app_keys_app_id; Type: INDEX; Schema: app; Owner: -
--

CREATE INDEX idx_app_keys_app_id ON app.keys USING btree (app_id);


--
-- Name: idx_app_keys_token_id; Type: INDEX; Schema: app; Owner: -
--

CREATE INDEX idx_app_keys_token_id ON app.keys USING btree (token_id);


--
-- Name: idx_apps_created_by; Type: INDEX; Schema: app; Owner: -
--

CREATE INDEX idx_apps_created_by ON app.apps USING btree (created_by);


--
-- Name: idx_apps_deleted; Type: INDEX; Schema: app; Owner: -
--

CREATE INDEX idx_apps_deleted ON app.apps USING btree (deleted);


--
-- Name: idx_chat_members_chat_id; Type: INDEX; Schema: communication; Owner: -
--

CREATE INDEX idx_chat_members_chat_id ON communication.chat_members USING btree (chat_id);


--
-- Name: idx_chat_members_role_id; Type: INDEX; Schema: communication; Owner: -
--

CREATE INDEX idx_chat_members_role_id ON communication.chat_members USING btree (role_id);


--
-- Name: idx_chat_members_status_id; Type: INDEX; Schema: communication; Owner: -
--

CREATE INDEX idx_chat_members_status_id ON communication.chat_members USING btree (status_id);


--
-- Name: idx_chat_members_user_id; Type: INDEX; Schema: communication; Owner: -
--

CREATE INDEX idx_chat_members_user_id ON communication.chat_members USING btree (user_id);


--
-- Name: idx_chat_requests_chat_id; Type: INDEX; Schema: communication; Owner: -
--

CREATE INDEX idx_chat_requests_chat_id ON communication.chat_requests USING btree (chat_id);


--
-- Name: idx_chat_requests_search; Type: INDEX; Schema: communication; Owner: -
--

CREATE INDEX idx_chat_requests_search ON communication.chat_requests USING gin (vector);


--
-- Name: idx_chat_requests_status_id; Type: INDEX; Schema: communication; Owner: -
--

CREATE INDEX idx_chat_requests_status_id ON communication.chat_requests USING btree (status_id);


--
-- Name: idx_chat_requests_user_id; Type: INDEX; Schema: communication; Owner: -
--

CREATE INDEX idx_chat_requests_user_id ON communication.chat_requests USING btree (user_id);


--
-- Name: idx_chats_created_by; Type: INDEX; Schema: communication; Owner: -
--

CREATE INDEX idx_chats_created_by ON communication.chats USING btree (created_by);


--
-- Name: idx_chats_search; Type: INDEX; Schema: communication; Owner: -
--

CREATE INDEX idx_chats_search ON communication.chats USING gin (vector);


--
-- Name: idx_chats_status_id; Type: INDEX; Schema: communication; Owner: -
--

CREATE INDEX idx_chats_status_id ON communication.chats USING btree (status_id);


--
-- Name: idx_chats_type; Type: INDEX; Schema: communication; Owner: -
--

CREATE INDEX idx_chats_type ON communication.chats USING btree (type);


--
-- Name: idx_chats_visibility; Type: INDEX; Schema: communication; Owner: -
--

CREATE INDEX idx_chats_visibility ON communication.chats USING btree (visibility);


--
-- Name: idx_communication_role_entity_id; Type: INDEX; Schema: communication; Owner: -
--

CREATE INDEX idx_communication_role_entity_id ON communication.role USING btree (entity_id);


--
-- Name: idx_feedback_status_id; Type: INDEX; Schema: communication; Owner: -
--

CREATE INDEX idx_feedback_status_id ON communication.feedback USING btree (status_id);


--
-- Name: idx_feedback_subject_id; Type: INDEX; Schema: communication; Owner: -
--

CREATE INDEX idx_feedback_subject_id ON communication.feedback USING btree (subject_id);


--
-- Name: idx_feedback_user_id; Type: INDEX; Schema: communication; Owner: -
--

CREATE INDEX idx_feedback_user_id ON communication.feedback USING btree (user_id);


--
-- Name: idx_notraius_notifications_entity_id; Type: INDEX; Schema: communication; Owner: -
--

CREATE INDEX idx_notraius_notifications_entity_id ON communication.notifications USING btree (entity_id);


--
-- Name: idx_notraius_notifications_log_id; Type: INDEX; Schema: communication; Owner: -
--

CREATE INDEX idx_notraius_notifications_log_id ON communication.notifications USING btree (log_id);


--
-- Name: idx_notraius_notifications_status_id; Type: INDEX; Schema: communication; Owner: -
--

CREATE INDEX idx_notraius_notifications_status_id ON communication.notifications USING btree (status_id);


--
-- Name: idx_notraius_notifications_user_id; Type: INDEX; Schema: communication; Owner: -
--

CREATE INDEX idx_notraius_notifications_user_id ON communication.notifications USING btree (user_id);


--
-- Name: idx_message_reads_message_id; Type: INDEX; Schema: communication; Owner: -
--

CREATE INDEX idx_message_reads_message_id ON communication.message_reads USING btree (message_id);


--
-- Name: idx_message_reads_user_id; Type: INDEX; Schema: communication; Owner: -
--

CREATE INDEX idx_message_reads_user_id ON communication.message_reads USING btree (user_id);


--
-- Name: idx_messages_chat_id; Type: INDEX; Schema: communication; Owner: -
--

CREATE INDEX idx_messages_chat_id ON communication.messages USING btree (chat_id);


--
-- Name: idx_messages_created_at; Type: INDEX; Schema: communication; Owner: -
--

CREATE INDEX idx_messages_created_at ON communication.messages USING btree (created_at);


--
-- Name: idx_messages_entity_ref; Type: INDEX; Schema: communication; Owner: -
--

CREATE INDEX idx_messages_entity_ref ON communication.messages USING btree (entity_id, reference_id);


--
-- Name: idx_messages_member_id; Type: INDEX; Schema: communication; Owner: -
--

CREATE INDEX idx_messages_member_id ON communication.messages USING btree (member_id);


--
-- Name: idx_messages_search; Type: INDEX; Schema: communication; Owner: -
--

CREATE INDEX idx_messages_search ON communication.messages USING gin (vector);


--
-- Name: idx_messages_status_id; Type: INDEX; Schema: communication; Owner: -
--

CREATE INDEX idx_messages_status_id ON communication.messages USING btree (status_id);


--
-- Name: idx_support_ticket_entity_ref; Type: INDEX; Schema: communication; Owner: -
--

CREATE INDEX idx_support_ticket_entity_ref ON communication.support_ticket USING btree (entity_id, reference_id);


--
-- Name: idx_support_ticket_priority_id; Type: INDEX; Schema: communication; Owner: -
--

CREATE INDEX idx_support_ticket_priority_id ON communication.support_ticket USING btree (priority_id);


--
-- Name: idx_support_ticket_status_id; Type: INDEX; Schema: communication; Owner: -
--

CREATE INDEX idx_support_ticket_status_id ON communication.support_ticket USING btree (status_id);


--
-- Name: idx_support_ticket_user_id; Type: INDEX; Schema: communication; Owner: -
--

CREATE INDEX idx_support_ticket_user_id ON communication.support_ticket USING btree (user_id);


--
-- Name: idx_event_frequencies_created_by; Type: INDEX; Schema: event; Owner: -
--

CREATE INDEX idx_event_frequencies_created_by ON event.frequencies USING btree (created_by);


--
-- Name: idx_event_frequencies_status_id; Type: INDEX; Schema: event; Owner: -
--

CREATE INDEX idx_event_frequencies_status_id ON event.frequencies USING btree (status_id);


--
-- Name: idx_event_types_activity_id; Type: INDEX; Schema: event; Owner: -
--

CREATE INDEX idx_event_types_activity_id ON event.types USING btree (activity_id);


--
-- Name: idx_event_types_status_id; Type: INDEX; Schema: event; Owner: -
--

CREATE INDEX idx_event_types_status_id ON event.types USING btree (status_id);


--
-- Name: idx_events_actor; Type: INDEX; Schema: event; Owner: -
--

CREATE INDEX idx_events_actor ON event.events USING btree (actor, actor_id);


--
-- Name: idx_events_frequency_id; Type: INDEX; Schema: event; Owner: -
--

CREATE INDEX idx_events_frequency_id ON event.events USING btree (frequency_id);


--
-- Name: idx_events_type_id; Type: INDEX; Schema: event; Owner: -
--

CREATE INDEX idx_events_type_id ON event.events USING btree (type_id);


--
-- Name: idx_events_user_id; Type: INDEX; Schema: event; Owner: -
--

CREATE INDEX idx_events_user_id ON event.events USING btree (user_id);


--
-- Name: idx_events_validity_id; Type: INDEX; Schema: event; Owner: -
--

CREATE INDEX idx_events_validity_id ON event.events USING btree (validity_id);


--
-- Name: uc_event_frequencies_code; Type: INDEX; Schema: event; Owner: -
--

CREATE UNIQUE INDEX uc_event_frequencies_code ON event.frequencies USING btree (code);


--
-- Name: uc_event_types_code; Type: INDEX; Schema: event; Owner: -
--

CREATE UNIQUE INDEX uc_event_types_code ON event.types USING btree (code);


--
-- Name: idx_agreements_created_by; Type: INDEX; Schema: notraius; Owner: -
--


--
-- Name: idx_agreements_driver_id; Type: INDEX; Schema: notraius; Owner: -
--


--
-- Name: idx_agreements_validity_id; Type: INDEX; Schema: notraius; Owner: -
--


--
-- Name: idx_attachments_created_by; Type: INDEX; Schema: notraius; Owner: -
--

CREATE INDEX idx_attachments_created_by ON notraius.attachments USING btree (created_by);


--
-- Name: idx_attachments_entity_id; Type: INDEX; Schema: notraius; Owner: -
--

CREATE INDEX idx_attachments_entity_id ON notraius.attachments USING btree (entity_id);


--
-- Name: idx_attachments_entity_reference; Type: INDEX; Schema: notraius; Owner: -
--

CREATE INDEX idx_attachments_entity_reference ON notraius.attachments USING btree (entity_id, reference_id);


--
-- Name: idx_attachments_media_id; Type: INDEX; Schema: notraius; Owner: -
--

CREATE INDEX idx_attachments_media_id ON notraius.attachments USING btree (media_id);


--
-- Name: idx_attachments_reference_id; Type: INDEX; Schema: notraius; Owner: -
--

CREATE INDEX idx_attachments_reference_id ON notraius.attachments USING btree (reference_id);


--
-- Name: idx_attachments_status_id; Type: INDEX; Schema: notraius; Owner: -
--

CREATE INDEX idx_attachments_status_id ON notraius.attachments USING btree (status_id);


--
-- Name: idx_course_types_type_id; Type: INDEX; Schema: notraius; Owner: -
--


--
-- Name: idx_courses_contract_id; Type: INDEX; Schema: notraius; Owner: -
--


--
-- Name: idx_courses_currency_id; Type: INDEX; Schema: notraius; Owner: -
--


--
-- Name: idx_courses_driver_id; Type: INDEX; Schema: notraius; Owner: -
--


--
-- Name: idx_courses_order_id; Type: INDEX; Schema: notraius; Owner: -
--


--
-- Name: idx_courses_order_sequence; Type: INDEX; Schema: notraius; Owner: -
--


--
-- Name: idx_courses_receiver_id; Type: INDEX; Schema: notraius; Owner: -
--


--
-- Name: idx_courses_sender_id; Type: INDEX; Schema: notraius; Owner: -
--


--
-- Name: idx_courses_status_id; Type: INDEX; Schema: notraius; Owner: -
--


--
-- Name: idx_courses_type_id; Type: INDEX; Schema: notraius; Owner: -
--


--
-- Name: idx_incidents_created_by; Type: INDEX; Schema: notraius; Owner: -
--


--
-- Name: idx_incidents_search; Type: INDEX; Schema: notraius; Owner: -
--


--
-- Name: idx_incidents_shipment_id; Type: INDEX; Schema: notraius; Owner: -
--


--
-- Name: idx_incidents_status_id; Type: INDEX; Schema: notraius; Owner: -
--


--
-- Name: idx_incidents_type_id; Type: INDEX; Schema: notraius; Owner: -
--


--
-- Name: idx_locations_search; Type: INDEX; Schema: notraius; Owner: -
--


--
-- Name: idx_locations_state_id; Type: INDEX; Schema: notraius; Owner: -
--


--
-- Name: idx_locations_status_id; Type: INDEX; Schema: notraius; Owner: -
--


--
-- Name: idx_locations_user_id; Type: INDEX; Schema: notraius; Owner: -
--


--
-- Name: idx_orders_receiver_id; Type: INDEX; Schema: notraius; Owner: -
--


--
-- Name: idx_orders_search; Type: INDEX; Schema: notraius; Owner: -
--


--
-- Name: idx_orders_sender_id; Type: INDEX; Schema: notraius; Owner: -
--


--
-- Name: idx_orders_status_id; Type: INDEX; Schema: notraius; Owner: -
--


--
-- Name: idx_qualifications_activity; Type: INDEX; Schema: notraius; Owner: -
--


--
-- Name: idx_qualifications_created_by; Type: INDEX; Schema: notraius; Owner: -
--


--
-- Name: idx_qualifications_search; Type: INDEX; Schema: notraius; Owner: -
--


--
-- Name: idx_qualifications_status_id; Type: INDEX; Schema: notraius; Owner: -
--


--
-- Name: idx_quotes_course_id; Type: INDEX; Schema: notraius; Owner: -
--


--
-- Name: idx_quotes_search; Type: INDEX; Schema: notraius; Owner: -
--


--
-- Name: idx_quotes_validity_id; Type: INDEX; Schema: notraius; Owner: -
--


--
-- Name: idx_shipments_course_id; Type: INDEX; Schema: notraius; Owner: -
--


--
-- Name: idx_shipments_location_id; Type: INDEX; Schema: notraius; Owner: -
--


--
-- Name: idx_shipments_operation_id; Type: INDEX; Schema: notraius; Owner: -
--


--
-- Name: idx_shipments_status_id; Type: INDEX; Schema: notraius; Owner: -
--


--
-- Name: idx_shipments_token_id; Type: INDEX; Schema: notraius; Owner: -
--


--
-- Name: idx_statuses_code_entity; Type: INDEX; Schema: notraius; Owner: -
--

CREATE INDEX idx_statuses_code_entity ON notraius.statuses USING btree (code, entity_id);


--
-- Name: idx_statuses_entity_id; Type: INDEX; Schema: notraius; Owner: -
--

CREATE INDEX idx_statuses_entity_id ON notraius.statuses USING btree (entity_id);


--
-- Name: idx_validity_status_id; Type: INDEX; Schema: notraius; Owner: -
--

CREATE INDEX idx_validity_status_id ON notraius.validity USING btree (status_id);


--
-- Name: idx_preferences_user_id; Type: INDEX; Schema: security; Owner: -
--

CREATE INDEX idx_preferences_user_id ON security.preferences USING btree (user_id);


--
-- Name: idx_rate_limit_activity_code; Type: INDEX; Schema: security; Owner: -
--

CREATE INDEX idx_rate_limit_activity_code ON security.rate_limit USING btree (activity_code);


--
-- Name: idx_rate_limit_user_activity; Type: INDEX; Schema: security; Owner: -
--

CREATE INDEX idx_rate_limit_user_activity ON security.rate_limit USING btree (user_id, activity_code);


--
-- Name: idx_rate_limit_user_id; Type: INDEX; Schema: security; Owner: -
--

CREATE INDEX idx_rate_limit_user_id ON security.rate_limit USING btree (user_id);


--
-- Name: idx_settings_platform_code; Type: INDEX; Schema: security; Owner: -
--

CREATE INDEX idx_settings_platform_code ON security.settings USING btree (platform_code);


--
-- Name: idx_settings_user_id; Type: INDEX; Schema: security; Owner: -
--

CREATE INDEX idx_settings_user_id ON security.settings USING btree (user_id);


--
-- Name: idx_tokens_purpose_id; Type: INDEX; Schema: security; Owner: -
--

CREATE INDEX idx_tokens_purpose_id ON security.tokens USING btree (purpose_id);


--
-- Name: idx_tokens_user_id; Type: INDEX; Schema: security; Owner: -
--

CREATE INDEX idx_tokens_user_id ON security.tokens USING btree (user_id);


--
-- Name: idx_webhooks_activity_id; Type: INDEX; Schema: security; Owner: -
--

CREATE INDEX idx_webhooks_activity_id ON security.webhooks USING btree (activity_id);


--
-- Name: idx_webhooks_actor; Type: INDEX; Schema: security; Owner: -
--

CREATE INDEX idx_webhooks_actor ON security.webhooks USING btree (actor);


--
-- Name: idx_webhooks_user_id; Type: INDEX; Schema: security; Owner: -
--

CREATE INDEX idx_webhooks_user_id ON security.webhooks USING btree (created_by);


--
-- Name: uk_settings_one_active_admin; Type: INDEX; Schema: security; Owner: -
--

CREATE UNIQUE INDEX uk_settings_one_active_admin ON security.settings USING btree (platform_code) WHERE (((platform_role)::text = 'ADMIN'::text) AND (status_id = 'df002d5c-10bd-4141-88f1-25d71c1ffafd'::uuid));


--
-- Name: idx_plan_feature_activity_id; Type: INDEX; Schema: subscription; Owner: -
--

CREATE INDEX idx_plan_feature_activity_id ON subscription.plan_features USING btree (activity_id);


--
-- Name: idx_plan_feature_feature_id; Type: INDEX; Schema: subscription; Owner: -
--

CREATE INDEX idx_plan_feature_feature_id ON subscription.plan_features USING btree (feature_id);


--
-- Name: idx_plan_feature_plan_id; Type: INDEX; Schema: subscription; Owner: -
--

CREATE INDEX idx_plan_feature_plan_id ON subscription.plan_features USING btree (plan_id);


--
-- Name: idx_plan_feature_status_id; Type: INDEX; Schema: subscription; Owner: -
--

CREATE INDEX idx_plan_feature_status_id ON subscription.plan_features USING btree (status_id);


--
-- Name: idx_plans_validity_id; Type: INDEX; Schema: subscription; Owner: -
--

CREATE INDEX idx_plans_validity_id ON subscription.plans USING btree (validity_id);


--
-- Name: idx_pricing_currency_id; Type: INDEX; Schema: subscription; Owner: -
--

CREATE INDEX idx_pricing_currency_id ON subscription.pricing USING btree (currency_id);


--
-- Name: idx_pricing_plan_id; Type: INDEX; Schema: subscription; Owner: -
--

CREATE INDEX idx_pricing_plan_id ON subscription.pricing USING btree (plan_id);


--
-- Name: idx_pricing_status_id; Type: INDEX; Schema: subscription; Owner: -
--

CREATE INDEX idx_pricing_status_id ON subscription.pricing USING btree (status_id);


--
-- Name: idx_subscriptions_currency_id; Type: INDEX; Schema: subscription; Owner: -
--

CREATE INDEX idx_subscriptions_currency_id ON subscription.subscriptions USING btree (currency_id);


--
-- Name: idx_subscriptions_plan_id; Type: INDEX; Schema: subscription; Owner: -
--

CREATE INDEX idx_subscriptions_plan_id ON subscription.subscriptions USING btree (plan_id);


--
-- Name: idx_subscriptions_user_created; Type: INDEX; Schema: subscription; Owner: -
--

CREATE INDEX idx_subscriptions_user_created ON subscription.subscriptions USING btree (user_id, created_at DESC);


--
-- Name: idx_subscriptions_user_id; Type: INDEX; Schema: subscription; Owner: -
--

CREATE INDEX idx_subscriptions_user_id ON subscription.subscriptions USING btree (user_id);


--
-- Name: idx_subscriptions_validity; Type: INDEX; Schema: subscription; Owner: -
--

CREATE INDEX idx_subscriptions_validity ON subscription.subscriptions USING btree (validity);


--
-- Name: uc_plans_code; Type: INDEX; Schema: subscription; Owner: -
--

CREATE UNIQUE INDEX uc_plans_code ON subscription.plans USING btree (code);


--
-- Name: chat_requests tsvector_update; Type: TRIGGER; Schema: communication; Owner: -
--

CREATE TRIGGER tsvector_update BEFORE INSERT OR UPDATE ON communication.chat_requests FOR EACH ROW EXECUTE FUNCTION public.tsvector_update_chat_requests();


--
-- Name: chats tsvector_update; Type: TRIGGER; Schema: communication; Owner: -
--

CREATE TRIGGER tsvector_update BEFORE INSERT OR UPDATE ON communication.chats FOR EACH ROW EXECUTE FUNCTION public.tsvector_update_chats();


--
-- Name: messages tsvector_update; Type: TRIGGER; Schema: communication; Owner: -
--

CREATE TRIGGER tsvector_update BEFORE INSERT OR UPDATE ON communication.messages FOR EACH ROW EXECUTE FUNCTION public.tsvector_update_messages();


--
-- Name: incidents tsvector_update; Type: TRIGGER; Schema: notraius; Owner: -
--


--
-- Name: locations tsvector_update; Type: TRIGGER; Schema: notraius; Owner: -
--


--
-- Name: orders tsvector_update; Type: TRIGGER; Schema: notraius; Owner: -
--


--
-- Name: qualifications tsvector_update; Type: TRIGGER; Schema: notraius; Owner: -
--


--
-- Name: quotes tsvector_update; Type: TRIGGER; Schema: notraius; Owner: -
--


--
-- Name: consents consents_permission_id_fkey; Type: FK CONSTRAINT; Schema: access; Owner: -
--

ALTER TABLE ONLY access.consents
    ADD CONSTRAINT consents_permission_id_fkey FOREIGN KEY (permission_id) REFERENCES access.permissions(id);


--
-- Name: callbacks fk_app_callbacks_app; Type: FK CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.callbacks
    ADD CONSTRAINT fk_app_callbacks_app FOREIGN KEY (app_id) REFERENCES app.apps(id) ON DELETE CASCADE;


--
-- Name: keys fk_app_keys_app; Type: FK CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.keys
    ADD CONSTRAINT fk_app_keys_app FOREIGN KEY (app_id) REFERENCES app.apps(id) ON DELETE CASCADE;


--
-- Name: keys fk_app_keys_token; Type: FK CONSTRAINT; Schema: app; Owner: -
--

ALTER TABLE ONLY app.keys
    ADD CONSTRAINT fk_app_keys_token FOREIGN KEY (token_id) REFERENCES security.tokens(id) ON DELETE CASCADE;


--
-- Name: role fk_communication_role_entity; Type: FK CONSTRAINT; Schema: communication; Owner: -
--

ALTER TABLE ONLY communication.role
    ADD CONSTRAINT fk_communication_role_entity FOREIGN KEY (entity_id) REFERENCES notraius.entities(id);


--
-- Name: notifications fk_notifications_on_status; Type: FK CONSTRAINT; Schema: communication; Owner: -
--

ALTER TABLE ONLY communication.notifications
    ADD CONSTRAINT fk_notifications_on_status FOREIGN KEY (status_id) REFERENCES notraius.statuses(id);


--
-- Name: agreements fk_agreements_validity; Type: FK CONSTRAINT; Schema: notraius; Owner: -
--


--
-- Name: course_types fk_course_types_on_type; Type: FK CONSTRAINT; Schema: notraius; Owner: -
--


--
-- Name: courses fk_courses_on_order; Type: FK CONSTRAINT; Schema: notraius; Owner: -
--


--
-- Name: courses fk_courses_on_type; Type: FK CONSTRAINT; Schema: notraius; Owner: -
--


--
-- Name: incidents fk_incidents_on_shipment; Type: FK CONSTRAINT; Schema: notraius; Owner: -
--


--
-- Name: incidents fk_incidents_on_status; Type: FK CONSTRAINT; Schema: notraius; Owner: -
--


--
-- Name: incidents fk_incidents_on_type; Type: FK CONSTRAINT; Schema: notraius; Owner: -
--


--
-- Name: locations fk_locations_on_status; Type: FK CONSTRAINT; Schema: notraius; Owner: -
--


--
-- Name: qualifications fk_qualifications_on_status; Type: FK CONSTRAINT; Schema: notraius; Owner: -
--


--
-- Name: quotes fk_quotes_on_course; Type: FK CONSTRAINT; Schema: notraius; Owner: -
--


--
-- Name: shipments fk_shipments_on_course; Type: FK CONSTRAINT; Schema: notraius; Owner: -
--


--
-- Name: shipments fk_shipments_on_location; Type: FK CONSTRAINT; Schema: notraius; Owner: -
--


--
-- Name: shipments fk_shipments_on_operation; Type: FK CONSTRAINT; Schema: notraius; Owner: -
--


--
-- Name: shipments fk_shipments_on_token; Type: FK CONSTRAINT; Schema: notraius; Owner: -
--


--
-- Name: statuses fk_statuses_on_entity; Type: FK CONSTRAINT; Schema: notraius; Owner: -
--

ALTER TABLE ONLY notraius.statuses
    ADD CONSTRAINT fk_statuses_on_entity FOREIGN KEY (entity_id) REFERENCES notraius.entities(id);


--
-- Name: validity validity_status_id_fkey; Type: FK CONSTRAINT; Schema: notraius; Owner: -
--

ALTER TABLE ONLY notraius.validity
    ADD CONSTRAINT validity_status_id_fkey FOREIGN KEY (status_id) REFERENCES notraius.statuses(id);


--
-- Name: tokens fk_tokens_on_purpose; Type: FK CONSTRAINT; Schema: security; Owner: -
--

ALTER TABLE ONLY security.tokens
    ADD CONSTRAINT fk_tokens_on_purpose FOREIGN KEY (purpose_id) REFERENCES security.token_purposes(id);


--
-- Name: plan_features fk_plan_feature_activity; Type: FK CONSTRAINT; Schema: subscription; Owner: -
--

ALTER TABLE ONLY subscription.plan_features
    ADD CONSTRAINT fk_plan_feature_activity FOREIGN KEY (activity_id) REFERENCES access.activities(id);


--
-- Name: plan_features fk_plan_feature_feature; Type: FK CONSTRAINT; Schema: subscription; Owner: -
--

ALTER TABLE ONLY subscription.plan_features
    ADD CONSTRAINT fk_plan_feature_feature FOREIGN KEY (feature_id) REFERENCES subscription.features(id);


--
-- Name: plan_features fk_plan_feature_plan; Type: FK CONSTRAINT; Schema: subscription; Owner: -
--

ALTER TABLE ONLY subscription.plan_features
    ADD CONSTRAINT fk_plan_feature_plan FOREIGN KEY (plan_id) REFERENCES subscription.plans(id);


--
-- Name: plan_features fk_plan_feature_status; Type: FK CONSTRAINT; Schema: subscription; Owner: -
--

ALTER TABLE ONLY subscription.plan_features
    ADD CONSTRAINT fk_plan_feature_status FOREIGN KEY (status_id) REFERENCES notraius.statuses(id);


--
-- Name: plans fk_plans_validity; Type: FK CONSTRAINT; Schema: subscription; Owner: -
--

ALTER TABLE ONLY subscription.plans
    ADD CONSTRAINT fk_plans_validity FOREIGN KEY (validity_id) REFERENCES notraius.validity(id);


--
-- Name: pricing fk_pricing_plan; Type: FK CONSTRAINT; Schema: subscription; Owner: -
--

ALTER TABLE ONLY subscription.pricing
    ADD CONSTRAINT fk_pricing_plan FOREIGN KEY (plan_id) REFERENCES subscription.plans(id);


--
-- Name: pricing fk_pricing_status; Type: FK CONSTRAINT; Schema: subscription; Owner: -
--

ALTER TABLE ONLY subscription.pricing
    ADD CONSTRAINT fk_pricing_status FOREIGN KEY (status_id) REFERENCES notraius.statuses(id);


--
-- Name: subscriptions fk_subscriptions_plan; Type: FK CONSTRAINT; Schema: subscription; Owner: -
--

ALTER TABLE ONLY subscription.subscriptions
    ADD CONSTRAINT fk_subscriptions_plan FOREIGN KEY (plan_id) REFERENCES subscription.plans(id);


--
-- Name: subscriptions fk_subscriptions_validity; Type: FK CONSTRAINT; Schema: subscription; Owner: -
--

ALTER TABLE ONLY subscription.subscriptions
    ADD CONSTRAINT fk_subscriptions_validity FOREIGN KEY (validity) REFERENCES notraius.validity(id);


--
-- PostgreSQL database dump complete
--

--unrestrict msScbTH3QkwB8zEWNxU8eLNxAfpJN92sJje0ND3jbppMNuZLV9XEWpeOZJOCvd3

