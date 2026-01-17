--
-- PostgreSQL database dump
--

-- Dumped from database version 14.18 (Homebrew)
-- Dumped by pg_dump version 14.18 (Homebrew)

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

--
-- Name: uuid-ossp; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS "uuid-ossp" WITH SCHEMA public;


--
-- Name: EXTENSION "uuid-ossp"; Type: COMMENT; Schema: -; Owner: -
--

COMMENT ON EXTENSION "uuid-ossp" IS 'generate universally unique identifiers (UUIDs)';


SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: aclf_mappings; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.aclf_mappings (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    business_tag text NOT NULL,
    aclf_file_id uuid,
    symbol_id uuid,
    c_data_type text,
    memory_offset integer,
    project_id uuid,
    mapping_strategy text,
    confidence_score double precision DEFAULT 1.0,
    raw_config_context text,
    external_layer_ref text,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    aclf_file_path character varying(255),
    attribute_tag character varying(255),
    source_symbol_id uuid,
    raw_data_path character varying(255),
    zconnect_json_path character varying(255),
    business_description character varying(255)
);


--
-- Name: agent_tasks; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.agent_tasks (
    id uuid DEFAULT public.uuid_generate_v4() NOT NULL,
    project_id uuid,
    agent_name text,
    status text,
    started_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    completed_at timestamp without time zone,
    logs text
);


--
-- Name: blueprint_refinements; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.blueprint_refinements (
    id uuid NOT NULL,
    blueprint_path character varying(500) NOT NULL,
    change_summary text,
    code_changes_detected boolean,
    created_at timestamp(6) without time zone,
    created_by character varying(100),
    deleted_symbols_count integer,
    modified_symbols_count integer,
    new_symbols_count integer,
    parent_blueprint_path character varying(500),
    project_name character varying(100) NOT NULL,
    refinement_prompt text NOT NULL,
    tokens_used integer,
    version integer NOT NULL
);


--
-- Name: data_lineage; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.data_lineage (
    id uuid DEFAULT public.uuid_generate_v4() NOT NULL,
    source_symbol_id uuid,
    target_symbol_id uuid,
    flow_type text NOT NULL,
    transformation_logic text,
    project_id uuid
);


--
-- Name: dependency_lineage; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.dependency_lineage (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    source_project_id uuid,
    source_file_id uuid,
    target_service_name text,
    protocol text,
    connection_type text,
    raw_evidence text,
    confidence_score double precision,
    discovered_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP
);


--
-- Name: global_dictionary; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.global_dictionary (
    id bigint NOT NULL,
    technical_name text NOT NULL,
    business_name text,
    standard_label text,
    domain text,
    description text,
    confidence_score double precision,
    created_by_agent boolean DEFAULT true
);


--
-- Name: global_dictionary_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.global_dictionary_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: global_dictionary_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.global_dictionary_id_seq OWNED BY public.global_dictionary.id;


--
-- Name: missing_dependencies; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.missing_dependencies (
    id uuid DEFAULT public.uuid_generate_v4() NOT NULL,
    project_id uuid,
    file_path text NOT NULL,
    symbol_name text NOT NULL,
    dependency_type text,
    status text DEFAULT 'detected'::text,
    detected_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP
);


--
-- Name: project_tech_stack; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.project_tech_stack (
    project_id uuid NOT NULL,
    tech_stack character varying(255)
);


--
-- Name: projects; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.projects (
    id uuid DEFAULT public.uuid_generate_v4() NOT NULL,
    name text NOT NULL,
    description text,
    base_path text NOT NULL,
    git_url text,
    tech_stack text[],
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    domain text DEFAULT 'General'::text,
    total_trust_score double precision DEFAULT 0.0,
    ambiguity_count integer DEFAULT 0,
    mapped_rules_count integer DEFAULT 0,
    last_score_refresh timestamp without time zone,
    ingestion_progress integer,
    status character varying(255),
    current_file character varying(255),
    estimated_remaining_seconds bigint,
    processed_files bigint,
    total_files bigint
);


--
-- Name: source_files; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.source_files (
    id uuid DEFAULT public.uuid_generate_v4() NOT NULL,
    project_id uuid,
    file_path text NOT NULL,
    file_name text NOT NULL,
    extension text NOT NULL,
    content_hash text,
    last_indexed timestamp without time zone,
    file_summary text,
    business_context text,
    storage_key text
);


--
-- Name: symbol_relationships; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.symbol_relationships (
    id uuid NOT NULL,
    context character varying(255),
    relationship_type character varying(255) NOT NULL,
    source_column integer,
    source_line integer,
    source_symbol_id uuid NOT NULL,
    target_symbol_id uuid NOT NULL
);


--
-- Name: symbols; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.symbols (
    id uuid DEFAULT public.uuid_generate_v4() NOT NULL,
    file_id uuid,
    name text NOT NULL,
    category text NOT NULL,
    data_type text,
    start_line integer NOT NULL,
    start_column integer NOT NULL,
    end_line integer NOT NULL,
    end_column integer NOT NULL,
    visibility text,
    is_definition boolean DEFAULT true,
    metadata jsonb,
    analysis_status text DEFAULT 'PENDING'::text
);


--
-- Name: global_dictionary id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.global_dictionary ALTER COLUMN id SET DEFAULT nextval('public.global_dictionary_id_seq'::regclass);


--
-- Name: aclf_mappings aclf_mappings_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.aclf_mappings
    ADD CONSTRAINT aclf_mappings_pkey PRIMARY KEY (id);


--
-- Name: agent_tasks agent_tasks_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_tasks
    ADD CONSTRAINT agent_tasks_pkey PRIMARY KEY (id);


--
-- Name: blueprint_refinements blueprint_refinements_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.blueprint_refinements
    ADD CONSTRAINT blueprint_refinements_pkey PRIMARY KEY (id);


--
-- Name: data_lineage data_lineage_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.data_lineage
    ADD CONSTRAINT data_lineage_pkey PRIMARY KEY (id);


--
-- Name: dependency_lineage dependency_lineage_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dependency_lineage
    ADD CONSTRAINT dependency_lineage_pkey PRIMARY KEY (id);


--
-- Name: global_dictionary global_dictionary_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.global_dictionary
    ADD CONSTRAINT global_dictionary_pkey PRIMARY KEY (id);


--
-- Name: global_dictionary global_dictionary_technical_name_domain_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.global_dictionary
    ADD CONSTRAINT global_dictionary_technical_name_domain_key UNIQUE (technical_name, domain);


--
-- Name: missing_dependencies missing_dependencies_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.missing_dependencies
    ADD CONSTRAINT missing_dependencies_pkey PRIMARY KEY (id);


--
-- Name: projects projects_name_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.projects
    ADD CONSTRAINT projects_name_key UNIQUE (name);


--
-- Name: projects projects_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.projects
    ADD CONSTRAINT projects_pkey PRIMARY KEY (id);


--
-- Name: source_files source_files_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.source_files
    ADD CONSTRAINT source_files_pkey PRIMARY KEY (id);


--
-- Name: source_files source_files_project_id_file_path_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.source_files
    ADD CONSTRAINT source_files_project_id_file_path_key UNIQUE (project_id, file_path);


--
-- Name: symbol_relationships symbol_relationships_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.symbol_relationships
    ADD CONSTRAINT symbol_relationships_pkey PRIMARY KEY (id);


--
-- Name: symbols symbols_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.symbols
    ADD CONSTRAINT symbols_pkey PRIMARY KEY (id);


--
-- Name: symbol_relationships ukdpotxj71ekrt09m623np9a32v; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.symbol_relationships
    ADD CONSTRAINT ukdpotxj71ekrt09m623np9a32v UNIQUE (source_symbol_id, target_symbol_id, relationship_type);


--
-- Name: idx_aclf_symbol; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_aclf_symbol ON public.aclf_mappings USING btree (symbol_id);


--
-- Name: idx_aclf_tag; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_aclf_tag ON public.aclf_mappings USING btree (business_tag);


--
-- Name: idx_mapping_confidence; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_mapping_confidence ON public.aclf_mappings USING btree (project_id, confidence_score);


--
-- Name: idx_symbols_metadata_gin; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_symbols_metadata_gin ON public.symbols USING gin (metadata);


--
-- Name: aclf_mappings aclf_mappings_aclf_file_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.aclf_mappings
    ADD CONSTRAINT aclf_mappings_aclf_file_id_fkey FOREIGN KEY (aclf_file_id) REFERENCES public.source_files(id);


--
-- Name: aclf_mappings aclf_mappings_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.aclf_mappings
    ADD CONSTRAINT aclf_mappings_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.projects(id);


--
-- Name: aclf_mappings aclf_mappings_symbol_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.aclf_mappings
    ADD CONSTRAINT aclf_mappings_symbol_id_fkey FOREIGN KEY (symbol_id) REFERENCES public.symbols(id);


--
-- Name: agent_tasks agent_tasks_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.agent_tasks
    ADD CONSTRAINT agent_tasks_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.projects(id);


--
-- Name: data_lineage data_lineage_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.data_lineage
    ADD CONSTRAINT data_lineage_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.projects(id);


--
-- Name: data_lineage data_lineage_source_symbol_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.data_lineage
    ADD CONSTRAINT data_lineage_source_symbol_id_fkey FOREIGN KEY (source_symbol_id) REFERENCES public.symbols(id);


--
-- Name: data_lineage data_lineage_target_symbol_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.data_lineage
    ADD CONSTRAINT data_lineage_target_symbol_id_fkey FOREIGN KEY (target_symbol_id) REFERENCES public.symbols(id);


--
-- Name: dependency_lineage dependency_lineage_source_file_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dependency_lineage
    ADD CONSTRAINT dependency_lineage_source_file_id_fkey FOREIGN KEY (source_file_id) REFERENCES public.source_files(id);


--
-- Name: dependency_lineage dependency_lineage_source_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dependency_lineage
    ADD CONSTRAINT dependency_lineage_source_project_id_fkey FOREIGN KEY (source_project_id) REFERENCES public.projects(id);


--
-- Name: symbol_relationships fk3tt478niyi81iu3xk9edd5p2n; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.symbol_relationships
    ADD CONSTRAINT fk3tt478niyi81iu3xk9edd5p2n FOREIGN KEY (target_symbol_id) REFERENCES public.symbols(id);


--
-- Name: symbol_relationships fk45xsp6tqyu7jb4262iblso7nd; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.symbol_relationships
    ADD CONSTRAINT fk45xsp6tqyu7jb4262iblso7nd FOREIGN KEY (source_symbol_id) REFERENCES public.symbols(id);


--
-- Name: project_tech_stack fk7w262lv0btlw1x6rm97f7o69o; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.project_tech_stack
    ADD CONSTRAINT fk7w262lv0btlw1x6rm97f7o69o FOREIGN KEY (project_id) REFERENCES public.projects(id);


--
-- Name: missing_dependencies missing_dependencies_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.missing_dependencies
    ADD CONSTRAINT missing_dependencies_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.projects(id) ON DELETE CASCADE;


--
-- Name: source_files source_files_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.source_files
    ADD CONSTRAINT source_files_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.projects(id) ON DELETE CASCADE;


--
-- Name: symbols symbols_file_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.symbols
    ADD CONSTRAINT symbols_file_id_fkey FOREIGN KEY (file_id) REFERENCES public.source_files(id) ON DELETE CASCADE;


--
-- PostgreSQL database dump complete
--

