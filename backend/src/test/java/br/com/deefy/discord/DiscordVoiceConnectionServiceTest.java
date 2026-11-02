package br.com.deefy.discord;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.audio.hooks.ConnectionListener;
import net.dv8tion.jda.api.audio.hooks.ConnectionStatus;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.GuildVoiceState;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.SelfMember;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.entities.channel.unions.AudioChannelUnion;
import net.dv8tion.jda.api.managers.AudioManager;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DiscordVoiceConnectionServiceTest {

    private final DiscordVoiceConnectionService service = new DiscordVoiceConnectionService();

    @Test
    void shouldRejectJoinWhenRequesterIsNotInVoice() {
        Guild guild = mock(Guild.class);
        Member requester = mock(Member.class);

        assertThat(service.join(guild, requester))
                .isEqualTo(DiscordVoiceConnectionService.VoiceCommandResult.USER_NOT_IN_VOICE);

        verify(guild, never()).getAudioManager();
    }

    @Test
    void shouldRejectStageChannel() {
        Guild guild = mock(Guild.class);
        Member requester = mock(Member.class);
        GuildVoiceState voiceState = mock(GuildVoiceState.class);
        AudioChannelUnion stageChannel = mock(AudioChannelUnion.class);
        when(requester.getVoiceState()).thenReturn(voiceState);
        when(voiceState.getChannel()).thenReturn(stageChannel);
        when(stageChannel.getType()).thenReturn(ChannelType.STAGE);

        assertThat(service.join(guild, requester))
                .isEqualTo(DiscordVoiceConnectionService.VoiceCommandResult.UNSUPPORTED_CHANNEL);

        verify(guild, never()).getAudioManager();
    }

    @Test
    void shouldRejectJoinWhenBotCannotAccessChannel() {
        VoiceFixture fixture = fixture(1L, 10L);
        when(fixture.selfMember.hasPermission(
                fixture.voiceChannel,
                Permission.VIEW_CHANNEL,
                Permission.VOICE_CONNECT
        )).thenReturn(false);

        assertThat(service.join(fixture.guild, fixture.requester))
                .isEqualTo(DiscordVoiceConnectionService.VoiceCommandResult.MISSING_PERMISSIONS);

        verify(fixture.audioManager, never()).openAudioConnection(fixture.voiceChannel);
    }

    @Test
    void shouldOpenConnectionToRequesterVoiceChannel() {
        VoiceFixture fixture = fixture(1L, 10L);

        assertThat(service.join(fixture.guild, fixture.requester))
                .isEqualTo(DiscordVoiceConnectionService.VoiceCommandResult.CONNECTION_STARTED);

        verify(fixture.audioManager).openAudioConnection(fixture.voiceChannel);
    }

    @Test
    void shouldNotMoveBotWhenAlreadyConnectedElsewhere() {
        VoiceFixture fixture = fixture(1L, 10L);
        AudioChannelUnion connectedChannel = mock(AudioChannelUnion.class);
        when(connectedChannel.getIdLong()).thenReturn(20L);
        when(fixture.audioManager.getConnectedChannel()).thenReturn(connectedChannel);

        assertThat(service.join(fixture.guild, fixture.requester))
                .isEqualTo(DiscordVoiceConnectionService.VoiceCommandResult.ALREADY_IN_ANOTHER_CHANNEL);

        verify(fixture.audioManager, never()).openAudioConnection(fixture.voiceChannel);
    }

    @Test
    void shouldTreatJoinToCurrentChannelAsIdempotent() {
        VoiceFixture fixture = fixture(1L, 10L);
        AudioChannelUnion connectedChannel = mock(AudioChannelUnion.class);
        when(connectedChannel.getIdLong()).thenReturn(10L);
        when(fixture.audioManager.getConnectedChannel()).thenReturn(connectedChannel);

        assertThat(service.join(fixture.guild, fixture.requester))
                .isEqualTo(DiscordVoiceConnectionService.VoiceCommandResult.ALREADY_IN_CHANNEL);

        verify(fixture.audioManager, never()).openAudioConnection(fixture.voiceChannel);
    }

    @Test
    void shouldSerializeConcurrentJoinRequestsWithinGuild() throws Exception {
        VoiceFixture fixture = fixture(1L, 10L);
        CountDownLatch firstAttemptStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstAttempt = new CountDownLatch(1);
        doAnswer(invocation -> {
            firstAttemptStarted.countDown();
            releaseFirstAttempt.await(5, TimeUnit.SECONDS);
            return null;
        }).when(fixture.audioManager).openAudioConnection(fixture.voiceChannel);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<DiscordVoiceConnectionService.VoiceCommandResult> first = executor.submit(
                    () -> service.join(fixture.guild, fixture.requester)
            );
            assertThat(firstAttemptStarted.await(5, TimeUnit.SECONDS)).isTrue();
            Future<DiscordVoiceConnectionService.VoiceCommandResult> second = executor.submit(
                    () -> service.join(fixture.guild, fixture.requester)
            );

            releaseFirstAttempt.countDown();

            assertThat(first.get(5, TimeUnit.SECONDS))
                    .isEqualTo(DiscordVoiceConnectionService.VoiceCommandResult.CONNECTION_STARTED);
            assertThat(second.get(5, TimeUnit.SECONDS))
                    .isEqualTo(DiscordVoiceConnectionService.VoiceCommandResult.CONNECTION_IN_PROGRESS);
            verify(fixture.audioManager).openAudioConnection(fixture.voiceChannel);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void shouldRejectConcurrentJoinToDifferentChannelWithinGuild() throws Exception {
        VoiceFixture fixture = fixture(1L, 10L);
        VoiceMember secondRequester = voiceMember(20L);
        when(fixture.selfMember.hasPermission(
                secondRequester.voiceChannel,
                Permission.VIEW_CHANNEL,
                Permission.VOICE_CONNECT
        )).thenReturn(true);
        CountDownLatch firstAttemptStarted = new CountDownLatch(1);
        CountDownLatch secondAttemptSubmitted = new CountDownLatch(1);
        CountDownLatch releaseFirstAttempt = new CountDownLatch(1);
        doAnswer(invocation -> {
            firstAttemptStarted.countDown();
            releaseFirstAttempt.await(5, TimeUnit.SECONDS);
            return null;
        }).when(fixture.audioManager).openAudioConnection(fixture.voiceChannel);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<DiscordVoiceConnectionService.VoiceCommandResult> first = executor.submit(
                    () -> service.join(fixture.guild, fixture.requester)
            );
            assertThat(firstAttemptStarted.await(5, TimeUnit.SECONDS)).isTrue();
            Future<DiscordVoiceConnectionService.VoiceCommandResult> second = executor.submit(() -> {
                secondAttemptSubmitted.countDown();
                return service.join(fixture.guild, secondRequester.member);
            });
            assertThat(secondAttemptSubmitted.await(5, TimeUnit.SECONDS)).isTrue();

            releaseFirstAttempt.countDown();

            assertThat(first.get(5, TimeUnit.SECONDS))
                    .isEqualTo(DiscordVoiceConnectionService.VoiceCommandResult.CONNECTION_STARTED);
            assertThat(second.get(5, TimeUnit.SECONDS))
                    .isEqualTo(DiscordVoiceConnectionService.VoiceCommandResult.CONNECTION_IN_PROGRESS_ELSEWHERE);
            verify(fixture.audioManager).openAudioConnection(fixture.voiceChannel);
            verify(fixture.audioManager, never()).openAudioConnection(secondRequester.voiceChannel);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void shouldKeepConnectionStateIndependentBetweenGuilds() {
        VoiceFixture firstGuild = fixture(1L, 10L);
        VoiceFixture secondGuild = fixture(2L, 20L);

        assertThat(service.join(firstGuild.guild, firstGuild.requester))
                .isEqualTo(DiscordVoiceConnectionService.VoiceCommandResult.CONNECTION_STARTED);
        assertThat(service.join(secondGuild.guild, secondGuild.requester))
                .isEqualTo(DiscordVoiceConnectionService.VoiceCommandResult.CONNECTION_STARTED);

        verify(firstGuild.audioManager).openAudioConnection(firstGuild.voiceChannel);
        verify(secondGuild.audioManager).openAudioConnection(secondGuild.voiceChannel);
    }

    @Test
    void shouldClearPendingAttemptAfterConnectionError() {
        VoiceFixture fixture = fixture(1L, 10L);
        ArgumentCaptor<ConnectionListener> listener = ArgumentCaptor.forClass(ConnectionListener.class);

        assertThat(service.join(fixture.guild, fixture.requester))
                .isEqualTo(DiscordVoiceConnectionService.VoiceCommandResult.CONNECTION_STARTED);
        verify(fixture.audioManager).setConnectionListener(listener.capture());

        listener.getValue().onStatusChange(ConnectionStatus.ERROR_CONNECTION_TIMEOUT);

        assertThat(service.join(fixture.guild, fixture.requester))
                .isEqualTo(DiscordVoiceConnectionService.VoiceCommandResult.CONNECTION_STARTED);
        verify(fixture.audioManager, times(2)).openAudioConnection(fixture.voiceChannel);
    }

    @Test
    void shouldNotLetLateFailureClearNewConnectionAttempt() {
        VoiceFixture fixture = fixture(1L, 10L);
        ArgumentCaptor<ConnectionListener> listeners = ArgumentCaptor.forClass(ConnectionListener.class);

        assertThat(service.join(fixture.guild, fixture.requester))
                .isEqualTo(DiscordVoiceConnectionService.VoiceCommandResult.CONNECTION_STARTED);
        verify(fixture.audioManager).setConnectionListener(listeners.capture());
        ConnectionListener firstListener = listeners.getValue();
        firstListener.onStatusChange(ConnectionStatus.ERROR_CONNECTION_TIMEOUT);

        assertThat(service.join(fixture.guild, fixture.requester))
                .isEqualTo(DiscordVoiceConnectionService.VoiceCommandResult.CONNECTION_STARTED);
        verify(fixture.audioManager, times(2)).setConnectionListener(listeners.capture());

        firstListener.onStatusChange(ConnectionStatus.ERROR_CONNECTION_TIMEOUT);

        assertThat(service.join(fixture.guild, fixture.requester))
                .isEqualTo(DiscordVoiceConnectionService.VoiceCommandResult.CONNECTION_IN_PROGRESS);
        verify(fixture.audioManager, times(2)).openAudioConnection(fixture.voiceChannel);
    }

    @Test
    void shouldClearPendingAttemptWhenListenerInstallationFails() {
        VoiceFixture fixture = fixture(1L, 10L);
        doThrow(new IllegalStateException("simulated"))
                .doNothing()
                .when(fixture.audioManager)
                .setConnectionListener(any(ConnectionListener.class));

        assertThat(service.join(fixture.guild, fixture.requester))
                .isEqualTo(DiscordVoiceConnectionService.VoiceCommandResult.CONNECTION_FAILED);
        assertThat(service.join(fixture.guild, fixture.requester))
                .isEqualTo(DiscordVoiceConnectionService.VoiceCommandResult.CONNECTION_STARTED);

        verify(fixture.audioManager, times(2)).setConnectionListener(any(ConnectionListener.class));
        verify(fixture.audioManager).openAudioConnection(fixture.voiceChannel);
    }

    @Test
    void shouldClearPendingAttemptAfterSynchronousFailure() {
        VoiceFixture fixture = fixture(1L, 10L);
        doThrow(new IllegalStateException("simulated"))
                .doNothing()
                .when(fixture.audioManager)
                .openAudioConnection(fixture.voiceChannel);

        assertThat(service.join(fixture.guild, fixture.requester))
                .isEqualTo(DiscordVoiceConnectionService.VoiceCommandResult.CONNECTION_FAILED);
        assertThat(service.join(fixture.guild, fixture.requester))
                .isEqualTo(DiscordVoiceConnectionService.VoiceCommandResult.CONNECTION_STARTED);
        verify(fixture.audioManager, times(2)).openAudioConnection(fixture.voiceChannel);
    }

    @Test
    void shouldDisconnectOnlyRequesterInBotChannel() {
        VoiceFixture fixture = fixture(1L, 10L);
        AudioChannelUnion connectedChannel = mock(AudioChannelUnion.class);
        when(connectedChannel.getIdLong()).thenReturn(10L);
        when(fixture.audioManager.getConnectedChannel()).thenReturn(connectedChannel);

        assertThat(service.leave(fixture.guild, fixture.requester))
                .isEqualTo(DiscordVoiceConnectionService.VoiceCommandResult.DISCONNECTION_STARTED);

        verify(fixture.audioManager).closeAudioConnection();
    }

    @Test
    void shouldRejectLeaveFromDifferentChannel() {
        VoiceFixture fixture = fixture(1L, 10L);
        AudioChannelUnion connectedChannel = mock(AudioChannelUnion.class);
        when(connectedChannel.getIdLong()).thenReturn(20L);
        when(fixture.audioManager.getConnectedChannel()).thenReturn(connectedChannel);

        assertThat(service.leave(fixture.guild, fixture.requester))
                .isEqualTo(DiscordVoiceConnectionService.VoiceCommandResult.USER_NOT_IN_BOT_CHANNEL);

        verify(fixture.audioManager, never()).closeAudioConnection();
    }

    @Test
    void shouldRejectLeaveWhenBotIsNotConnected() {
        VoiceFixture fixture = fixture(1L, 10L);

        assertThat(service.leave(fixture.guild, fixture.requester))
                .isEqualTo(DiscordVoiceConnectionService.VoiceCommandResult.BOT_NOT_CONNECTED);

        verify(fixture.audioManager, never()).closeAudioConnection();
    }

    @Test
    void shouldReturnControlledFailureAndAllowNewOperationAccordingToJdaState() {
        VoiceFixture fixture = fixture(1L, 10L);
        AudioChannelUnion connectedChannel = mock(AudioChannelUnion.class);
        when(connectedChannel.getIdLong()).thenReturn(10L);
        assertThat(service.join(fixture.guild, fixture.requester))
                .isEqualTo(DiscordVoiceConnectionService.VoiceCommandResult.CONNECTION_STARTED);
        when(fixture.audioManager.getConnectedChannel())
                .thenReturn(connectedChannel)
                .thenReturn(null);
        doThrow(new IllegalStateException("simulated"))
                .when(fixture.audioManager)
                .closeAudioConnection();

        assertThat(service.leave(fixture.guild, fixture.requester))
                .isEqualTo(DiscordVoiceConnectionService.VoiceCommandResult.DISCONNECTION_FAILED);
        assertThat(service.join(fixture.guild, fixture.requester))
                .isEqualTo(DiscordVoiceConnectionService.VoiceCommandResult.CONNECTION_STARTED);

        verify(fixture.audioManager).closeAudioConnection();
        verify(fixture.audioManager, times(2)).openAudioConnection(fixture.voiceChannel);
    }

    @Test
    void shouldKeepLeaveOperationsIndependentBetweenGuilds() {
        VoiceFixture firstGuild = fixture(1L, 10L);
        VoiceFixture secondGuild = fixture(2L, 20L);
        AudioChannelUnion firstConnectedChannel = mock(AudioChannelUnion.class);
        AudioChannelUnion secondConnectedChannel = mock(AudioChannelUnion.class);
        when(firstConnectedChannel.getIdLong()).thenReturn(10L);
        when(secondConnectedChannel.getIdLong()).thenReturn(20L);
        when(firstGuild.audioManager.getConnectedChannel()).thenReturn(firstConnectedChannel);
        when(secondGuild.audioManager.getConnectedChannel()).thenReturn(secondConnectedChannel);

        assertThat(service.leave(firstGuild.guild, firstGuild.requester))
                .isEqualTo(DiscordVoiceConnectionService.VoiceCommandResult.DISCONNECTION_STARTED);

        verify(firstGuild.audioManager).closeAudioConnection();
        verify(secondGuild.audioManager, never()).closeAudioConnection();

        assertThat(service.leave(secondGuild.guild, secondGuild.requester))
                .isEqualTo(DiscordVoiceConnectionService.VoiceCommandResult.DISCONNECTION_STARTED);
        verify(secondGuild.audioManager).closeAudioConnection();
    }

    private VoiceFixture fixture(long guildId, long channelId) {
        Guild guild = mock(Guild.class);
        Member requester = mock(Member.class);
        SelfMember selfMember = mock(SelfMember.class);
        GuildVoiceState voiceState = mock(GuildVoiceState.class);
        AudioChannelUnion channelUnion = mock(AudioChannelUnion.class);
        VoiceChannel voiceChannel = mock(VoiceChannel.class);
        AudioManager audioManager = mock(AudioManager.class);

        when(guild.getIdLong()).thenReturn(guildId);
        when(guild.getSelfMember()).thenReturn(selfMember);
        when(guild.getAudioManager()).thenReturn(audioManager);
        when(requester.getVoiceState()).thenReturn(voiceState);
        when(voiceState.getChannel()).thenReturn(channelUnion);
        when(channelUnion.getType()).thenReturn(ChannelType.VOICE);
        when(channelUnion.asVoiceChannel()).thenReturn(voiceChannel);
        when(voiceChannel.getIdLong()).thenReturn(channelId);
        when(selfMember.hasPermission(
                voiceChannel,
                Permission.VIEW_CHANNEL,
                Permission.VOICE_CONNECT
        )).thenReturn(true);

        return new VoiceFixture(guild, requester, selfMember, voiceChannel, audioManager);
    }

    private VoiceMember voiceMember(long channelId) {
        Member member = mock(Member.class);
        GuildVoiceState voiceState = mock(GuildVoiceState.class);
        AudioChannelUnion channelUnion = mock(AudioChannelUnion.class);
        VoiceChannel voiceChannel = mock(VoiceChannel.class);
        when(member.getVoiceState()).thenReturn(voiceState);
        when(voiceState.getChannel()).thenReturn(channelUnion);
        when(channelUnion.getType()).thenReturn(ChannelType.VOICE);
        when(channelUnion.asVoiceChannel()).thenReturn(voiceChannel);
        when(voiceChannel.getIdLong()).thenReturn(channelId);
        return new VoiceMember(member, voiceChannel);
    }

    private record VoiceFixture(
            Guild guild,
            Member requester,
            SelfMember selfMember,
            VoiceChannel voiceChannel,
            AudioManager audioManager
    ) {
    }

    private record VoiceMember(Member member, VoiceChannel voiceChannel) {
    }
}
