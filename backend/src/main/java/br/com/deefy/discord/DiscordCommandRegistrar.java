package br.com.deefy.discord;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class DiscordCommandRegistrar extends ListenerAdapter {

    private static final Logger LOGGER = LoggerFactory.getLogger(DiscordCommandRegistrar.class);

    private final DiscordBotProperties properties;

    public DiscordCommandRegistrar(DiscordBotProperties properties) {
        this.properties = properties;
    }

    @Override
    public void onReady(ReadyEvent event) {
        JDA jda = event.getJDA();
        List<CommandData> commands = commands();

        if (!properties.isTestMode()) {
            commands.forEach(command -> registerGlobally(jda, command));
            LOGGER.info("Discord bot connected; global command registration requested");
            return;
        }

        String guildId = properties.getTestGuildId().trim();
        Guild guild = jda.getGuildById(guildId);
        if (guild == null) {
            LOGGER.error("Discord bot connected, but the configured test guild was not found");
            return;
        }

        commands.forEach(command -> registerInTestGuild(guild, command));
        LOGGER.info("Discord bot connected; test-guild command registration requested");
    }

    private List<CommandData> commands() {
        OptionData music = new OptionData(
                OptionType.STRING,
                "musica",
                "Título ou título - artista (ID também é aceito)",
                true
        ).setAutoComplete(true);

        return List.of(
                Commands.slash("deefy", "Comandos do Deefy")
                        .addSubcommands(new SubcommandData("ping", "Verifica se o bot está conectado")),
                Commands.slash("entrar", "Conecta o bot ao seu canal de voz"),
                Commands.slash("sair", "Desconecta o bot do canal de voz"),
                Commands.slash("tocar", "Toca uma faixa do catálogo Deefy").addOptions(music),
                Commands.slash("pausar", "Pausa a faixa atual sem perder a posição"),
                Commands.slash("retomar", "Retoma a faixa pausada"),
                Commands.slash("parar", "Encerra a faixa atual e mantém o bot no canal por 10 minutos"),
                Commands.slash("pular", "Encerra a faixa atual e inicia a próxima da fila"),
                Commands.slash("voltar", "Volta para a última faixa do histórico"),
                Commands.slash("reiniciar", "Reinicia a faixa atual desde o começo"),
                Commands.slash("fila", "Consulta e organiza a fila de reprodução")
                        .addSubcommands(
                                new SubcommandData("ver", "Mostra a faixa atual e as pendentes"),
                                new SubcommandData("remover", "Remove uma posição pendente")
                                        .addOptions(new OptionData(
                                                OptionType.INTEGER,
                                                "posicao",
                                                "Posição pública da faixa pendente",
                                                true
                                        ).setRequiredRange(1, 50)),
                                new SubcommandData("mover", "Move uma faixa pendente")
                                        .addOptions(
                                                new OptionData(
                                                        OptionType.INTEGER,
                                                        "origem",
                                                        "Posição atual",
                                                        true
                                                ).setRequiredRange(1, 50),
                                                new OptionData(
                                                        OptionType.INTEGER,
                                                        "destino",
                                                        "Nova posição",
                                                        true
                                                ).setRequiredRange(1, 50)
                                        ),
                                new SubcommandData("limpar", "Remove todas as faixas pendentes"),
                                new SubcommandData("embaralhar", "Embaralha somente as faixas pendentes")
                        )
        );
    }

    private void registerGlobally(JDA jda, CommandData command) {
        jda.upsertCommand(command)
                .queue(
                        registered -> LOGGER.info("Discord global command /{} registered", command.getName()),
                        error -> LOGGER.error("Could not register Discord global command /{}", command.getName())
                );
    }

    private void registerInTestGuild(Guild guild, CommandData command) {
        guild.upsertCommand(command)
                .queue(
                        registered -> LOGGER.info("Discord command /{} registered in test guild", command.getName()),
                        error -> LOGGER.error("Could not register Discord command /{} in test guild", command.getName())
                );
    }
}
