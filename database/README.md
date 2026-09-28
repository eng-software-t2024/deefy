# Banco de dados

Esta pasta contem scripts SQL usados durante o desenvolvimento do Deefy.

Para rodar o projeto localmente em um computador novo, use apenas:

- `scripts/schema_local.sql`
- `scripts/seed_local_demo.sql`

Esses dois arquivos foram preparados para ambiente publico: nao carregam dump
real do Supabase, nao contem segredos e criam um banco PostgreSQL demonstrativo
compativel com o backend atual.

Os demais scripts foram mantidos como historico do projeto academico e podem
estar desatualizados em relacao ao schema final.

## Migracoes de producao

O backend usa `spring.jpa.hibernate.ddl-auto=none`, portanto ele nao cria nem
atualiza tabelas automaticamente em uma base existente. Antes de habilitar o
fluxo de cadastro pendente em producao, aplique manualmente as migracoes da
pasta `scripts/migrations` no banco de dados do ambiente.

A migracao `001_create_cadastro_pendente.sql` deve ser aplicada uma vez antes
do deploy desta alteracao. O arquivo usa `IF NOT EXISTS` para permitir a
execucao segura em bases que ja possuem a tabela.
