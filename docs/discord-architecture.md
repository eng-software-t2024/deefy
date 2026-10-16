# Arquitetura da integração com o Discord

Este documento registra o estado atual e a evolução incremental planejada da
integração entre o Deefy e o Discord. As referências `#56` e `#74` a `#82`
neste texto identificam tarefas do Taiga acadêmico, não GitHub Issues.

## 1. Objetivo e escopo

A integração tem como objetivo oferecer recursos do Deefy dentro de guilds do
Discord por comandos de interação. O código atual conecta um cliente JDA ao
processo Spring e oferece `/deefy ping`, `/entrar`, `/sair` e
`/tocar musica:<titulo-ou-titulo-artista>`, além de `/pausar`, `/retomar`,
`/parar`, `/pular`, `/voltar`, `/reiniciar` e `/fila` com suas operações de
consulta e organização. No modo normal, os onze comandos
são registrados globalmente e podem ser usados em qualquer guild na qual o bot
tenha sido convidado com as permissões necessárias. Um modo de teste explícito
e desativado por padrão pode restringir registro e execução a uma única guild.
`/entrar` e `/sair` conectam e desconectam o bot de canais de voz comuns;
`/tocar` inicia uma faixa ou a coloca na fila FIFO da guild. Cada guild mantém
estado de conexão, fila, histórico e timers independentes, e uma conexão
existente não é movida silenciosamente para outro canal.

Esta arquitetura cobre:

- os componentes Discord existentes e seus limites;
- o ciclo de vida da conexão JDA;
- a convenção para comandos e eventos futuros;
- os pontos de extensão para reprodução, fila e presença;
- a fronteira entre a integração Discord e os serviços de aplicação do Deefy;
- segurança, configuração, observabilidade e estratégia de testes.

Ficam fora do escopo desta tarefa Stage Channels, playlists Discord, repetição,
volume, presença e a ampliação da política de permissões,
bem como alterações de banco, Supabase ou cliente web.

As responsabilidades das partes são distintas:

- **Bot Discord:** adaptação embutida no backend que recebe eventos via JDA e
  transforma interações do Discord em chamadas controladas à aplicação.
- **Backend Deefy:** processo Spring e fonte das regras de negócio. A integração
  Discord atual faz parte desse processo, mas ainda não chama os serviços de
  negócio do Deefy.
- **Discord:** plataforma externa que entrega eventos, mantém guilds/canais e
  recebe respostas e áudio por meio da API consumida pelo JDA.
- **Supabase:** infraestrutura de dados e mídia acessada pelas implementações do
  backend. Listeners Discord não devem acessar banco ou Storage diretamente.
- **Cliente web:** outro adaptador do sistema, que consome as APIs do backend e
  não controla diretamente o cliente JDA.

## 2. Componentes atuais

A implementação está concentrada em `backend/src/main/java/br/com/deefy/discord`.
Existe reprodução de uma faixa por vez, com fila e histórico mantidos em
memória por guild; não há persistência da fila em banco.

| Componente | Responsabilidade | Entradas | Saídas e efeitos | Dependências e limites |
|---|---|---|---|---|
| `DiscordBotProperties` | Mapear e validar a configuração `deefy.discord` | Propriedades Spring derivadas do ambiente | Estado de configuração, política de guild e lista de erros de validação | Aceita token legado em ambiente ou arquivo protegido, sem registrar o valor; a guild só é obrigatória no modo de teste explícito |
| `DiscordBotLifecycle` | Integrar o cliente JDA ao ciclo de vida Spring | Propriedades, listener, registrar e `ReadyEvent` | Criação do JDA, estado `running` e shutdown | Usa `JDABuilder`; não registra comandos nem trata regras de negócio |
| `DiscordCommandRegistrar` | Registrar os comandos disponíveis | `ReadyEvent` e política de guild | `upsertCommand` individual e assíncrono para `/deefy ping`, `/entrar`, `/sair`, `/tocar`, `/pausar`, `/retomar`, `/parar`, `/pular`, `/voltar`, `/reiniciar` e `/fila`, com logs do resultado | Registra globalmente por padrão sem substituir comandos não relacionados; no modo de teste, registra somente na guild configurada |
| `DiscordCommandListener` | Filtrar e rotear interações, botões e autocomplete suportados | Eventos slash, botão e autocomplete | Respostas públicas e uma mensagem Deefy atualizada durante a reprodução | Reconhece interações prontamente, desabilita menções e encaminha botões/slash ao mesmo serviço de domínio; rejeições individuais de botão são efêmeras |
| `DiscordVoiceConnectionService` | Aplicar as regras de entrada e saída de voz por guild | Guild, membro solicitante e estado JDA do canal | Abertura ou fechamento da conexão de voz e resultado controlado | Suporta apenas canais de voz comuns; não move conexão existente e não reproduz áudio |
| `DiscordTrackCatalog` | Resolver título, título + artista ou ID técnico no catálogo | Entrada textual e `MusicRepository` | Faixa normalizada, sugestões ou erro de ausência, ambiguidade ou origem | Normaliza caixa, acentos e espaços; entende créditos importados; recusa URL fora do origin de `DEEFY_API_URL`, assinada ou do Supabase antigo |
| `DiscordPlaybackSessionService` | Possuir sessão, fila e histórico por guild e executar mutações serializadas | Guild, membro, faixa e intenção de controle | Fonte JDA, estado, fila, histórico, timers, conexão ociosa e cleanup restritos à guild | Uma faixa ativa toca por vez; novas faixas entram FIFO; valida sessão/mensagem/canal no controle; ao fim natural ou `/parar`, libera áudio e mantém voz por até 10 minutos |
| `DiscordGuildCommandExecutor` | Serializar mutações por guild com fila limitada | Comandos, botões, timers e callbacks | Uma sequência ordenada por guild e paralelismo entre guilds | Não executa rede/FFmpeg sob um lock compartilhado; remove lanes inativas e rejeita sobrecarga explicitamente |
| `DiscordPlaybackMessageCoordinator` | Coalescer edições da mensagem pública | Apresentações versionadas da sessão | No máximo uma edição em voo e entrega do estado mais recente | Uma conclusão atrasada não republica estado antigo |
| `DiscordFfmpegAudioSource` | Converter mídia self-hosted em PCM de 20 ms e preservar posição durante pausa | Processo FFmpeg sem shell | Frames PCM para `AudioSendHandler` | Na pausa deixa de ler e entregar frames; não registra URL e libera stream/processo no término, erro ou saída |
| JDA 6.7.0 | Adaptar Gateway, eventos e operações da API Discord | Token, listeners e chamadas assíncronas | Eventos JDA e ações remotas | Dependência declarada em `backend/pom.xml`; não substitui as regras de aplicação do Deefy |

A configuração é ligada por:

- `DEEFY_DISCORD_ENABLED`;
- `DEEFY_DISCORD_TOKEN`;
- `DEEFY_DISCORD_TOKEN_FILE` (preferido para Docker secret);
- `DEEFY_DISCORD_TEST_MODE` (opcional, `false` por padrão);
- `DEEFY_DISCORD_TEST_GUILD_ID`;
- `DEEFY_API_URL` (origin self-hosted permitido para áudio).

Os nomes são mapeados em `backend/src/main/resources/application.properties`.
Quando não informada, a ativação é `false`. O modelo futuro
`docker-compose.discord-bot.yml` usa arquivo protegido para o token, não publica
portas e exige a origem self-hosted. A guild só é exigida quando
`DEEFY_DISCORD_TEST_MODE=true`.

A cobertura Discord inclui:

- `DiscordBotPropertiesTest`: configuração completa, campos ausentes e guild
  inválida;
- `DiscordBotLifecycleTest`: integração desativada, configuração incompleta e
  transição para `running` após o evento de pronto;
- `DiscordCommandListenerTest`: resposta ao ping, modo global, descarte de guild
  no modo de teste, autocomplete, mensagem pública, botões e roteamento dos
  comandos de voz/reprodução;
- `DiscordCommandRegistrarTest`: registro global individual ou registro
  restrito quando o modo de teste está explicitamente habilitado;
- `DiscordVoiceConnectionServiceTest`: canal e permissão, idempotência,
  concorrência por guild, isolamento entre guilds e saída controlada;
- testes de catálogo/origem, sessões por guild, fonte PCM/FFmpeg, executor por
  guild, coalescência de mensagem e corridas de controle, todos com mocks ou
  fakes e sem Discord ou mídia reais.

## 3. Diagrama textual

```text
                         estado atual

Discord (qualquer guild autorizada pela instalação do bot)
        | eventos e interactions
        v
JDA client -- ciclo de vida --> DiscordBotLifecycle
        |
        +--> DiscordCommandRegistrar -- registra --> /deefy ping, /entrar, /sair, /tocar
        |                                          /pausar, /retomar, /parar, /pular
        |                                          /voltar, /reiniciar, /fila
        |
        +--> DiscordCommandListener  -- responde --> pong
                         |
                         +--> DiscordVoiceConnectionService --> conexão de voz por guild
                         |
                         +--> DiscordPlaybackService
                                   +--> DiscordTrackCatalog --> catálogo self-hosted
                                   +--> executor por guild --> sessão/estado/timers
                                   +--> sessão por guild --> FFmpeg PCM --> JDA
                                   +--> mensagem pública versionada + botões

                    evolução incremental planejada

Discord / JDA
        |
        v
[camada de comandos Discord]
        |
        +--> [validação e permissões]
        |
        v
[serviços de aplicação do Deefy] <------ cliente web / API HTTP
        |
        +--> domínio, dados e mídia ------> Supabase por adaptadores do backend
        |
        +--> [presença]
        +--> [voz] --> [reprodução] <---- [porta conceitual da fila, Taiga #79]
                              ^
                              +----------- [controles]
```

Os blocos entre colchetes são pontos de extensão, não classes existentes. O
sentido das dependências preserva as regras no backend: Discord e cliente web
são adaptadores de entrada, e o acesso a dados/mídia ocorre por adaptadores já
controlados pela aplicação.

## 4. Ciclo de vida do bot

1. O Spring instancia `DiscordBotProperties` e associa as propriedades com
   prefixo `deefy.discord`.
2. Como `SmartLifecycle`, `DiscordBotLifecycle` recebe o início do contexto.
3. Se `enabled` for `false`, registra que a integração está desativada e não
   cria o JDA.
4. Se ativada, valida o token. A guild só é obrigatória e validada quando o modo
   de teste explícito está ativo; no modo normal sua ausência é válida.
5. Com configuração válida, o processo exclusivo do bot valida JDave/native e
   configura `AudioModuleConfig` com um `DaveSessionFactory` explícito. Só então
   `JDABuilder.createDefault` cria o cliente e recebe como listeners
   `DiscordCommandListener`, `DiscordCommandRegistrar` e o próprio lifecycle.
6. A construção solicita a inicialização de forma assíncrona. O estado só muda
   para `running` quando `DiscordBotLifecycle` recebe `ReadyEvent`.
7. No mesmo tipo de evento, `DiscordCommandRegistrar` faz `upsert` individual
   dos onze comandos globais ou, no modo de teste, na guild configurada.
8. Na parada do contexto, o lifecycle zera suas referências e chama
   `shutdownNow()` no cliente existente. Na variante com callback, o callback é
   executado mesmo após a tentativa de shutdown.

O comportamento seguro atual é:

- bot desativado: backend segue ativo sem cliente Discord;
- token ausente: inicialização do JDA não é tentada;
- guild ausente no modo normal: não impede a inicialização;
- guild ausente ou inválida no modo de teste: inicialização não é tentada;
- guild de teste válida, mas não encontrada após a conexão: o JDA pode estar
  pronto, porém os comandos restritos não são registrados;
- falha síncrona ao construir o JDA: referência e estado são limpos, o erro é
  registrado e o backend permanece em execução;
- encerramento da aplicação: o cliente existente recebe shutdown imediato.

Não há política explícita de reconexão, backoff ou tratamento dos eventos de
desconexão no código do projeto. Eventual comportamento interno do JDA não deve
ser confundido com uma política implementada e testada pelo Deefy.

## 5. Comandos e eventos

O fluxo atual de `/deefy ping` é:

1. após `ReadyEvent`, o registrar escolhe o escopo global ou a guild de teste;
2. solicita `upsert` individual dos comandos, sem limpar comandos não
   relacionados da aplicação;
3. o listener recebe uma `SlashCommandInteractionEvent`;
4. descarta eventos fora de guild e aplica a restrição apenas no modo de teste;
5. para a combinação suportada, responde `pong` por uma ação assíncrona;
6. interações de outro contexto são ignoradas, sem resposta de erro.

Em `/tocar`, o campo `musica` oferece autocomplete do catálogo self-hosted e
aceita título, `título - artista` ou ID como alternativa técnica. O resolvedor
normaliza caixa, acentos e espaços e prioriza título + artista exatos, título
exato e só então correspondências parciais. Hífens internos permanecem parte do
título quando essa for a correspondência correta. Resultados ambíguos retornam
opções para refinamento e nunca iniciam uma faixa arbitrária.

As respostas normais são públicas no canal de texto, com menções desabilitadas.
`/tocar` cria uma única mensagem com título, créditos, solicitante e canal de
voz e atualiza esse mesmo registro entre preparação, reprodução, conclusão ou
falha. Detalhes de FFmpeg, transporte e codecs permanecem somente nos logs
sanitizados. No fim natural, a fonte e o processo de áudio são liberados, mas a
conexão fica ociosa por até 10 minutos. Uma reprodução válida no mesmo canal
cancela o prazo anterior; `/sair`, falha terminal, desconexão externa e shutdown
encerram imediatamente os recursos da guild correspondente.

Na mensagem de reprodução, os botões `Pausar/Retomar` e `Parar` chamam o mesmo
serviço usado por `/pausar`, `/retomar` e `/parar`. A autorização é refeita no
momento da ação: o membro precisa continuar no mesmo canal de voz, e botões
também precisam corresponder à guild, à mensagem e ao identificador da sessão
ativa. O canal conectado é relido do `AudioManager`; se o bot já não estiver no
canal associado à sessão, o controle expira. Controles de sessões anteriores ou
de mensagens antigas expiram sem criar uma sessão nova. Sucessos atualizam a mensagem pública existente;
rejeições individuais de botão podem ser efêmeras para não poluir o canal.

### 5.1 Estados e transições do player

| Estado | Entradas válidas | Próximo estado | Efeito principal |
|---|---|---|---|
| `IDLE` | `/entrar` ou `/tocar` válido | `IDLE` ou `STARTING` | Mantém somente conexão ociosa, com prazo de 10 minutos |
| `STARTING` | primeiro frame, falha, timeout, `/parar` ou `/sair` | `PLAYING`, `STOPPING` | Fonte é preparada fora da lane; a conclusão revalida a sessão |
| `PLAYING` | pausar, parar, pular, voltar, reiniciar, fim/erro ou sair | `PAUSED`, `STARTING`, `STOPPING` | Frames são entregues; uma nova faixa é enfileirada e só uma fonte toca por vez |
| `PAUSED` | retomar, parar, pular, voltar, reiniciar, timeout de 10 minutos ou sair | `PLAYING`, `STARTING`, `STOPPING` | FFmpeg/stream preservam a posição; controles de faixa passam pela mesma lane |
| `STOPPING` | callbacks/timers atrasados | `STOPPING` | Cleanup idempotente; nenhuma nova mutação da sessão é aceita |

`/parar` libera a faixa e inicia o período ocioso da conexão. `/sair` fecha a
voz imediatamente. Cada pausa recebe uma geração própria, de modo que um timer
de uma pausa anterior não encerre uma pausa posterior da mesma sessão.

### 5.2 Ordenação e concorrência

Todas as mutações de uma guild passam por uma lane limitada do
`DiscordGuildCommandExecutor`: slash commands, botões, timers, término/erro da
fonte e callbacks de voz. Guilds distintas usam lanes independentes. Operações
lentas de resolução do catálogo e abertura da fonte são feitas fora da lane;
ao concluir, revalidam a identidade da sessão antes de anexar recursos.

Sessões possuem UUID, a mensagem de controle fica vinculada à sessão e timers
carregam a identidade do proprietário ou uma geração. Interaction IDs recentes
são deduplicados dentro de um limite fixo. A fila interna de mutações também é
limitada; saturação retorna erro claro em vez de crescer sem limite. Lanes,
timers e metadados são liberados quando a guild deixa de ter sessão ou conexão
ociosa.

Edições da mensagem são versionadas e coalescidas: existe no máximo uma edição
em voo e, ao terminar, somente o estado mais recente pendente é enviado. Assim,
uma atualização lenta não sobrescreve uma pausa, retomada, parada ou falha mais
nova. Estados terminais removem os botões.

Para comandos futuros, o registro permanece na borda JDA, mas a execução deve
ser incrementalmente separada por caso de uso:

- o listener converte o evento em entrada simples e roteia pelo nome do comando;
- parsing e validação de formato acontecem antes da chamada de aplicação;
- autorização de guild, canal e usuário fica em uma camada explícita;
- handlers pequenos chamam serviços de aplicação e não acessam repositórios,
  Supabase ou detalhes do player diretamente;
- o retorno da aplicação é convertido em resposta Discord na borda;
- novos handlers são testados com eventos/portas simulados, sem conexão real.

Essa separação evita um listener monolítico. Não exige, nesta etapa, um
framework adicional nem define nomes de classes futuras.

## 6. Arquitetura alvo incremental

Os nomes abaixo representam papéis conceituais. A divisão concreta deve ser
introduzida apenas quando cada incremento justificar a extração.

| Papel futuro | Responsabilidade | Dependências permitidas | Dependências proibidas | Teste esperado | Taiga |
|---|---|---|---|---|---|
| Gateway/client Discord | Encapsular inicialização, eventos e operações JDA | JDA, configuração e observabilidade | Repositórios e regras de negócio | Ciclo de vida com cliente simulado | #74, #75 |
| Camada de comandos | Rotear interações para casos de uso pequenos | DTOs simples, validação e serviços de aplicação | SQL, Storage e estado interno do player | Handler por comando sem Discord real | #75, #82 |
| Validação/permissões | Autorizar guild, canal, usuário e ação | Contexto normalizado e política configurada | Token do bot e persistência da fila | Matrizes de permitido/negado | #81 |
| Camada de voz | Entrar, sair e manter sessão de voz | Gateway Discord e estado mínimo da sessão | Ordenação da fila e regras de catálogo | Adaptadores JDA simulados | #76 |
| Reprodução/player | Controlar ciclo da faixa e emitir eventos de término/erro | Fonte de áudio, voz e porta da fila | Estrutura interna ou ordenação da fila | Fonte e fila falsas | #77 |
| Controles | Traduzir pausar, retomar e parar em intenções; reservar extensão para avanço quando a fila existir | Serviço de reprodução e permissões | Mutação direta da fila/player | Estado, concorrência e autorização simulados | #78 |
| Porta da fila | Fornecer ao player o estado necessário sem revelar implementação | Contrato definido com o responsável pela fila | JDA e detalhes de áudio | Fake contratual | #79 |
| Presença | Projetar estado relevante em atividade/status do bot | Estado sanitizado da aplicação/player | Credenciais e acesso direto a dados | Atualizações deduplicadas simuladas | #80 |
| Integração com backend | Reusar casos de uso do Deefy | Serviços de aplicação e modelos de entrada/saída | Dependência de Discord no domínio | Testes de serviço e adaptadores | #77–#82 |
| Observabilidade | Registrar transições e falhas sem dados sensíveis | Identificadores técnicos mínimos e métricas | Token, conteúdo privado e payload integral | Verificação de logs sanitizados | #82 |
| Configuração | Validar ativação, guild, intents e limites | Propriedades Spring e ambiente | Valores secretos em código | Configurações válida/inválida | #74, #82 |

## 7. Contrato da fila — Taiga #79

As referências locais à #79 indicam que a fila e o histórico são responsabilidade
separada da reprodução. Nesta entrega, a integração Discord usa um contrato
interno mínimo e volátil, sem consultar diretamente `ListeningHistoryService`,
`historico_execucao`, banco ou Storage. Nenhuma fonte externa da tarefa Taiga foi
consultada nesta rodada.

O contrato local fornece:

- faixa atual e pendentes FIFO por guild;
- posições públicas iniciadas em 1, somente para pendentes;
- remoção, movimentação, limpeza e embaralhamento apenas das pendentes;
- histórico limitado para `/voltar`, consumido ao retroceder;
- avanço único em fim natural, falha ou `/pular`;
- snapshot sanitizado para a mensagem e `/fila ver`.

Os limites padrão são 50 pendentes e 50 entradas de histórico, configuráveis por
`DEEFY_DISCORD_PLAYBACK_QUEUE_MAX_SIZE` e
`DEEFY_DISCORD_PLAYBACK_HISTORY_MAX_SIZE`. A fila não é persistida, não repete
faixas automaticamente, não pré-baixa a fila inteira e não implementa playlists
externas. Falhas de uma faixa avançam para a próxima; quando todas falham, a
guild entra no estado ocioso sem loop de tentativas.

`/pular`, `/voltar` e `/reiniciar` não criam uma segunda fila: são intenções
serializadas pelo mesmo `DiscordPlaybackSessionService`. `/voltar` pode iniciar
a última entrada do histórico enquanto a voz está ociosa; `/reiniciar` mantém a
faixa atual, fila e histórico, criando apenas uma nova identidade de sessão e
fonte para começar do primeiro frame.

## 8. Voz e reprodução

| Tarefa | Pré-requisitos | Responsabilidade | Limite de escopo | Aceite resumido | Testes principais |
|---|---|---|---|---|---|
| #76 — voz | JDA pronto, guild/canal autorizados e intents revisados | Entrada, saída e estado da conexão de voz | Não reproduz nem ordena faixas | Conecta e desconecta no canal permitido com erro controlado | Canal ausente, permissão negada, conexão e shutdown simulados |
| #77 — reprodução | Sessão de voz e fonte de áudio definida | Iniciar, terminar e sinalizar erro de uma faixa | Não implementa fila nem controles do player | Player entrega áudio e encerra estado de forma determinística | Fonte válida/inválida, término, erro e limpeza com fakes |
| #78 — controles | Player e autorização disponíveis | Pausar, retomar e parar por botão/slash | Não implementa fila paralela | Cada controle produz uma intenção autorizada, serializada e observável | Corridas, timers antigos, permissão, idempotência e isolamento por guild |
| #79 — fila | Player e controles locais disponíveis | Fila FIFO, histórico limitado, pular, voltar, reiniciar e organização de pendentes | Não persiste fila nem conhece detalhes de Storage/Discord fora da borda | Uma fila por guild, sem perda/duplicação em concorrência e sem duas fontes simultâneas | Ordem, avanço, falhas, limites, histórico, autorização e isolamento |
| #80 — presença | Estado confiável do bot/player | Atualizar atividade/status com informação sanitizada | Não dirige reprodução | Presença reflete transições relevantes sem excesso de chamadas | Deduplicação, indisponibilidade e ausência de faixa |
| #81 — permissões | Contexto normalizado de guild/canal/usuário | Autorizar comandos e ações de voz | Não contém regra de reprodução | Ações negadas por padrão fora do escopo autorizado | Matrizes de papéis, guilds, canais e respostas seguras |
| #82 — testes/documentação | Contratos estabilizados nas tarefas anteriores | Cobertura integrada, guias e operação segura | Não adiciona funcionalidade incidental | Suíte reproduzível e documentação coerente com o código | Lifecycle, comandos, voz, player, fila fake, permissões e shutdown |

## 9. Segurança e configuração

| Variável | Finalidade | Tratamento esperado |
|---|---|---|
| `DEEFY_DISCORD_ENABLED` | Habilitar explicitamente a integração | Permanecer `false` por padrão |
| `DEEFY_DISCORD_TOKEN` | Autenticar o cliente bot no Discord | Segredo externo ao Git, nunca registrado ou exibido |
| `DEEFY_DISCORD_TOKEN_FILE` | Ler o token por arquivo protegido | Preferido no serviço Docker; `/run/secrets/deefy_discord_token` |
| `DEEFY_DISCORD_TEST_MODE` | Ativar restrição temporária para teste controlado | `false` por padrão; não é necessário no uso normal |
| `DEEFY_DISCORD_TEST_GUILD_ID` | Guild permitida quando o modo de teste está ativo | Obrigatória somente com `DEEFY_DISCORD_TEST_MODE=true` |
| `DEEFY_API_URL` | Definir o origin self-hosted aceito para as mídias | URL sem credenciais; outro origin, URL assinada e Supabase antigo são recusados |

Regras de segurança:

- o token não entra no Git, logs, exceções reproduzidas, mensagens ou capturas;
- o `.env` real permanece ignorado; templates contêm somente nomes e
  placeholders sem valor real;
- a restrição por guild é opcional, explicitamente ativada e desabilitada por
  padrão; o modo normal atende todas as guilds em que o bot foi autorizado;
- intents futuros devem ser mínimos, declarados explicitamente e revisados antes
  da ativação; hoje não há configuração explícita de intents no projeto;
- permissões do bot e OAuth seguem menor privilégio por comando, canal e guild;
- listeners não recebem acesso direto a credenciais de banco ou Supabase;
- testes unitários não usam token nem executam ações reais no Discord;
- logs registram estado técnico suficiente, sem token ou payload privado.

As variáveis são consumidas por `application.properties`; o template futuro do
serviço contém somente nomes e valores não sensíveis.

## 10. Estratégia de testes

O conjunto atual cobre propriedades, lifecycle, registro/roteamento, catálogo,
voz, reprodução, controles, timers, coalescência de mensagens e corridas
determinísticas por guild. A evolução mantém a seguinte pirâmide:

1. **Unidade:** propriedades, roteamento, parsing, autorização e handlers com
   Mockito ou fakes, sem rede.
2. **Contrato:** player contra fake da porta da fila e adaptadores de voz contra
   interfaces simuladas, sem exigir a implementação da #79.
3. **Componente:** lifecycle com fábrica/cliente controlável, incluindo falha de
   início, evento pronto, desconexão e shutdown.
4. **Integração controlada:** o modo de teste restringe registro e execução a
   uma guild, com credenciais fora do repositório e execução opt-in.
5. **Cenários de voz:** preferir simulação de canal, fonte e envio de áudio;
   testes reais ficam isolados, controlados e não fazem parte da suíte comum.

Os controles usam scheduler capturável, barreiras e latches em vez de espera
real. Os casos cobrem ações simultâneas, interação repetida, timer/callback
antigo, mensagem fora de ordem, canal divergente, isolamento entre guilds,
cleanup único e preservação dos frames durante pausa. Ausência de próximo item
só poderá ser testada como contrato quando a #79 definir sua porta. Testes e
logs também devem verificar que tokens não aparecem em mensagens de erro.

Nenhum teste unitário deve conectar ao Discord real.

## 11. Backlog e dependências

| Tarefa Taiga | Estado atual | Dependências | Entrega esperada | Limite de escopo |
|---|---|---|---|---|
| #56 | Entregue por este documento | Código atual das tarefas iniciais | Arquitetura, contratos e limites registrados | Sem implementação funcional |
| #74 | Parcialmente entregue | Configuração Spring e ambiente | Configuração operacional completa, segura e documentada | Sem comandos, voz ou reprodução |
| #75 | Parcialmente entregue | #74 e JDA | Ciclo de conexão e comando básico testável | Sem player ou fila |
| #76 | Implementada | #74, #75 e intents padrão do JDA | Entrada e saída de canal de voz comum por guild | Sem reprodução, fila ou Stage Channel |
| #77 | Implementada | #76 e catálogo self-hosted | Uma faixa por guild via FFmpeg/JDA, com cleanup explícito | Sem fila |
| #78 | Implementada localmente, ainda sem commit/deploy | #77 e autorização por canal | Pausar, retomar e parar por botão/slash, com concorrência serializada | Não adiciona fila paralela |
| #79 | Implementada localmente nesta branch, ainda sem commit/deploy | #77/#78 e catálogo existente | Fila FIFO em memória, histórico limitado e controles de avanço | Sem persistência, repetição, playlist externa ou reprodução progressiva |
| #80 | Não implementada | Estado de #75/#77 | Presença coerente e sanitizada | Sem regras de player |
| #81 | Não implementada | Contexto de comandos e voz | Política explícita de permissões | Sem credenciais ou regra de fila |
| #82 | Não implementada | Incrementos anteriores estabilizados | Testes adicionais e documentação operacional | Sem funcionalidade nova incidental |

## 12. Decisões e lacunas

- O código permanece no artefato Spring, mas há um Compose futuro para executar
  o bot em processo/container separado, sem portas publicadas e sem modificar o
  backend web existente.
- O runtime de voz do bot usa Java 25 e JDave 0.1.8 em perfil Maven e Dockerfile
  exclusivos. O backend web permanece em Java 21 e não carrega JDave quando a
  integração Discord está desativada.
- A imagem fixa os estágios Java 25 por digest e incorpora o native DAVE em
  `/app/native/libdave.so`; o carregamento não depende de executar arquivos no
  `/tmp`, que continua montado com `noexec`.
- A inicialização do bot valida o native DAVE antes de abrir o cliente JDA e não
  aceita fallback silencioso para `PassthroughDaveSessionFactory`.
- Transições de voz, lifecycle do FFmpeg, exit code, bytes PCM e frames entregues
  são registrados somente com IDs técnicos; URL de mídia e valores sensíveis são
  removidos do stderr limitado.
- A borda Discord deve chamar serviços de aplicação. Ela não deve acessar
  Supabase, repositórios ou estruturas internas de reprodução diretamente.
- A reconexão não está explicitamente implementada nem testada.
- Intents não estão explicitamente definidos no código atual.
- Presença e política ampla de permissões não estão implementadas nesta linha.
  A fila é volátil e local ao serviço; reinício do bot perde pendentes e
  histórico, mas não altera catálogo, banco ou Storage. Volume e playlists
  externas continuam ausentes.
- No modo de teste, a guild configurada pode não ser encontrada depois do JDA
  ficar pronto; esse caso impede o registro dos comandos restritos, mas não muda
  o estado `running` do lifecycle.
- Falhas assíncronas de resposta e registro são tratadas de forma limitada; o
  registrar possui callback de erro, enquanto a resposta `pong` não possui.
- Os templates `.env.example` e `.env.production.example` estão incompletos para
  a configuração Discord.
- Não há documentação operacional completa.
- Números `#56` e `#74` a `#82` são tarefas Taiga. Um PR futuro deve usar a
  descrição explícita da tarefa e não usar palavras-chave de encerramento
  automático sem uma GitHub Issue correspondente confirmada.
- Fontes gerados preexistentes em `target/generated-sources/annotations` são
  dívida técnica fora do escopo; esta tarefa não os adiciona, remove ou altera.
