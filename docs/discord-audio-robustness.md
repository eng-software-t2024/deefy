# Robustez de áudio do bot Discord

Esta entrega prepara a faixa antes de entregá-la ao player JDA. O fluxo é:

1. validar o ID e a origem self-hosted da faixa antes de abrir HTTP;
2. baixar o arquivo completo para um `.part` no volume exclusivo do bot;
3. validar metadados com `ffprobe` e fazer uma amostra limitada de decode com `ffmpeg`;
4. mover o arquivo para o nome final somente com rename atômico;
5. aguardar o buffer inicial configurado antes de instalar o handler de voz;
6. reproduzir somente o `Path` local validado.

Não há fallback para streaming remoto, reprodução progressiva, fila, `/pular`,
`/voltar` ou `/reiniciar` nesta etapa.

## Cache e retry

O nome do arquivo final usa o ID canônico da faixa. O cache verifica TTL,
tamanho e SHA-256 local e, quando a origem fornece `ETag` ou `Last-Modified`,
confere a revisão antes de reutilizá-lo. Um arquivo parcial nunca é usado pelo
player.

São feitas exatamente três tentativas totais para falhas transitórias: a
segunda começa após 2 segundos e a terceira após 5 segundos. O valor de 10
segundos pode permanecer documentado/configurável para evolução futura, mas não
é usado para criar uma quarta tentativa.

## Origem e segurança

A origem permitida é a mesma origem configurada em `DEEFY_API_URL`, sem
credenciais, query string, fragmento ou o domínio hospedado legado. Cada salto
de redirecionamento é validado antes de uma nova conexão. URLs, cabeçalhos de
autorização, tokens e credenciais não são registrados em logs ou mensagens do
Discord.

O preflight exige que `/var/cache/deefy-discord` exista e seja gravável pelo
UID/GID do processo. Se o volume não estiver disponível, o bot permanece
parado; não há alteração automática de permissões nem fallback remoto.

## Barreira de início

Os valores padrão são 10 segundos de áudio em buffer e 15 segundos de espera.
Faixas menores podem iniciar quando o arquivo validado terminou de produzir e
há áudio disponível. Timeout, erro de decode, truncamento ou arquivo vazio
encerram a preparação com mensagem segura ao usuário.

## Telemetria

Os logs agregam apenas IDs técnicos da faixa e métricas não sensíveis: duração
da validação, tempo de preparação, bytes, frames produzidos/entregues,
profundidade mínima/máxima do buffer, consultas sem frame, intervalos de
entrega, exit code e motivo de cleanup. Diagnósticos de processo são limitados
e sanitizados. Não há I/O adicional na thread de entrega de áudio.

## Rollback e validação

O rollout deve substituir somente a imagem/configuração do serviço
`deefy-discord-bot`, mantendo a imagem anterior identificada para rollback.
Antes de uma guild de teste, validar o preflight do cache, a imagem com as
restrições reais do Compose e uma faixa do catálogo self-hosted. Em falha,
parar ou restaurar somente o serviço do bot, sem tocar no site, banco, Storage,
catálogo ou demais serviços.
