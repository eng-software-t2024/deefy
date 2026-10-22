package br.com.deefy.discord;

import net.dv8tion.jda.api.components.MessageTopLevelComponent;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.interactions.AutoCompleteQuery;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.requests.RestAction;
import net.dv8tion.jda.api.requests.restaction.MessageEditAction;
import net.dv8tion.jda.api.requests.restaction.WebhookMessageCreateAction;
import net.dv8tion.jda.api.requests.restaction.WebhookMessageEditAction;
import net.dv8tion.jda.api.requests.restaction.interactions.AutoCompleteCallbackAction;
import net.dv8tion.jda.api.requests.restaction.interactions.MessageEditCallbackAction;
import net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DiscordCommandListenerTest {

    @Test
    void shouldIgnoreCommandsFromDirectMessage() {
        DiscordPlaybackService playbackService = mock(DiscordPlaybackService.class);
        SlashCommandInteractionEvent event = mock(SlashCommandInteractionEvent.class);
        when(event.isFromGuild()).thenReturn(false);

        listener(configuredProperties(), playbackService).onSlashCommandInteraction(event);

        verifyNoInteractions(playbackService);
        verify(event, never()).reply(anyString());
    }

    @Test
    void shouldReplyPongPubliclyWithoutRenewingPlaybackState() {
        DiscordPlaybackService playbackService = mock(DiscordPlaybackService.class);
        SlashCommandInteractionEvent event = guildSlash(configuredProperties(), "deefy");
        ReplyCallbackAction reply = mock(ReplyCallbackAction.class);
        when(event.getSubcommandName()).thenReturn("ping");
        when(event.reply("pong")).thenReturn(reply);
        when(reply.setEphemeral(false)).thenReturn(reply);
        when(reply.setAllowedMentions(anyList())).thenReturn(reply);

        listener(configuredProperties(), playbackService).onSlashCommandInteraction(event);

        verify(reply).setEphemeral(false);
        verify(reply).setAllowedMentions(List.of());
        verify(reply).queue();
        verifyNoInteractions(playbackService);
    }

    @Test
    void shouldAcceptCommandsFromAnyGuildInNormalMode() {
        DiscordBotProperties properties = new DiscordBotProperties();
        SlashCommandInteractionEvent event = guildSlash(properties, "deefy");
        ReplyCallbackAction reply = mock(ReplyCallbackAction.class);
        when(event.getSubcommandName()).thenReturn("ping");
        when(event.reply("pong")).thenReturn(reply);
        when(reply.setEphemeral(false)).thenReturn(reply);
        when(reply.setAllowedMentions(anyList())).thenReturn(reply);

        listener(properties, mock(DiscordPlaybackService.class)).onSlashCommandInteraction(event);

        verify(reply).queue();
    }

    @Test
    void shouldIgnoreCommandsOutsideConfiguredGuildOnlyInTestMode() {
        DiscordBotProperties properties = configuredProperties();
        SlashCommandInteractionEvent event = mock(SlashCommandInteractionEvent.class);
        Guild guild = mock(Guild.class);
        when(event.isFromGuild()).thenReturn(true);
        when(event.getGuild()).thenReturn(guild);
        when(guild.getId()).thenReturn("987654321098765432");

        listener(properties, mock(DiscordPlaybackService.class)).onSlashCommandInteraction(event);

        verify(event, never()).reply(anyString());
    }

    @Test
    void shouldAcknowledgeJoinAsPublicAndCompleteAsynchronously() {
        DiscordBotProperties properties = configuredProperties();
        DiscordPlaybackService playbackService = mock(DiscordPlaybackService.class);
        SlashCommandInteractionEvent event = guildSlash(properties, "entrar");
        ReplyCallbackAction defer = mock(ReplyCallbackAction.class);
        InteractionHook hook = mock(InteractionHook.class);
        WebhookMessageEditAction<Message> edit = mock(WebhookMessageEditAction.class);
        Guild guild = event.getGuild();
        Member member = mock(Member.class);
        when(event.getMember()).thenReturn(member);
        when(event.getId()).thenReturn("join-1");
        when(event.deferReply(false)).thenReturn(defer);
        when(defer.setAllowedMentions(anyList())).thenReturn(defer);
        completeReply(defer, hook);
        when(playbackService.joinAsync(guild, member, "join-1")).thenReturn(CompletableFuture.completedFuture(
                DiscordVoiceConnectionService.VoiceCommandResult.CONNECTION_STARTED
        ));
        String response = DiscordVoiceConnectionService.VoiceCommandResult.CONNECTION_STARTED.responseMessage();
        when(hook.editOriginal(response)).thenReturn(edit);
        when(edit.setAllowedMentions(anyList())).thenReturn(edit);

        listener(properties, playbackService).onSlashCommandInteraction(event);

        verify(event).deferReply(false);
        verify(playbackService).joinAsync(guild, member, "join-1");
        verify(edit).queue();
    }

    @Test
    void shouldRouteLeaveThroughPlaybackDomainAndReplyPublicly() {
        DiscordBotProperties properties = configuredProperties();
        DiscordPlaybackService playbackService = mock(DiscordPlaybackService.class);
        SlashCommandInteractionEvent event = guildSlash(properties, "sair");
        ReplyCallbackAction defer = mock(ReplyCallbackAction.class);
        InteractionHook hook = mock(InteractionHook.class);
        WebhookMessageEditAction<Message> edit = mock(WebhookMessageEditAction.class);
        Guild guild = event.getGuild();
        Member member = mock(Member.class);
        when(event.getMember()).thenReturn(member);
        when(event.getId()).thenReturn("leave-1");
        when(event.deferReply(false)).thenReturn(defer);
        when(defer.setAllowedMentions(anyList())).thenReturn(defer);
        completeReply(defer, hook);
        when(playbackService.leaveAsync(guild, member, "leave-1")).thenReturn(CompletableFuture.completedFuture(
                DiscordVoiceConnectionService.VoiceCommandResult.DISCONNECTION_STARTED
        ));
        String response = DiscordVoiceConnectionService.VoiceCommandResult.DISCONNECTION_STARTED.responseMessage();
        when(hook.editOriginal(response)).thenReturn(edit);
        when(edit.setAllowedMentions(anyList())).thenReturn(edit);

        listener(properties, playbackService).onSlashCommandInteraction(event);

        verify(playbackService).leaveAsync(guild, member, "leave-1");
        verify(event).deferReply(false);
        verify(edit).queue();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    void shouldPublishReadablePlaybackMessageWithButtonsAndNoTechnicalText() {
        DiscordBotProperties properties = configuredProperties();
        DiscordPlaybackService playbackService = mock(DiscordPlaybackService.class);
        SlashCommandInteractionEvent event = guildSlash(properties, "tocar");
        Guild guild = event.getGuild();
        Member member = mock(Member.class);
        OptionMapping option = mock(OptionMapping.class);
        ReplyCallbackAction reply = mock(ReplyCallbackAction.class);
        InteractionHook hook = mock(InteractionHook.class);
        MessageEditAction edit = mock(MessageEditAction.class);
        RestAction<Message> retrieve = mock(RestAction.class);
        Message message = mock(Message.class);
        ArgumentCaptor<List<MessageEmbed>> embeds = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<? extends MessageTopLevelComponent>> components = ArgumentCaptor.forClass(List.class);
        DiscordPlaybackService.PlaybackPresentation playing = presentation(
                DiscordPlaybackService.PlaybackMessageState.PLAYING,
                "Tocando agora"
        );

        when(event.getMember()).thenReturn(member);
        when(event.getId()).thenReturn("play-1");
        when(event.getOption("musica")).thenReturn(option);
        when(option.getAsString()).thenReturn("XTRANHO - Matuê");
        when(event.replyEmbeds(anyList())).thenReturn(reply);
        when(reply.setEphemeral(false)).thenReturn(reply);
        when(reply.setAllowedMentions(anyList())).thenReturn(reply);
        completeReply(reply, hook);
        when(message.editMessageEmbeds(embeds.capture())).thenReturn(edit);
        when(edit.setComponents(components.capture())).thenReturn(edit);
        when(edit.setAllowedMentions(anyList())).thenReturn(edit);
        completeEdit(edit);
        when(hook.retrieveOriginal()).thenReturn(retrieve);
        when(message.getIdLong()).thenReturn(99L);
        when(retrieve.submit()).thenReturn(CompletableFuture.completedFuture(message));
        when(playbackService.bindControlMessageAsync(guild.getIdLong(), "session-1", 99L))
                .thenReturn(CompletableFuture.completedFuture(true));
        when(playbackService.playAsync(eq(guild), eq(member), eq("XTRANHO - Matuê"), eq("play-1"), any()))
                .thenAnswer(invocation -> {
                    Consumer<DiscordPlaybackService.PlaybackProgressUpdate> observer = invocation.getArgument(4);
                    observer.accept(new DiscordPlaybackService.PlaybackProgressUpdate(playing));
                    return CompletableFuture.completedFuture(new DiscordPlaybackService.PlaybackCommandResult(
                            presentation(DiscordPlaybackService.PlaybackMessageState.PREPARING, "Preparando reprodução"),
                            true,
                            "session-1"
                    ));
                });

        listener(properties, playbackService).onSlashCommandInteraction(event);

        MessageEmbed published = embeds.getValue().getFirst();
        assertThat(published.getTitle()).isEqualTo("Tocando agora");
        assertThat(published.getFields()).extracting(MessageEmbed.Field::getValue)
                .contains("XTRANHO", "Matuê", "Pessoa de teste", "Música");
        assertThat(published.toData().toString())
                .doesNotContain("PCM", "frame", "DAVE", "codec", "audição no cliente");
        assertThat(embeds.getAllValues())
                .extracting(value -> value.getFirst().getTitle())
                .containsExactly("Tocando agora");
        List<ActionRow> rows = (List<ActionRow>) (List) components.getValue();
        assertThat(rows).singleElement().satisfies(row -> assertThat(row.getButtons())
                .extracting(button -> button.getLabel())
                .containsExactly("⏸️ Pausar", "⏹️ Parar", "⏭️ Pular", "⏮️ Voltar", "🔄 Reiniciar"));
        verify(playbackService).bindControlMessageAsync(guild.getIdLong(), "session-1", 99L);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    void shouldNotPublishSessionButtonsBeforeTheMessageIsBound() {
        DiscordBotProperties properties = configuredProperties();
        DiscordPlaybackService playbackService = mock(DiscordPlaybackService.class);
        SlashCommandInteractionEvent event = guildSlash(properties, "tocar");
        Guild guild = event.getGuild();
        Member member = mock(Member.class);
        OptionMapping option = mock(OptionMapping.class);
        ReplyCallbackAction reply = mock(ReplyCallbackAction.class);
        InteractionHook hook = mock(InteractionHook.class);
        MessageEditAction edit = mock(MessageEditAction.class);
        RestAction<Message> retrieve = mock(RestAction.class);
        Message message = mock(Message.class);
        CompletableFuture<Boolean> binding = new CompletableFuture<>();
        ArgumentCaptor<List<MessageEmbed>> embeds = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<? extends MessageTopLevelComponent>> components = ArgumentCaptor.forClass(List.class);
        DiscordPlaybackService.PlaybackPresentation playing = presentation(
                DiscordPlaybackService.PlaybackMessageState.PLAYING,
                "Tocando agora"
        );
        when(event.getMember()).thenReturn(member);
        when(event.getId()).thenReturn("play-1");
        when(event.getOption("musica")).thenReturn(option);
        when(option.getAsString()).thenReturn("XTRANHO - Matuê");
        when(event.replyEmbeds(anyList())).thenReturn(reply);
        when(reply.setEphemeral(false)).thenReturn(reply);
        when(reply.setAllowedMentions(anyList())).thenReturn(reply);
        completeReply(reply, hook);
        when(hook.retrieveOriginal()).thenReturn(retrieve);
        when(retrieve.submit()).thenReturn(CompletableFuture.completedFuture(message));
        when(message.getIdLong()).thenReturn(99L);
        when(playbackService.bindControlMessageAsync(guild.getIdLong(), "session-1", 99L))
                .thenReturn(binding);
        when(playbackService.playAsync(eq(guild), eq(member), eq("XTRANHO - Matuê"), eq("play-1"), any()))
                .thenAnswer(invocation -> {
                    Consumer<DiscordPlaybackService.PlaybackProgressUpdate> observer = invocation.getArgument(4);
                    observer.accept(new DiscordPlaybackService.PlaybackProgressUpdate(playing));
                    return CompletableFuture.completedFuture(new DiscordPlaybackService.PlaybackCommandResult(
                            presentation(DiscordPlaybackService.PlaybackMessageState.PREPARING, "Preparando reprodução"),
                            true,
                            "session-1"
                    ));
                });
        when(message.editMessageEmbeds(embeds.capture())).thenReturn(edit);
        when(edit.setComponents(components.capture())).thenReturn(edit);
        when(edit.setAllowedMentions(anyList())).thenReturn(edit);
        completeEdit(edit);

        listener(properties, playbackService).onSlashCommandInteraction(event);

        verify(message, never()).editMessageEmbeds(anyList());

        binding.complete(true);

        verify(message).editMessageEmbeds(anyList());
        assertThat(embeds.getValue().getFirst().getTitle()).isEqualTo("Tocando agora");
        List<ActionRow> rows = (List<ActionRow>) (List) components.getValue();
        assertThat(rows).singleElement().satisfies(row -> assertThat(row.getButtons())
                .extracting(button -> button.getLabel())
                .containsExactly("⏸️ Pausar", "⏹️ Parar", "⏭️ Pular", "⏮️ Voltar", "🔄 Reiniciar"));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    void shouldReuseTheGuildPanelWhenASecondTrackIsQueued() {
        DiscordBotProperties properties = configuredProperties();
        DiscordPlaybackService playbackService = mock(DiscordPlaybackService.class);
        DiscordCommandListener listener = listener(properties, playbackService);
        Guild guild = mock(Guild.class);
        Member member = mock(Member.class);
        when(guild.getId()).thenReturn(properties.getTestGuildId());
        when(guild.getIdLong()).thenReturn(123L);

        SlashCommandInteractionEvent firstEvent = mock(SlashCommandInteractionEvent.class);
        OptionMapping firstOption = mock(OptionMapping.class);
        ReplyCallbackAction firstReply = mock(ReplyCallbackAction.class);
        InteractionHook panelHook = mock(InteractionHook.class);
        MessageEditAction panelEdit = mock(MessageEditAction.class);
        RestAction<Message> retrieve = mock(RestAction.class);
        Message panelMessage = mock(Message.class);
        ArgumentCaptor<List<MessageEmbed>> panelEmbeds = ArgumentCaptor.forClass(List.class);
        when(firstEvent.isFromGuild()).thenReturn(true);
        when(firstEvent.getGuild()).thenReturn(guild);
        when(firstEvent.getMember()).thenReturn(member);
        when(firstEvent.getId()).thenReturn("play-1");
        when(firstEvent.getName()).thenReturn("tocar");
        when(firstEvent.getOption("musica")).thenReturn(firstOption);
        when(firstOption.getAsString()).thenReturn("Faixa principal");
        when(firstEvent.replyEmbeds(anyList())).thenReturn(firstReply);
        when(firstReply.setEphemeral(false)).thenReturn(firstReply);
        when(firstReply.setAllowedMentions(anyList())).thenReturn(firstReply);
        completeReply(firstReply, panelHook);
        when(panelHook.retrieveOriginal()).thenReturn(retrieve);
        when(retrieve.submit()).thenReturn(CompletableFuture.completedFuture(panelMessage));
        when(panelMessage.getIdLong()).thenReturn(99L);
        when(panelMessage.editMessageEmbeds(panelEmbeds.capture())).thenReturn(panelEdit);
        when(panelEdit.setComponents(anyList())).thenReturn(panelEdit);
        when(panelEdit.setAllowedMentions(anyList())).thenReturn(panelEdit);
        completeEdit(panelEdit);
        when(playbackService.bindControlMessageAsync(123L, "session-1", 99L))
                .thenReturn(CompletableFuture.completedFuture(true));
        when(playbackService.playAsync(eq(guild), eq(member), eq("Faixa principal"), eq("play-1"), any()))
                .thenAnswer(invocation -> {
                    Consumer<DiscordPlaybackService.PlaybackProgressUpdate> observer = invocation.getArgument(4);
                    observer.accept(new DiscordPlaybackService.PlaybackProgressUpdate(
                            presentation(DiscordPlaybackService.PlaybackMessageState.PLAYING, "Tocando agora"),
                            "session-1"
                    ));
                    return CompletableFuture.completedFuture(new DiscordPlaybackService.PlaybackCommandResult(
                            presentation(DiscordPlaybackService.PlaybackMessageState.PREPARING, "Preparando reprodução"),
                            true,
                            "session-1"
                    ));
                });

        listener.onSlashCommandInteraction(firstEvent);

        SlashCommandInteractionEvent secondEvent = mock(SlashCommandInteractionEvent.class);
        OptionMapping secondOption = mock(OptionMapping.class);
        ReplyCallbackAction secondDefer = mock(ReplyCallbackAction.class);
        InteractionHook secondHook = mock(InteractionHook.class);
        WebhookMessageEditAction<Message> secondEdit = mock(WebhookMessageEditAction.class);
        when(secondEvent.isFromGuild()).thenReturn(true);
        when(secondEvent.getGuild()).thenReturn(guild);
        when(secondEvent.getMember()).thenReturn(member);
        when(secondEvent.getId()).thenReturn("play-2");
        when(secondEvent.getName()).thenReturn("tocar");
        when(secondEvent.getOption("musica")).thenReturn(secondOption);
        when(secondOption.getAsString()).thenReturn("Faixa seguinte");
        when(secondEvent.deferReply(true)).thenReturn(secondDefer);
        completeReply(secondDefer, secondHook);
        when(secondHook.editOriginal(anyString())).thenReturn(secondEdit);
        when(secondEdit.setAllowedMentions(anyList())).thenReturn(secondEdit);
        when(playbackService.currentSessionId(123L)).thenReturn("session-1");
        when(playbackService.currentPresentation(123L)).thenReturn(
                new DiscordPlaybackService.PlaybackPresentation(
                        DiscordPlaybackService.PlaybackMessageState.PLAYING,
                        "Tocando agora",
                        "A reprodução está em andamento.",
                        "Faixa principal",
                        "Artista",
                        "Pessoa de teste",
                        "Música",
                        1,
                        0,
                        List.of(new DiscordPlaybackService.QueueItemPresentation(
                                "Faixa seguinte", "Outro artista", "Pessoa de teste"))
                )
        );
        when(playbackService.playAsync(eq(guild), eq(member), eq("Faixa seguinte"), eq("play-2"), any()))
                .thenReturn(CompletableFuture.completedFuture(new DiscordPlaybackService.PlaybackCommandResult(
                        new DiscordPlaybackService.PlaybackPresentation(
                                DiscordPlaybackService.PlaybackMessageState.PREPARING,
                                "Faixa adicionada à fila",
                                "Posição 1 na fila pendente.",
                                "Faixa seguinte",
                                "Outro artista",
                                "Pessoa de teste",
                                "Música",
                                1,
                                0,
                                List.of()
                        ),
                        false,
                        null,
                        true,
                        1,
                        1
                )));

        listener.onSlashCommandInteraction(secondEvent);

        verify(firstEvent).replyEmbeds(anyList());
        verify(secondEvent, never()).replyEmbeds(anyList());
        verify(secondEvent).deferReply(true);
        verify(secondHook).editOriginal("✅ Faixa adicionada à fila. O painel de reprodução foi atualizado.");
        assertThat(panelEmbeds.getAllValues().getLast().getFirst().getFields())
                .extracting(MessageEmbed.Field::getName)
                .contains("Próximas faixas");
    }

    @Test
    void shouldRouteSlashPauseToSameDomainControlWithoutPublicFlood() {
        DiscordBotProperties properties = configuredProperties();
        DiscordPlaybackService playbackService = mock(DiscordPlaybackService.class);
        SlashCommandInteractionEvent event = guildSlash(properties, "pausar");
        ReplyCallbackAction defer = mock(ReplyCallbackAction.class);
        InteractionHook hook = mock(InteractionHook.class);
        RestAction<Void> delete = mock(RestAction.class);
        Member member = mock(Member.class);
        when(event.getMember()).thenReturn(member);
        when(event.getId()).thenReturn("pause-1");
        when(event.deferReply(true)).thenReturn(defer);
        completeReply(defer, hook);
        when(hook.deleteOriginal()).thenReturn(delete);
        when(playbackService.controlAsync(eq(event.getGuild()), eq(member), any()))
                .thenReturn(CompletableFuture.completedFuture(new DiscordPlaybackSessionService.PlaybackControlResult(
                        DiscordPlaybackSessionService.PlaybackControlStatus.APPLIED,
                        "Reprodução pausada."
                )));
        ArgumentCaptor<DiscordPlaybackSessionService.PlaybackControlRequest> request =
                ArgumentCaptor.forClass(DiscordPlaybackSessionService.PlaybackControlRequest.class);

        listener(properties, playbackService).onSlashCommandInteraction(event);

        verify(playbackService).controlAsync(eq(event.getGuild()), eq(member), request.capture());
        assertThat(request.getValue().action())
                .isEqualTo(DiscordPlaybackSessionService.PlaybackControlAction.PAUSE);
        assertThat(request.getValue().expectedSessionId()).isNull();
        verify(hook).deleteOriginal();
        verify(delete).queue();
    }

    @Test
    void shouldValidateButtonUsingSessionMessageGuildAndInteractionIdentity() {
        DiscordBotProperties properties = configuredProperties();
        DiscordPlaybackService playbackService = mock(DiscordPlaybackService.class);
        ButtonInteractionEvent event = mock(ButtonInteractionEvent.class);
        Guild guild = mock(Guild.class);
        Member member = mock(Member.class);
        MessageEditCallbackAction defer = mock(MessageEditCallbackAction.class);
        InteractionHook hook = mock(InteractionHook.class);
        when(event.isFromGuild()).thenReturn(true);
        when(event.getGuild()).thenReturn(guild);
        when(guild.getId()).thenReturn(properties.getTestGuildId());
        when(event.getMember()).thenReturn(member);
        when(event.getComponentId()).thenReturn("deefy:playback:stop:session-1");
        when(event.getMessageIdLong()).thenReturn(99L);
        when(event.getId()).thenReturn("button-1");
        when(event.deferEdit()).thenReturn(defer);
        completeButtonDefer(defer, hook);
        when(playbackService.controlAsync(eq(guild), eq(member), any()))
                .thenReturn(CompletableFuture.completedFuture(new DiscordPlaybackSessionService.PlaybackControlResult(
                        DiscordPlaybackSessionService.PlaybackControlStatus.APPLIED,
                        "Reprodução encerrada."
                )));
        ArgumentCaptor<DiscordPlaybackSessionService.PlaybackControlRequest> request =
                ArgumentCaptor.forClass(DiscordPlaybackSessionService.PlaybackControlRequest.class);

        listener(properties, playbackService).onButtonInteraction(event);

        verify(playbackService).controlAsync(eq(guild), eq(member), request.capture());
        assertThat(request.getValue()).satisfies(value -> {
            assertThat(value.action()).isEqualTo(DiscordPlaybackSessionService.PlaybackControlAction.STOP);
            assertThat(value.expectedSessionId()).isEqualTo("session-1");
            assertThat(value.expectedMessageId()).isEqualTo(99L);
            assertThat(value.interactionId()).isEqualTo("button-1");
        });
    }

    @Test
    void shouldRejectAnExpiredButtonEphemerallyWithoutPostingAnotherPublicMessage() {
        DiscordBotProperties properties = configuredProperties();
        DiscordPlaybackService playbackService = mock(DiscordPlaybackService.class);
        ButtonInteractionEvent event = mock(ButtonInteractionEvent.class);
        Guild guild = mock(Guild.class);
        Member member = mock(Member.class);
        MessageEditCallbackAction defer = mock(MessageEditCallbackAction.class);
        InteractionHook hook = mock(InteractionHook.class);
        WebhookMessageCreateAction<Message> rejection = mock(WebhookMessageCreateAction.class);
        when(event.isFromGuild()).thenReturn(true);
        when(event.getGuild()).thenReturn(guild);
        when(guild.getId()).thenReturn(properties.getTestGuildId());
        when(event.getMember()).thenReturn(member);
        when(event.getComponentId()).thenReturn("deefy:playback:pause:old-session");
        when(event.getMessageIdLong()).thenReturn(99L);
        when(event.getId()).thenReturn("old-button");
        when(event.deferEdit()).thenReturn(defer);
        completeButtonDefer(defer, hook);
        when(playbackService.controlAsync(eq(guild), eq(member), any()))
                .thenReturn(CompletableFuture.completedFuture(new DiscordPlaybackSessionService.PlaybackControlResult(
                        DiscordPlaybackSessionService.PlaybackControlStatus.EXPIRED_CONTROL,
                        "Esse controle pertence a uma reprodução encerrada."
                )));
        when(hook.sendMessage("Esse controle pertence a uma reprodução encerrada."))
                .thenReturn(rejection);
        when(rejection.setEphemeral(true)).thenReturn(rejection);
        when(rejection.setAllowedMentions(anyList())).thenReturn(rejection);

        listener(properties, playbackService).onButtonInteraction(event);

        verify(event).deferEdit();
        verify(rejection).setEphemeral(true);
        verify(rejection).queue();
    }

    @Test
    void shouldReturnSelfHostedCatalogSuggestionsForPlayAutocomplete() {
        DiscordBotProperties properties = new DiscordBotProperties();
        DiscordPlaybackService playbackService = mock(DiscordPlaybackService.class);
        CommandAutoCompleteInteractionEvent event = mock(CommandAutoCompleteInteractionEvent.class);
        Guild guild = mock(Guild.class);
        AutoCompleteQuery focusedOption = mock(AutoCompleteQuery.class);
        AutoCompleteCallbackAction action = mock(AutoCompleteCallbackAction.class);
        when(event.isFromGuild()).thenReturn(true);
        when(event.getGuild()).thenReturn(guild);
        when(guild.getId()).thenReturn("987654321098765432");
        when(event.getName()).thenReturn("tocar");
        when(event.getFocusedOption()).thenReturn(focusedOption);
        when(focusedOption.getName()).thenReturn("musica");
        when(focusedOption.getValue()).thenReturn("xtranho");
        when(playbackService.suggest("xtranho")).thenReturn(List.of(
                new DiscordPlaybackService.PlaybackSuggestion("Matuê, BRANDÃO85 - XTRANHO", "369")
        ));
        when(event.replyChoices(anyList())).thenReturn(action);

        listener(properties, playbackService).onCommandAutoCompleteInteraction(event);

        verify(event).replyChoices(anyList());
        verify(action).queue();
    }

    private DiscordCommandListener listener(
            DiscordBotProperties properties,
            DiscordPlaybackService playbackService
    ) {
        return new DiscordCommandListener(
                properties,
                mock(DiscordVoiceConnectionService.class),
                playbackService
        );
    }

    private SlashCommandInteractionEvent guildSlash(DiscordBotProperties properties, String command) {
        SlashCommandInteractionEvent event = mock(SlashCommandInteractionEvent.class);
        Guild guild = mock(Guild.class);
        when(event.isFromGuild()).thenReturn(true);
        when(event.getGuild()).thenReturn(guild);
        when(guild.getId()).thenReturn(properties.isTestMode()
                ? properties.getTestGuildId()
                : "987654321098765432");
        when(guild.getIdLong()).thenReturn(123L);
        when(event.getName()).thenReturn(command);
        return event;
    }

    private DiscordPlaybackService.PlaybackPresentation presentation(
            DiscordPlaybackService.PlaybackMessageState state,
            String heading
    ) {
        return new DiscordPlaybackService.PlaybackPresentation(
                state,
                heading,
                state == DiscordPlaybackService.PlaybackMessageState.PLAYING
                        ? "A reprodução está em andamento."
                        : "Preparando.",
                "XTRANHO",
                "Matuê",
                "Pessoa de teste",
                "Música"
        );
    }

    @SuppressWarnings("unchecked")
    private void completeReply(ReplyCallbackAction action, InteractionHook hook) {
        doAnswer(invocation -> {
            invocation.<Consumer<InteractionHook>>getArgument(0).accept(hook);
            return null;
        }).when(action).queue(any());
    }

    @SuppressWarnings("unchecked")
    private void completeButtonDefer(MessageEditCallbackAction action, InteractionHook hook) {
        doAnswer(invocation -> {
            invocation.<Consumer<InteractionHook>>getArgument(0).accept(hook);
            return null;
        }).when(action).queue(any());
    }

    @SuppressWarnings("unchecked")
    private void completeEdit(RestAction<Message> action) {
        doAnswer(invocation -> {
            invocation.<Consumer<Message>>getArgument(0).accept(mock(Message.class));
            return null;
        }).when(action).queue(any(), any());
    }

    private DiscordBotProperties configuredProperties() {
        DiscordBotProperties properties = new DiscordBotProperties();
        properties.setTestMode(true);
        properties.setTestGuildId("123456789012345678");
        return properties;
    }
}
