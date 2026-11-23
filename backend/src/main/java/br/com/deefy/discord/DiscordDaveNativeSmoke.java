package br.com.deefy.discord;

import net.dv8tion.jda.api.audio.AudioSendHandler;

import javax.sound.sampled.AudioFormat;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;

public final class DiscordDaveNativeSmoke {

    private static final int FRAME_DURATION_MILLISECONDS = 20;
    private static final int MAXIMUM_OPUS_PACKET_BYTES = 4_000;

    private DiscordDaveNativeSmoke() {
    }

    public static void main(String[] args) {
        DiscordBotProperties properties = new DiscordBotProperties();
        properties.setOpusLibraryPath(System.getProperty(
                "deefy.discord.opus-library-path",
                "/app/native/libopus.so"
        ));
        new DiscordOpusNativeProvider(properties).loadRequired();

        int encodedBytes = encodeJdaPcmFrame();
        System.out.printf("OPUS_ENCODER=PASS encodedBytes=%d%n", encodedBytes);

        new DiscordDaveSessionFactoryProvider().createRequired();
        System.out.println("DAVE_NATIVE=PASS");
        System.out.println("AUDIO_NATIVES=PASS");
    }

    private static int encodeJdaPcmFrame() {
        AudioFormat format = AudioSendHandler.INPUT_FORMAT;
        validateJdaPcmFormat(format);

        int samplesPerChannel = Math.round(
                format.getSampleRate() * FRAME_DURATION_MILLISECONDS / 1_000.0f
        );
        int frameBytes = samplesPerChannel * format.getFrameSize();

        ByteBuffer jdaPcmFrame = ByteBuffer.allocate(frameBytes).order(ByteOrder.BIG_ENDIAN);
        while (jdaPcmFrame.hasRemaining()) {
            jdaPcmFrame.putShort((short) 0);
        }
        jdaPcmFrame.flip();

        ShortBuffer nativePcm = ByteBuffer.allocateDirect(frameBytes)
                .order(ByteOrder.nativeOrder())
                .asShortBuffer();
        while (jdaPcmFrame.hasRemaining()) {
            nativePcm.put(jdaPcmFrame.getShort());
        }
        nativePcm.flip();

        IntBuffer error = ByteBuffer.allocateDirect(Integer.BYTES)
                .order(ByteOrder.nativeOrder())
                .asIntBuffer();
        Object encoder = null;
        try {
            Class<?> opusType = Class.forName("tomp2p.opuswrapper.Opus");
            Class<?> pointerByReferenceType = Class.forName("com.sun.jna.ptr.PointerByReference");
            Object opus = opusType.getField("INSTANCE").get(null);
            int applicationAudio = opusType.getField("OPUS_APPLICATION_AUDIO").getInt(null);
            int success = opusType.getField("OPUS_OK").getInt(null);

            Method createEncoder = opusType.getMethod(
                    "opus_encoder_create",
                    int.class,
                    int.class,
                    int.class,
                    IntBuffer.class
            );
            Method encode = opusType.getMethod(
                    "opus_encode",
                    pointerByReferenceType,
                    ShortBuffer.class,
                    int.class,
                    ByteBuffer.class,
                    int.class
            );
            encoder = createEncoder.invoke(
                    opus,
                    (int) format.getSampleRate(),
                    format.getChannels(),
                    applicationAudio,
                    error
            );
            Object encoderPointer = encoder == null
                    ? null
                    : pointerByReferenceType.getMethod("getValue").invoke(encoder);
            if (error.get(0) != success || encoderPointer == null) {
                throw new IllegalStateException("Opus encoder could not be created");
            }

            ByteBuffer encoded = ByteBuffer.allocateDirect(MAXIMUM_OPUS_PACKET_BYTES);
            int encodedBytes = (int) encode.invoke(
                    opus, encoder, nativePcm, samplesPerChannel, encoded, encoded.capacity()
            );
            if (encodedBytes <= 0) {
                throw new IllegalStateException("Opus encoder did not produce a packet");
            }
            return encodedBytes;
        } catch (InvocationTargetException exception) {
            throw new IllegalStateException("Opus encoder smoke failed", exception.getCause());
        } catch (ReflectiveOperationException | LinkageError exception) {
            throw new IllegalStateException("Opus encoder smoke could not access native bindings", exception);
        } finally {
            destroyEncoder(encoder);
        }
    }

    private static void destroyEncoder(Object encoder) {
        if (encoder == null) {
            return;
        }
        try {
            Class<?> opusType = Class.forName("tomp2p.opuswrapper.Opus");
            Class<?> pointerByReferenceType = Class.forName("com.sun.jna.ptr.PointerByReference");
            Object opus = opusType.getField("INSTANCE").get(null);
            opusType.getMethod("opus_encoder_destroy", pointerByReferenceType).invoke(opus, encoder);
        } catch (InvocationTargetException exception) {
            throw new IllegalStateException("Opus encoder could not be released", exception.getCause());
        } catch (ReflectiveOperationException | LinkageError exception) {
            throw new IllegalStateException("Opus encoder cleanup could not access native bindings", exception);
        }
    }

    private static void validateJdaPcmFormat(AudioFormat format) {
        if (format.getSampleRate() != 48_000.0f
                || format.getSampleSizeInBits() != 16
                || format.getChannels() != 2
                || !format.isBigEndian()
                || format.getEncoding() != AudioFormat.Encoding.PCM_SIGNED) {
            throw new IllegalStateException("Unexpected JDA PCM input format");
        }
    }
}
