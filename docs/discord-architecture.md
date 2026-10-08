# Arquitetura da integração com o Discord

Este documento registra o estado atual e a evolução incremental planejada da
integração entre o Deefy e o Discord. As referências `#56` e `#74` a `#82`
neste texto identificam tarefas do Taiga acadêmico, não GitHub Issues.

## 1. Objetivo e escopo

A integração tem como objetivo oferecer recursos do Deefy dentro de uma guild
do Discord, inicialmente por comandos de interação e, em incrementos futuros,
por presença, voz e reprodução. O incremento disponível no código conecta um
cliente JDA ao processo do backend Spring, restringe a operação a uma guild de
teste e responde ao comando `/deefy ping`.

Esta arquitetura cobre:

- os componentes Discord existentes e seus limites;
- o ciclo de vida da conexão JDA;
- a convenção para comandos e eventos futuros;
- os pontos de extensão para permissões, voz, reprodução, fila e presença;
- a fronteira entre a integração Discord e os serviços de aplicação do Deefy;
- segurança, configuração, observabilidade e estratégia de testes.

Ficam fora do escopo desta tarefa a implementação de novos comandos, voz,
reprodução, controles, presença, permissões e fila, bem como alterações de
infraestrutura, banco, Supabase, cliente web ou configuração operacional.

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
Não existe hoje uma camada de aplicação específica para comandos Discord, uma
abstração de fila ou um componente de voz.

| Componente | Responsabilidade | Entradas | Saídas e efeitos | Dependências e limites |
|---|---|---|---|---|
| `DiscordBotProperties` | Mapear e validar a configuração `deefy.discord` | Propriedades Spring derivadas do ambiente | Estado de configuração e lista de erros de validação | Não inicia conexões nem conhece comandos. Valida token não vazio e guild como inteiro sem sinal |
| `DiscordBotLifecycle` | Integrar o cliente JDA ao ciclo de vida Spring | Propriedades, listener, registrar e `ReadyEvent` | Criação do JDA, estado `running` e shutdown | Usa `JDABuilder`; não registra comandos nem trata regras de negócio |
| `DiscordCommandRegistrar` | Registrar o comando disponível na guild de teste | `ReadyEvent` e guild configurada | `upsertCommand` assíncrono para `/deefy ping` e logs do resultado | Não executa comandos. Se a guild não for encontrada, registra erro e encerra o processamento do evento |
| `DiscordCommandListener` | Filtrar e responder à interação suportada | `SlashCommandInteractionEvent` | Resposta assíncrona `pong` | Aceita apenas evento de guild, guild configurada, comando `deefy` e subcomando `ping`; demais contextos são ignorados |
| JDA 6.7.0 | Adaptar Gateway, eventos e operações da API Discord | Token, listeners e chamadas assíncronas | Eventos JDA e ações remotas | Dependência declarada em `backend/pom.xml`; não substitui as regras de aplicação do Deefy |

A configuração é ligada por:

- `DEEFY_DISCORD_ENABLED`;
- `DEEFY_DISCORD_TOKEN`;
- `DEEFY_DISCORD_TEST_GUILD_ID`.

Os nomes são mapeados em `backend/src/main/resources/application.properties` e
repassados pelo `docker-compose.local.yml`. Quando não informada, a ativação é
`false` e os dois valores textuais são vazios.

Há oito testes unitários distribuídos entre:

- `DiscordBotPropertiesTest`: configuração completa, campos ausentes e guild
  inválida;
- `DiscordBotLifecycleTest`: integração desativada, configuração incompleta e
  transição para `running` após o evento de pronto;
- `DiscordCommandListenerTest`: resposta ao comando válido e descarte de guild
  não autorizada.

Não há teste específico do registro assíncrono de comandos.

## 3. Diagrama textual

```text
                         estado atual

Discord (guild de teste)
        | eventos e interactions
        v
JDA client -- ciclo de vida --> DiscordBotLifecycle
        |
        +--> DiscordCommandRegistrar -- registra --> /deefy ping
        |
        +--> DiscordCommandListener  -- responde --> pong

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
4. Se ativada, valida token e guild. Configuração ausente ou guild que não seja
   um inteiro sem sinal impede o início sem encerrar o backend.
5. Com configuração válida, `JDABuilder.createDefault` cria o cliente e recebe
   como listeners `DiscordCommandListener`, `DiscordCommandRegistrar` e o
   próprio lifecycle.
6. A construção solicita a inicialização de forma assíncrona. O estado só muda
   para `running` quando `DiscordBotLifecycle` recebe `ReadyEvent`.
7. No mesmo tipo de evento, `DiscordCommandRegistrar` procura a guild de teste
   e solicita o registro de `/deefy ping`.
8. Na parada do contexto, o lifecycle zera suas referências e chama
   `shutdownNow()` no cliente existente. Na variante com callback, o callback é
   executado mesmo após a tentativa de shutdown.

O comportamento seguro atual é:

- bot desativado: backend segue ativo sem cliente Discord;
- token ausente: inicialização do JDA não é tentada;
- guild ausente ou inválida: inicialização não é tentada;
- guild válida na configuração, mas não encontrada após a conexão: o JDA pode
  estar pronto, porém o comando não é registrado;
- falha síncrona ao construir o JDA: referência e estado são limpos, o erro é
  registrado e o backend permanece em execução;
- encerramento da aplicação: o cliente existente recebe shutdown imediato.

Não há política explícita de reconexão, backoff ou tratamento dos eventos de
desconexão no código do projeto. Eventual comportamento interno do JDA não deve
ser confundido com uma política implementada e testada pelo Deefy.

## 5. Comandos e eventos

O fluxo atual de `/deefy ping` é:

1. após `ReadyEvent`, o registrar localiza a guild configurada;
2. solicita `upsert` do comando raiz `deefy` com o subcomando `ping`;
3. o listener recebe uma `SlashCommandInteractionEvent`;
4. descarta eventos fora de guild e valida guild, comando e subcomando;
5. para a combinação suportada, responde `pong` por uma ação assíncrona;
6. interações de outro contexto são ignoradas, sem resposta de erro.

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
| Controles | Traduzir pausar, retomar, pular e volume em intenções | Serviço de reprodução e permissões | Mutação direta da fila/player | Estado e autorização simulados | #78 |
| Porta da fila | Fornecer ao player o estado necessário sem revelar implementação | Contrato definido com o responsável pela fila | JDA e detalhes de áudio | Fake contratual | #79 |
| Presença | Projetar estado relevante em atividade/status do bot | Estado sanitizado da aplicação/player | Credenciais e acesso direto a dados | Atualizações deduplicadas simuladas | #80 |
| Integração com backend | Reusar casos de uso do Deefy | Serviços de aplicação e modelos de entrada/saída | Dependência de Discord no domínio | Testes de serviço e adaptadores | #77–#82 |
| Observabilidade | Registrar transições e falhas sem dados sensíveis | Identificadores técnicos mínimos e métricas | Token, conteúdo privado e payload integral | Verificação de logs sanitizados | #82 |
| Configuração | Validar ativação, guild, intents e limites | Propriedades Spring e ambiente | Valores secretos em código | Configurações válida/inválida | #74, #82 |

## 7. Contrato da fila — Taiga #79

A fila pertence à tarefa Taiga #79 e não é definida nem implementada por este
documento. A camada de reprodução deve depender apenas de uma porta conceitual
capaz de:

- consultar a faixa atual;
- consultar se há próxima faixa disponível;
- solicitar o próximo item para reprodução;
- notificar término ou erro da faixa atual;
- limpar o estado de reprodução quando a sessão terminar;
- consultar um retrato do estado necessário ao player sem expor a estrutura.

Essas capacidades não são assinaturas Java. Tipos, concorrência, persistência,
repetição, remoção, prioridade e a semântica exata de avanço pertencem à #79 e
devem ser acordados com seu responsável.

As regras de integração são:

- a reprodução **consome** a porta; a fila é dona da ordenação;
- controles enviam intenções ao serviço de reprodução e não manipulam a
  estrutura interna da fila;
- o player notifica fatos de reprodução, mas não decide como a fila os
  transforma em ordenação ou repetição;
- mocks ou fakes da porta podem viabilizar testes futuros do player sem antecipar
  a implementação real.

## 8. Voz e reprodução

| Tarefa | Pré-requisitos | Responsabilidade | Limite de escopo | Aceite resumido | Testes principais |
|---|---|---|---|---|---|
| #76 — voz | JDA pronto, guild/canal autorizados e intents revisados | Entrada, saída e estado da conexão de voz | Não reproduz nem ordena faixas | Conecta e desconecta no canal permitido com erro controlado | Canal ausente, permissão negada, conexão e shutdown simulados |
| #77 — reprodução | Sessão de voz e fonte de áudio definida | Iniciar, terminar e sinalizar erro de uma faixa | Não implementa fila nem comandos | Player entrega áudio e encerra estado de forma determinística | Fonte válida/inválida, término, erro e limpeza com fakes |
| #78 — controles | Player e autorização disponíveis | Pausar, retomar, pular e ajustar volume | Não altera a estrutura interna da fila | Cada controle produz uma intenção autorizada e observável | Estado inválido, limites, permissão e idempotência |
| #79 — fila | Contrato alinhado com o player | Possuir coleção, ordenação e semântica de avanço | Não conhece JDA nem detalhes de áudio | Contrato aceito pelo player e sem vazamento de implementação | Ordem, concorrência e semânticas definidas na própria tarefa |
| #80 — presença | Estado confiável do bot/player | Atualizar atividade/status com informação sanitizada | Não dirige reprodução | Presença reflete transições relevantes sem excesso de chamadas | Deduplicação, indisponibilidade e ausência de faixa |
| #81 — permissões | Contexto normalizado de guild/canal/usuário | Autorizar comandos e ações de voz | Não contém regra de reprodução | Ações negadas por padrão fora do escopo autorizado | Matrizes de papéis, guilds, canais e respostas seguras |
| #82 — testes/documentação | Contratos estabilizados nas tarefas anteriores | Cobertura integrada, guias e operação segura | Não adiciona funcionalidade incidental | Suíte reproduzível e documentação coerente com o código | Lifecycle, comandos, voz, player, fila fake, permissões e shutdown |

## 9. Segurança e configuração

| Variável | Finalidade | Tratamento esperado |
|---|---|---|
| `DEEFY_DISCORD_ENABLED` | Habilitar explicitamente a integração | Permanecer `false` por padrão |
| `DEEFY_DISCORD_TOKEN` | Autenticar o cliente bot no Discord | Segredo externo ao Git, nunca registrado ou exibido |
| `DEEFY_DISCORD_TEST_GUILD_ID` | Restringir registro e execução à guild controlada | Valor validado e diferente de uma permissão global |

Regras de segurança:

- o token não entra no Git, logs, exceções reproduzidas, mensagens ou capturas;
- o `.env` real permanece ignorado; templates contêm somente nomes e
  placeholders sem valor real;
- a guild de teste é obrigatória enquanto a validação estiver controlada;
- intents futuros devem ser mínimos, declarados explicitamente e revisados antes
  da ativação; hoje não há configuração explícita de intents no projeto;
- permissões do bot e OAuth seguem menor privilégio por comando, canal e guild;
- listeners não recebem acesso direto a credenciais de banco ou Supabase;
- testes unitários não usam token nem executam ações reais no Discord;
- logs registram estado técnico suficiente, sem token ou payload privado.

As três variáveis já são consumidas por `application.properties` e Compose, mas
não constam em `.env.example` nem `.env.production.example`. Completar esses
templates é pendência das tarefas #74/#82 e não será feito na #56.

## 10. Estratégia de testes

O conjunto atual cobre validação básica de propriedades, estados iniciais do
lifecycle, transição por `ReadyEvent` e filtragem/resposta do listener. A
evolução deve adotar a seguinte pirâmide:

1. **Unidade:** propriedades, roteamento, parsing, autorização e handlers com
   Mockito ou fakes, sem rede.
2. **Contrato:** player contra fake da porta da fila e adaptadores de voz contra
   interfaces simuladas, sem exigir a implementação da #79.
3. **Componente:** lifecycle com fábrica/cliente controlável, incluindo falha de
   início, evento pronto, desconexão e shutdown.
4. **Integração controlada:** registro e conexão somente em guild de teste, com
   credenciais fornecidas fora do repositório e execução explicitamente opt-in.
5. **Cenários de voz:** preferir simulação de canal, fonte e envio de áudio;
   testes reais ficam isolados, controlados e não fazem parte da suíte comum.

Devem existir casos de permissão permitida/negada, comandos desconhecidos,
estado inválido do player, término/erro de faixa, ausência de próximo item e
shutdown durante operação. Testes e logs também devem verificar que tokens não
aparecem em mensagens de erro.

Nenhum teste unitário deve conectar ao Discord real.

## 11. Backlog e dependências

| Tarefa Taiga | Estado atual | Dependências | Entrega esperada | Limite de escopo |
|---|---|---|---|---|
| #56 | Entregue por este documento | Código atual das tarefas iniciais | Arquitetura, contratos e limites registrados | Sem implementação funcional |
| #74 | Parcialmente entregue | Configuração Spring e ambiente | Configuração operacional completa, segura e documentada | Sem comandos, voz ou reprodução |
| #75 | Parcialmente entregue | #74 e JDA | Ciclo de conexão e comando básico testável | Sem player ou fila |
| #76 | Não implementada | #74, #75 e revisão de intents/permissões | Entrada e saída de canal de voz | Sem reprodução |
| #77 | Não implementada | #76 e contrato compatível com #79 | Serviço de reprodução desacoplado | Sem possuir a fila |
| #78 | Não implementada | #77 e #81 | Controles de reprodução autorizados | Sem mutação direta da fila |
| #79 | Não implementada; responsabilidade de outro colega | Contrato alinhado com #77/#78 | Fila e suas semânticas | Sem JDA ou reprodução de áudio |
| #80 | Não implementada | Estado de #75/#77 | Presença coerente e sanitizada | Sem regras de player |
| #81 | Não implementada | Contexto de comandos e voz | Política explícita de permissões | Sem credenciais ou regra de fila |
| #82 | Não implementada | Incrementos anteriores estabilizados | Testes adicionais e documentação operacional | Sem funcionalidade nova incidental |

## 12. Decisões e lacunas

- A integração permanece embutida no backend Spring; não há justificativa atual
  para separá-la em outro processo.
- A borda Discord deve chamar serviços de aplicação. Ela não deve acessar
  Supabase, repositórios ou estruturas internas de reprodução diretamente.
- A reconexão não está explicitamente implementada nem testada.
- Intents não estão explicitamente definidos no código atual.
- Presença, voz, reprodução, controles, permissões e fila não estão
  implementados.
- A guild configurada pode não ser encontrada depois do JDA ficar pronto; esse
  caso impede o registro do comando, mas não muda o estado `running` do
  lifecycle.
- Falhas assíncronas de resposta e registro são tratadas de forma limitada; o
  registrar possui callback de erro, enquanto a resposta `pong` não possui.
- Os templates `.env.example` e `.env.production.example` estão incompletos para
  a configuração Discord.
- Não há documentação operacional completa nem teste dedicado do registrar.
- Números `#56` e `#74` a `#82` são tarefas Taiga. Um PR futuro deve usar a
  descrição explícita da tarefa e não usar palavras-chave de encerramento
  automático sem uma GitHub Issue correspondente confirmada.
- Fontes gerados preexistentes em `target/generated-sources/annotations` são
  dívida técnica fora do escopo; esta tarefa não os adiciona, remove ou altera.
