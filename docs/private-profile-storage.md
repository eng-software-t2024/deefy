# Contrato privado de Storage para foto de perfil

Este documento descreve uma mudança local e ainda não aplicada. O contrato parte
do commit `57a539688fb594cccf2fe6807b942e91dcd5fad0`.

## Validação local

- Backend: 48 testes aprovados em modo offline.
- Frontend tests **NO-RUN**: o ambiente local não possui Node >= 22.22.2 nem
  dependências instaladas para Vitest/jsdom. O teste
  `usePrivateProfilePhoto.test.jsx` deve rodar em CI ou ambiente aprovado antes
  de merge ou deploy.

## Contrato HTTP

- `POST /api/v1/users/me/photo/upload`: recebe `multipart/form-data` no campo
  `file`, valida nome, extensão, MIME, assinatura e limite de 5 MiB. Retorna
  apenas disponibilidade, MIME, tamanho e data; nunca retorna bucket, path ou
  credencial.
- `GET /api/v1/users/me/photo`: proxy autenticado do conteúdo privado. A
  identidade vem do JWT do backend; não existe parâmetro de usuário ou path.
- `DELETE /api/v1/users/me/photo`: remove o objeto pela Storage API e retorna
  `204`. Se a remoção física falhar, a referência não é removida e a API retorna
  erro explícito.

O endpoint legado que aceitava uma URL arbitrária por `PATCH /me/photo` deixa de
fazer parte do contrato seguro. Valores legados de `fotoperfilurl` não são
apagados automaticamente por estas migrations.

## Ownership e paths

O backend gera `profiles/<usuario-id>/<uuid>.<ext>`. O cliente nunca fornece
bucket ou path. A tabela `usuario_foto_perfil` associa exatamente uma referência
ao `usuario_id`, e um `CHECK` impede paths fora da pasta do proprietário.

O `service_role` permanece apenas no backend. Como uploads feitos com essa chave
não recebem `owner_id` do usuário no Storage, o owner de negócio é mantido na
tabela do Deefy e conferido antes de toda operação física.

## Consistência e compensação

As mutações bloqueiam a linha de usuário durante a operação:

1. substituição: envia o novo objeto, grava a nova referência e remove o objeto
   anterior; qualquer falha antes da conclusão remove o objeto novo e provoca
   rollback da referência;
2. delete: baixa uma cópia limitada a 5 MiB, remove o objeto, elimina a referência
   e, se a persistência falhar, tenta restaurar o mesmo objeto/path;
3. falhas de compensação são anexadas à exceção original e exigem reconciliação
   operacional — nunca são convertidas em sucesso.

O lock pessimista é local à instância do banco, mas hoje permanece aberto
durante chamadas à Storage API (timeout de 15 segundos por chamada). Isso reduz
corridas para o mesmo usuário, porém amplia contenção e não elimina falhas entre
sistemas. A evolução recomendada é separar a mutação física com uma máquina de
estados/outbox idempotente e definir timeout de aquisição do lock antes de uso
em produção com múltiplas instâncias.

Existe um risco residual comum a transações distribuídas: falha do commit do
banco depois de a Storage API confirmar a mutação. Antes de produção, a
homologação deve testar falhas injetadas; para garantia mais forte entre várias
instâncias, a evolução recomendada é um outbox de reconciliação idempotente.

## Bucket e policies

A migration `003` altera somente o schema da aplicação: cria os metadados em
`public.usuario_foto_perfil`, restringe a referência ao bucket exato e falha se
a role `deefy_backend_app` não existir. Ela não altera schemas internos do
Supabase.

A mudança do bucket `fotos de perfil` para privado deve ser feita futuramente
pela Storage API, para que o serviço invalide seus caches. Antes disso, é
obrigatório inventariar todas as policies permissivas existentes em
`storage.objects` e validar o efeito combinado de qualquer policy restritiva.
A proposta em `docs/proposed-storage-policies.sql` está deliberadamente fora das
migrations aplicáveis e não pode ser executada sem homologação e autorização
explícita. O proxy autenticado do backend continua sendo a fronteira de
autorização do recurso; `service_role` permanece restrito ao servidor.

Objetos legados devem ser inventariados e reconciliados antes da troca: somente
URLs inequivocamente pertencentes ao bucket/path esperado podem ser convertidas
em referências; as demais exigem decisão manual.

## Evolução equivalente para catálogo

`imagens` e `musicas` permanecem inalterados neste patch. A evolução segura é:

- manter upload/delete de catálogo restritos ao administrador;
- persistir `bucket`, `path`, MIME, tamanho, UUID e `created_by` em tabela própria;
- separar paths `catalog/images` e `catalog/audio` de qualquer mídia de usuário;
- servir conteúdo público apenas quando a regra de produto for explicitamente
  pública; caso contrário, usar proxy autenticado ou URL assinada curta;
- remover o objeto pela Storage API antes de eliminar a referência e registrar
  reconciliação idempotente para falhas entre sistemas.

Essa evolução deve ser uma mudança separada porque `musica.arquivourl`,
`musica.capaurl` e `playlist.capaurl` hoje armazenam URLs públicas e têm impacto
direto no player e no frontend.
