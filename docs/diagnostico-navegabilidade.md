# Implementação Frontend — Caminhos de Navegação do Deefy

**Projeto:** Deefy (Streaming Musical)  
**Escopo:** Implementação de caminhos de navegação (Breadcrumbs) para orientação do usuário  
**Tecnologias:** React 19, React Router 7, CSS Vanilla, React Icons  

---

## 1. Objetivo

Inserir no frontend do Deefy caminhos de navegação (breadcrumbs) que permitam ao usuário:
1. Compreender sua localização atual dentro do sistema.
2. Identificar a hierarquia entre as páginas.
3. Retornar facilmente às seções pai através de links funcionais.

A implementação foi concebida com foco estrito em orientação visual e semântica, sem alterar regras de negócio, serviços, reprodutor de áudio, autenticação ou banco de dados.

---

## 2. Componente Reutilizável: `BreadcrumbNav`

Foi desenvolvido o componente reutilizável `BreadcrumbNav.jsx` acompanhado de sua folha de estilos `BreadcrumbNav.css`.

### Características Técnicas e de Acessibilidade
- **Semântica HTML5:** Utiliza `<nav aria-label="Caminho de navegação">` contendo uma lista ordenada `<ol className="deefy-breadcrumb-list">` e itens `<li>`.
- **Links Funcionais:** Todas as etapas anteriores da hierarquia são componentes `<Link>` do React Router que direcionam às rotas existentes correspondentes.
- **Identificação da Página Atual:** O último elemento é apresentado como texto destacado com o atributo `aria-current="page"`, sinalizando a localização atual tanto visualmente quanto para leitores de tela.
- **Separadores Visuais:** Utiliza ícones `<MdChevronRight aria-hidden="true" />` entre os níveis da trilha.
- **Identidade Visual:** Alinhado com o design escuro do Deefy, com realce em ciano/turquesa (`#39f0d0`) nos estados de hover e `:focus-visible`.
- **Responsividade Mobile:** Adapta-se a telas pequenas (≤ 768px) com overflow horizontal contido e truncamento por reticências (`ellipsis`) em títulos longos.

---

## 3. Páginas Integradas e Hierarquia de Navegação

O componente `BreadcrumbNav` foi adicionado nas seguintes páginas da aplicação:

| Página | Arquivo | Caminho de Navegação Apresentado |
| :--- | :--- | :--- |
| **Playlist do Sistema** | `PlaylistDetail.jsx` | `Início > Playlists > Playlist: [Nome da Playlist]` |
| **Playlist de Usuário** | `UserPlaylistDetail.jsx` | `Início > Playlists > Playlist: [Nome da Playlist]` |
| **Criar / Editar Playlist** | `CreatePlaylist.jsx` | `Início > Playlists > Criar playlist` ou `Editar playlist` |
| **Músicas Favoritas** | `FavoritesDetail.jsx` | `Início > Playlists > Favoritos` |
| **Playlists Recomendadas** | `SystemPlaylists.jsx` | `Início > Playlists > Playlists Deefy` |
| **Catálogo de Artistas** | `Artists.jsx` | `Início > Artistas` |
| **Perfil do Artista** | `ArtistProfile.jsx` | `Início > Artistas > [Nome do Artista]` |
| **Configurações** | `Configuration.jsx` | `Início > Configurações` |
| **Editar Perfil** | `CustomProfile.jsx` | `Início > Configurações > Editar perfil` |
| **Editar Foto** | `EditProfile.jsx` | `Início > Configurações > Editar foto` |

*Em estados de carregamento ou dados não encontrados, a trilha apresenta textos provisórios adequados (`Carregando...` ou `Playlist não encontrada`), sendo atualizada dinamicamente assim que os dados são resolvidos.*

---

## 4. Arquivos do Escopo

### Criados:
- `frontend/src/components/BreadcrumbNav.jsx` — Componente funcional de migalhas de pão.
- `frontend/src/components/BreadcrumbNav.css` — Estilos, separadores, foco acessível e responsividade.

### Atualizados para Inclusão dos Caminhos:
- `frontend/src/pages/PlaylistDetail.jsx`
- `frontend/src/pages/UserPlaylistDetail.jsx`
- `frontend/src/pages/CreatePlaylist.jsx`
- `frontend/src/pages/FavoritesDetail.jsx`
- `frontend/src/pages/SystemPlaylists.jsx`
- `frontend/src/pages/Artists.jsx`
- `frontend/src/pages/ArtistProfile.jsx`
- `frontend/src/pages/Configuration.jsx`
- `frontend/src/pages/CustomProfile.jsx`
- `frontend/src/pages/EditProfile.jsx`

---

## 5. Validação e Resultados

- **Testes Unitários e de Integração (Vitest):**
  - **25 de 25 testes aprovados (100% de sucesso)**
  - `src/services/musicService.playlists.test.js` (2 testes aprovados)
  - `src/services/musicService.artist.test.js` (3 testes aprovados)
  - `src/pages/PlaylistPrivacy.test.jsx` (20 testes aprovados)
- **Compilação de Produção (Vite Build):**
  - Executado `npm run build` com sucesso em 3.29s (0 erros de empacotamento, tipagem ou sintaxe).
- **Ambiente em Containers:**
  - Build e execução validados com sucesso no Docker Compose (`deefy-local-frontend`, `deefy-local-backend`, `deefy-local-db`).

---

## 6. Preservação do Sistema

- **Backend e Banco de Dados:** Nenhuma alteração em Java, Spring Boot ou esquemas de banco.
- **Player de Áudio:** Player global, fila de reprodução, streaming e controles de reprodução 100% inalterados.
- **Autenticação:** JWT, rotas protegidas e controle de acesso preservados.
- **Comportamento Existente:** Os botões originais das páginas, formulários de criação/edição e navegações existentes permanecem funcionando exatamente como foram concebidos.
