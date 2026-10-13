-- NÃO APLICAR SEM VALIDAÇÃO NA VERSÃO SELF-HOSTED E APROVAÇÃO EXPLÍCITA.
--
-- Esta proposta não é uma migration do Deefy. Antes de qualquer aplicação,
-- é obrigatório inventariar todas as policies existentes em storage.objects,
-- verificar a interação com policies permissivas e alterar o bucket para
-- privado pela Storage API. A execução deve ocorrer em uma mudança separada,
-- validada primeiro em homologação.

BEGIN;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM storage.buckets WHERE id = 'fotos de perfil'
    ) THEN
        RAISE EXCEPTION 'Bucket de fotos de perfil não existe';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM storage.buckets
        WHERE id = 'fotos de perfil' AND public
    ) THEN
        RAISE EXCEPTION 'Bucket de fotos de perfil ainda é público';
    END IF;
END
$$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_policies
        WHERE schemaname = 'storage' AND tablename = 'objects'
          AND policyname = 'deefy_profile_deny_direct_select'
    ) THEN
        CREATE POLICY deefy_profile_deny_direct_select
            ON storage.objects AS RESTRICTIVE
            FOR SELECT TO anon, authenticated
            USING (bucket_id <> 'fotos de perfil');
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_policies
        WHERE schemaname = 'storage' AND tablename = 'objects'
          AND policyname = 'deefy_profile_deny_direct_insert'
    ) THEN
        CREATE POLICY deefy_profile_deny_direct_insert
            ON storage.objects AS RESTRICTIVE
            FOR INSERT TO anon, authenticated
            WITH CHECK (bucket_id <> 'fotos de perfil');
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_policies
        WHERE schemaname = 'storage' AND tablename = 'objects'
          AND policyname = 'deefy_profile_deny_direct_update'
    ) THEN
        CREATE POLICY deefy_profile_deny_direct_update
            ON storage.objects AS RESTRICTIVE
            FOR UPDATE TO anon, authenticated
            USING (bucket_id <> 'fotos de perfil')
            WITH CHECK (bucket_id <> 'fotos de perfil');
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_policies
        WHERE schemaname = 'storage' AND tablename = 'objects'
          AND policyname = 'deefy_profile_deny_direct_delete'
    ) THEN
        CREATE POLICY deefy_profile_deny_direct_delete
            ON storage.objects AS RESTRICTIVE
            FOR DELETE TO anon, authenticated
            USING (bucket_id <> 'fotos de perfil');
    END IF;
END
$$;

COMMIT;
