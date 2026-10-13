BEGIN;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'deefy_backend_app') THEN
        RAISE EXCEPTION 'Role obrigatória deefy_backend_app não existe';
    END IF;
END
$$;

CREATE TABLE IF NOT EXISTS public.usuario_foto_perfil (
    usuario_id INTEGER PRIMARY KEY
        REFERENCES public.usuario(id) ON DELETE CASCADE,
    bucket_id TEXT NOT NULL,
    object_path TEXT NOT NULL UNIQUE,
    content_type TEXT NOT NULL,
    size_bytes BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT usuario_foto_perfil_bucket_expected
        CHECK (bucket_id = 'fotos de perfil'),
    CONSTRAINT usuario_foto_perfil_owned_path
        CHECK (
            split_part(object_path, '/', 1) = 'profiles'
            AND split_part(object_path, '/', 2) = usuario_id::TEXT
            AND object_path ~ '^profiles/[0-9]+/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}[.](jpg|png|webp|gif)$'
        ),
    CONSTRAINT usuario_foto_perfil_content_type
        CHECK (content_type IN ('image/jpeg', 'image/png', 'image/webp', 'image/gif')),
    CONSTRAINT usuario_foto_perfil_size
        CHECK (size_bytes > 0 AND size_bytes <= 5242880)
);

REVOKE ALL PRIVILEGES ON TABLE public.usuario_foto_perfil
    FROM PUBLIC, anon, authenticated, service_role;

GRANT SELECT, INSERT, UPDATE, DELETE
    ON TABLE public.usuario_foto_perfil TO postgres;

ALTER TABLE public.usuario_foto_perfil ENABLE ROW LEVEL SECURITY;

DO $$
BEGIN
    GRANT SELECT, INSERT, UPDATE, DELETE
        ON TABLE public.usuario_foto_perfil TO deefy_backend_app;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_policies
        WHERE schemaname = 'public'
          AND tablename = 'usuario_foto_perfil'
          AND policyname = 'usuario_foto_perfil_backend_only'
    ) THEN
        CREATE POLICY usuario_foto_perfil_backend_only
            ON public.usuario_foto_perfil
            FOR ALL
            TO deefy_backend_app
            USING (true)
            WITH CHECK (true);
    END IF;
END
$$;

COMMIT;
