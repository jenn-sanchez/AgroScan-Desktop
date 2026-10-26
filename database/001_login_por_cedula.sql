-- Login por cédula: la cédula identifica al usuario (única y obligatoria) y el email pasa a ser opcional.
-- Ejecutar una vez en bases creadas antes de este cambio (dron_control.sql ya lo incluye).
ALTER TABLE usuario ALTER COLUMN cedula SET NOT NULL;
ALTER TABLE usuario ADD CONSTRAINT usuario_cedula_key UNIQUE (cedula);
ALTER TABLE usuario ALTER COLUMN email DROP NOT NULL;
