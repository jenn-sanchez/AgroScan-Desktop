--
-- PostgreSQL database dump
--

\restrict LILCNs2nkm9N2IA0IBwy0fT9bIC25LDKfkIXxXbDiCImcy5G83ebhSlXuVvECWl

-- Dumped from database version 16.13
-- Dumped by pg_dump version 16.13

--
-- Name: postgis; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS postgis WITH SCHEMA public;

--
-- Name: EXTENSION postgis; Type: COMMENT; Schema: -; Owner: -
--

COMMENT ON EXTENSION postgis IS 'PostGIS geometry and geography spatial types and functions';

--
-- Name: dispositivo; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.dispositivo (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    usuario_id uuid NOT NULL,
    tipo text,
    nombre text,
    ultimo_sync timestamp with time zone,
    activo boolean DEFAULT true NOT NULL
);

--
-- Name: lote; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.lote (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    creado_por uuid NOT NULL,
    nombre text NOT NULL,
    area_ha double precision,
    perimetro public.geometry(Polygon,4326),
    creado_en timestamp with time zone DEFAULT now() NOT NULL,
    actualizado_en timestamp with time zone,
    eliminado_en timestamp with time zone
);

--
-- Name: mision; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.mision (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    lote_id uuid NOT NULL,
    parametros_id uuid NOT NULL,
    creado_por uuid NOT NULL,
    nombre text NOT NULL,
    estado text,
    duracion_estimada_min double precision,
    fotos_estimadas integer,
    ruta_waypoints jsonb,
    creado_en timestamp with time zone DEFAULT now() NOT NULL,
    version integer DEFAULT 1 NOT NULL,
    actualizado_en timestamp with time zone,
    eliminado_en timestamp with time zone
);

--
-- Name: parametros_vuelo; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.parametros_vuelo (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    altura_m double precision,
    traslape_lateral_pct double precision,
    traslape_frontal_pct double precision,
    margen_interno_m double precision,
    altura_copa_m double precision,
    modo_captura text
);

--
-- Name: salida; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.salida (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    vuelo_id uuid NOT NULL,
    operador_id uuid,
    dispositivo_id uuid,
    indice integer NOT NULL,
    desde_wp integer,
    hasta_wp integer,
    estado text,
    motivo text,
    inicio_ms bigint,
    fin_ms bigint,
    bateria_inicio integer,
    bateria_fin integer,
    distancia_m double precision,
    video_inicio_ms bigint,
    video_fin_ms bigint,
    actualizado_en timestamp with time zone
);

--
-- Name: usuario; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.usuario (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    email text,
    cedula text NOT NULL,
    nombre text NOT NULL,
    password_hash text NOT NULL,
    activo boolean DEFAULT true NOT NULL,
    creado_en timestamp with time zone DEFAULT now() NOT NULL
);

--
-- Name: vertice; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.vertice (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    lote_id uuid NOT NULL,
    orden integer NOT NULL,
    latitud double precision NOT NULL,
    longitud double precision NOT NULL
);

--
-- Name: vuelo; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.vuelo (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    mision_id uuid NOT NULL,
    iniciado_por uuid,
    estado text,
    modo_captura text,
    perimetro_volado jsonb,
    ruta_volada jsonb,
    total_waypoints integer DEFAULT 0 NOT NULL,
    siguiente_waypoint integer DEFAULT 0 NOT NULL,
    inicio timestamp with time zone,
    fin timestamp with time zone,
    actualizado_en timestamp with time zone
);

--
-- Name: dispositivo dispositivo_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dispositivo
    ADD CONSTRAINT dispositivo_pkey PRIMARY KEY (id);

--
-- Name: lote lote_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.lote
    ADD CONSTRAINT lote_pkey PRIMARY KEY (id);

--
-- Name: mision mision_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mision
    ADD CONSTRAINT mision_pkey PRIMARY KEY (id);

--
-- Name: parametros_vuelo parametros_vuelo_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.parametros_vuelo
    ADD CONSTRAINT parametros_vuelo_pkey PRIMARY KEY (id);

--
-- Name: salida salida_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.salida
    ADD CONSTRAINT salida_pkey PRIMARY KEY (id);

--
-- Name: salida uq_salida_indice; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.salida
    ADD CONSTRAINT uq_salida_indice UNIQUE (vuelo_id, indice);

--
-- Name: vertice uq_vertice_orden; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.vertice
    ADD CONSTRAINT uq_vertice_orden UNIQUE (lote_id, orden);

--
-- Name: usuario usuario_cedula_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.usuario
    ADD CONSTRAINT usuario_cedula_key UNIQUE (cedula);

--
-- Name: usuario usuario_email_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.usuario
    ADD CONSTRAINT usuario_email_key UNIQUE (email);

--
-- Name: usuario usuario_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.usuario
    ADD CONSTRAINT usuario_pkey PRIMARY KEY (id);

--
-- Name: vertice vertice_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.vertice
    ADD CONSTRAINT vertice_pkey PRIMARY KEY (id);

--
-- Name: vuelo vuelo_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.vuelo
    ADD CONSTRAINT vuelo_pkey PRIMARY KEY (id);

--
-- Name: idx_dispositivo_usuario; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_dispositivo_usuario ON public.dispositivo USING btree (usuario_id);

--
-- Name: idx_lote_creado_por; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_lote_creado_por ON public.lote USING btree (creado_por);

--
-- Name: idx_lote_perimetro; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_lote_perimetro ON public.lote USING gist (perimetro);

--
-- Name: idx_mision_creado_por; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_mision_creado_por ON public.mision USING btree (creado_por);

--
-- Name: idx_mision_lote; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_mision_lote ON public.mision USING btree (lote_id);

--
-- Name: idx_mision_parametros; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_mision_parametros ON public.mision USING btree (parametros_id);

--
-- Name: idx_salida_dispositivo; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_salida_dispositivo ON public.salida USING btree (dispositivo_id);

--
-- Name: idx_salida_operador; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_salida_operador ON public.salida USING btree (operador_id);

--
-- Name: idx_vuelo_iniciado_por; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_vuelo_iniciado_por ON public.vuelo USING btree (iniciado_por);

--
-- Name: idx_vuelo_mision; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_vuelo_mision ON public.vuelo USING btree (mision_id);

--
-- Name: dispositivo dispositivo_usuario_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dispositivo
    ADD CONSTRAINT dispositivo_usuario_id_fkey FOREIGN KEY (usuario_id) REFERENCES public.usuario(id);

--
-- Name: lote lote_creado_por_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.lote
    ADD CONSTRAINT lote_creado_por_fkey FOREIGN KEY (creado_por) REFERENCES public.usuario(id);

--
-- Name: mision mision_creado_por_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mision
    ADD CONSTRAINT mision_creado_por_fkey FOREIGN KEY (creado_por) REFERENCES public.usuario(id);

--
-- Name: mision mision_lote_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mision
    ADD CONSTRAINT mision_lote_id_fkey FOREIGN KEY (lote_id) REFERENCES public.lote(id);

--
-- Name: mision mision_parametros_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mision
    ADD CONSTRAINT mision_parametros_id_fkey FOREIGN KEY (parametros_id) REFERENCES public.parametros_vuelo(id);

--
-- Name: salida salida_dispositivo_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.salida
    ADD CONSTRAINT salida_dispositivo_id_fkey FOREIGN KEY (dispositivo_id) REFERENCES public.dispositivo(id);

--
-- Name: salida salida_operador_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.salida
    ADD CONSTRAINT salida_operador_id_fkey FOREIGN KEY (operador_id) REFERENCES public.usuario(id);

--
-- Name: salida salida_vuelo_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.salida
    ADD CONSTRAINT salida_vuelo_id_fkey FOREIGN KEY (vuelo_id) REFERENCES public.vuelo(id) ON DELETE CASCADE;

--
-- Name: vertice vertice_lote_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.vertice
    ADD CONSTRAINT vertice_lote_id_fkey FOREIGN KEY (lote_id) REFERENCES public.lote(id) ON DELETE CASCADE;

--
-- Name: vuelo vuelo_iniciado_por_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.vuelo
    ADD CONSTRAINT vuelo_iniciado_por_fkey FOREIGN KEY (iniciado_por) REFERENCES public.usuario(id);

--
-- Name: vuelo vuelo_mision_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.vuelo
    ADD CONSTRAINT vuelo_mision_id_fkey FOREIGN KEY (mision_id) REFERENCES public.mision(id);

--
-- PostgreSQL database dump complete
--

\unrestrict LILCNs2nkm9N2IA0IBwy0fT9bIC25LDKfkIXxXbDiCImcy5G83ebhSlXuVvECWl

