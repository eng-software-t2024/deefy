package br.com.deefy.discord;

public final class DiscordDaveNativeSmoke {

    private DiscordDaveNativeSmoke() {
    }

    public static void main(String[] args) {
        new DiscordDaveSessionFactoryProvider().createRequired();
        System.out.println("DAVE_NATIVE=PASS");
    }
}
