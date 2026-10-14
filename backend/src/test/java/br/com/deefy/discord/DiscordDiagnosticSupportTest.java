package br.com.deefy.discord;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DiscordDiagnosticSupportTest {

    @Test
    void shouldProduceStableShortCorrelationWithoutExposingInput() {
        String original = "interaction-value-that-must-not-appear";

        String first = DiscordDiagnosticSupport.pseudonymize(original);

        assertThat(first).hasSize(12)
                .matches("[0-9a-f]{12}")
                .isEqualTo(DiscordDiagnosticSupport.pseudonymize(original))
                .doesNotContain(original);
    }

    @Test
    void shouldUseAnExplicitMarkerForMissingValues() {
        assertThat(DiscordDiagnosticSupport.pseudonymize(null)).isEqualTo("none");
        assertThat(DiscordDiagnosticSupport.pseudonymize(" ")).isEqualTo("none");
    }
}
