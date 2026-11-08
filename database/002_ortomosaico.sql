-- Ortomosaicos: una fila por vuelo, enlazada a su lote (el polígono) para armar el historial de cada lote en el tiempo.
-- La base solo indexa: los archivos (GeoTIFF, vista previa, teselas) viven en Documentos\AgroScan\ortomosaicos\<lote>\<fecha>_<vuelo>\.
-- Ejecutar una vez en bases creadas antes de este cambio (dron_control.sql ya lo incluye). Se puede repetir sin problema.
CREATE TABLE IF NOT EXISTS public.ortomosaico (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    vuelo_id uuid NOT NULL,
    lote_id uuid NOT NULL,
    creado_por uuid NOT NULL,
    estado text NOT NULL,
    motor text,
    calidad text,
    imagenes text,
    imagenes_usadas integer,
    imagenes_total integer,
    cobertura_pct double precision,
    area_ha double precision,
    gsd_cm_px double precision,
    crs text,
    limites public.geometry(Polygon,4326),
    error_estimado_m double precision,
    tamano_bytes bigint,
    error text,
    creado_en timestamp with time zone DEFAULT now() NOT NULL,
    terminado_en timestamp with time zone,
    CONSTRAINT ortomosaico_pkey PRIMARY KEY (id),
    CONSTRAINT ortomosaico_vuelo_key UNIQUE (vuelo_id),
    CONSTRAINT ortomosaico_estado_check CHECK (estado IN ('PROCESANDO', 'LISTO', 'ERROR', 'CANCELADO', 'INTERRUMPIDO')),
    CONSTRAINT ortomosaico_vuelo_id_fkey FOREIGN KEY (vuelo_id) REFERENCES public.vuelo(id) ON DELETE CASCADE,
    CONSTRAINT ortomosaico_lote_id_fkey FOREIGN KEY (lote_id) REFERENCES public.lote(id),
    CONSTRAINT ortomosaico_creado_por_fkey FOREIGN KEY (creado_por) REFERENCES public.usuario(id)
);

CREATE INDEX IF NOT EXISTS idx_ortomosaico_lote ON public.ortomosaico USING btree (lote_id);
CREATE INDEX IF NOT EXISTS idx_ortomosaico_creado_por ON public.ortomosaico USING btree (creado_por);
CREATE INDEX IF NOT EXISTS idx_ortomosaico_limites ON public.ortomosaico USING gist (limites);
