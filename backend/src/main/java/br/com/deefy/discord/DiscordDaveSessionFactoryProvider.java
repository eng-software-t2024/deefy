package br.com.deefy.discord;

import net.dv8tion.jda.api.audio.dave.DaveSessionFactory;
import net.dv8tion.jda.api.audio.dave.DaveProtocolCallbacks;
import net.dv8tion.jda.api.audio.dave.DaveSession;
import org.springframework.stereotype.Component;

import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;

@Component
public class DiscordDaveSessionFactoryProvider {

    static final String JDAVE_FACTORY_CLASS = "club.minnced.discord.jdave.interop.JDaveSessionFactory";

    DaveSessionFactory createRequired() {
        try {
            Class<?> factoryClass = Class.forName(JDAVE_FACTORY_CLASS);
            Object factory = factoryClass.getDeclaredConstructor().newInstance();
            if (!(factory instanceof DaveSessionFactory daveSessionFactory)) {
                throw new IllegalStateException("Configured DAVE provider is incompatible with JDA");
            }
            verifyNativeLibrary(daveSessionFactory);
            return daveSessionFactory;
        } catch (ClassNotFoundException exception) {
            throw new IllegalStateException("Required DAVE provider is not available", exception);
        } catch (InvocationTargetException exception) {
            throw new IllegalStateException("Required DAVE provider could not be initialized", exception.getCause());
        } catch (ReflectiveOperationException | LinkageError exception) {
            throw new IllegalStateException("Required DAVE provider could not be initialized", exception);
        }
    }

    private void verifyNativeLibrary(DaveSessionFactory factory) {
        DaveSession session = null;
        try {
            session = factory.createDaveSession(new NoOpDaveCallbacks(), 0L, 0L);
            if (session.getMaxProtocolVersion() <= 0) {
                throw new IllegalStateException("Required DAVE provider reported no supported protocol");
            }
        } catch (RuntimeException | LinkageError exception) {
            throw new IllegalStateException("Required DAVE native library could not be loaded", exception);
        } finally {
            if (session != null) {
                session.destroy();
            }
        }
    }

    private static final class NoOpDaveCallbacks implements DaveProtocolCallbacks {
        @Override
        public void sendMLSKeyPackage(ByteBuffer keyPackage) {
        }

        @Override
        public void sendDaveProtocolReadyForTransition(int transitionId) {
        }

        @Override
        public void sendMLSCommitWelcome(ByteBuffer commitWelcome) {
        }

        @Override
        public void sendMLSInvalidCommitWelcome(int transitionId) {
        }
    }
}
