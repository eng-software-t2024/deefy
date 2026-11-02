# Atividades - Playlist Compartilhada

Registro das atividades do quadro de tarefas sobre o compartilhamento de
playlists. Status conforme o ultimo acompanhamento realizado.

## Concluidas

- **#29 - Definir permissoes de compartilhamento:** VIEW e EDITOR.
- **#30 - Criar a tabela `PLAYLIST_COMPARTILHADA`:** tabela para usuarios especificos.
- **#31 - Adicionar atributos de configuracao:** link, token e permissao na tabela `PLAYLIST`.
- **#32 - Criar entidades, repositories, services e DTOs necessarios.**
- **#33 - Criar endpoint para compartilhar com um usuario.**
- **#34 - Criar endpoint para alterar a permissao de um usuario.**
- **#35 - Criar endpoint para revogar o acesso de um usuario.**
- **#36 - Criar endpoint para gerar ou ativar o compartilhamento por link.**
- **#37 - Criar endpoint para alterar a permissao do link.**
- **#38 - Criar endpoint para desativar ou revogar o link.**
- **#39 - Validar permissoes ao adicionar, remover e reordenar musicas.**
- **#40 - Criar tela para compartilhar com usuarios especificos.**
- **#41 - Criar tela para ativar, copiar e revogar o link.**
- **#42 - Adicionar testes para proprietario, usuario VIEW, usuario EDITOR, acesso por link e usuario sem acesso.**

## Em andamento

Nenhuma.

## Pendentes

Nenhuma.

## Estado tecnico atual

As estruturas de banco, modelo, DTOs, repositories, services, endpoints, telas
e testes do compartilhamento foram implementadas. O backend valida acesso do
dono, `VIEW`, `EDITOR`, acesso por link, token invalido e link desativado.
