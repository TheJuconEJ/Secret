package de.Darkness.sekret.manager;

import de.Darkness.sekret.SekretPlugin;
import de.maxhenkel.voicechat.api.BukkitVoicechatService;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.audiochannel.AudioPlayer;
import de.maxhenkel.voicechat.api.audiochannel.StaticAudioChannel;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStoppedEvent;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Level;

public final class ScheduledSoundManager implements VoicechatPlugin {

    private static final int SAMPLE_RATE = 48000;
    private static final int FRAME_SAMPLES = 960;

    private final SekretPlugin plugin;
    private final File soundFile;
    private final Set<AudioPlayer> activePlayers = ConcurrentHashMap.newKeySet();
    private volatile VoicechatServerApi serverApi;
    private volatile int generation;
    private BukkitTask clockTask;
    private BukkitTask decodeTask;
    private ZoneId zone;
    private LocalTime time;
    private ZonedDateTime nextPlayback;

    public ScheduledSoundManager(SekretPlugin plugin) {
        this.plugin = plugin;
        this.soundFile = new File(plugin.getDataFolder(), "sounds/sound.mp3");
    }

    public void start() {
        BukkitVoicechatService service = plugin.getServer().getServicesManager()
                .load(BukkitVoicechatService.class);
        if (service == null) {
            plugin.getLogger().severe("Simple Voice Chat ist nicht bereit. Zeitgesteuerte Musik ist deaktiviert.");
            return;
        }
        service.registerPlugin(this);
        reload();
    }

    public synchronized void reload() {
        shutdown();
        File directory = soundFile.getParentFile();
        if (!directory.isDirectory() && !directory.mkdirs()) {
            plugin.getLogger().severe("Der Sound-Ordner konnte nicht erstellt werden: " + directory);
            return;
        }
        if (!plugin.getConfig().getBoolean("scheduled_sound.enabled", true)) {
            return;
        }
        try {
            zone = ZoneId.of(plugin.getConfig().getString("scheduled_sound.timezone", "Europe/Berlin"));
            time = LocalTime.parse(plugin.getConfig().getString("scheduled_sound.time", "06:00"));
        } catch (DateTimeException exception) {
            plugin.getLogger().severe("Ungültige scheduled_sound.timezone oder time: " + exception.getMessage());
            return;
        }
        nextPlayback = findNextPlayback(Instant.now());
        clockTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::checkClock, 1L, 20L);
        plugin.getLogger().info("Musik täglich um " + time + " (" + zone + "). Datei: " + soundFile);
        if (!soundFile.isFile()) {
            plugin.getLogger().info("Lege deine MP3-Datei als sound.mp3 in den Ordner " + directory);
        }
    }

    public synchronized void shutdown() {
        generation++;
        if (clockTask != null) {
            clockTask.cancel();
            clockTask = null;
        }
        if (decodeTask != null) {
            decodeTask.cancel();
            decodeTask = null;
        }
        stopPlayback();
    }

    @Override
    public String getPluginId() {
        return "vision_scheduled_sound";
    }

    @Override
    public void initialize(VoicechatApi api) {
        if (api instanceof VoicechatServerApi voicechatServerApi) {
            serverApi = voicechatServerApi;
        }
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(VoicechatServerStartedEvent.class, event -> serverApi = event.getVoicechat());
        registration.registerEvent(VoicechatServerStoppedEvent.class, event -> onVoicechatStopped());
    }

    private synchronized void onVoicechatStopped() {
        serverApi = null;
        generation++;
        stopPlayback();
    }

    private ZonedDateTime findNextPlayback(Instant now) {
        LocalDate date = now.atZone(zone).toLocalDate();
        ZonedDateTime candidate = date.atTime(time).atZone(zone);
        if (!candidate.toInstant().isAfter(now)) {
            candidate = date.plusDays(1).atTime(time).atZone(zone);
        }
        return candidate;
    }

    private void checkClock() {
        Instant now = Instant.now();
        if (now.isBefore(nextPlayback.toInstant())) {
            return;
        }
        nextPlayback = findNextPlayback(now);
        beginPlayback();
    }

    public synchronized boolean testPlayback() {
        if (!plugin.getConfig().getBoolean("scheduled_sound.enabled", true) || serverApi == null || !soundFile.isFile()) {
            return false;
        }
        beginPlayback();
        return true;
    }

    private synchronized void beginPlayback() {
        if (serverApi == null) {
            plugin.getLogger().warning("Musik übersprungen: Simple Voice Chat ist nicht bereit.");
            return;
        }
        if (!soundFile.isFile()) {
            plugin.getLogger().warning("Musik übersprungen: Datei fehlt: " + soundFile);
            return;
        }
        int request = ++generation;
        if (decodeTask != null) {
            decodeTask.cancel();
        }
        stopPlayback();
        decodeTask = plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                short[] audio = decodeMp3(request);
                if (generation != request) {
                    return;
                }
                plugin.getServer().getScheduler().runTask(plugin, () -> playForEveryone(audio, request));
            } catch (CancellationException ignored) {
            } catch (Exception exception) {
                if (generation == request) {
                    plugin.getLogger().log(Level.WARNING, "sound.mp3 konnte nicht abgespielt werden.", exception);
                }
            }
        });
    }

    private short[] decodeMp3(int request) throws Exception {
        Class<?> bitstreamClass = Class.forName("javazoom.jl.decoder.Bitstream");
        Class<?> decoderClass = Class.forName("javazoom.jl.decoder.Decoder");
        Class<?> headerClass = Class.forName("javazoom.jl.decoder.Header");
        Class<?> sampleBufferClass = Class.forName("javazoom.jl.decoder.SampleBuffer");
        Method readFrame = bitstreamClass.getMethod("readFrame");
        Method closeFrame = bitstreamClass.getMethod("closeFrame");
        Method closeBitstream = bitstreamClass.getMethod("close");
        Method decodeFrame = decoderClass.getMethod("decodeFrame", headerClass, bitstreamClass);
        Method getSampleFrequency = sampleBufferClass.getMethod("getSampleFrequency");
        Method getChannelCount = sampleBufferClass.getMethod("getChannelCount");
        Method getBuffer = sampleBufferClass.getMethod("getBuffer");
        Method getBufferLength = sampleBufferClass.getMethod("getBufferLength");
        try (BufferedInputStream input = new BufferedInputStream(new FileInputStream(soundFile));
             ByteArrayOutputStream mono = new ByteArrayOutputStream()) {
            Object bitstream = bitstreamClass.getConstructor(InputStream.class).newInstance(input);
            Object decoder = decoderClass.getConstructor().newInstance();
            int sourceRate = 0;
            try {
                Object header;
                while ((header = readFrame.invoke(bitstream)) != null) {
                    if (generation != request || Thread.currentThread().isInterrupted()) {
                        throw new CancellationException();
                    }
                    try {
                        Object buffer = sampleBufferClass.cast(decodeFrame.invoke(decoder, header, bitstream));
                        int frequency = (Integer) getSampleFrequency.invoke(buffer);
                        if (sourceRate != 0 && sourceRate != frequency) {
                            throw new IOException("Die Abtastrate der MP3 ändert sich innerhalb der Datei.");
                        }
                        sourceRate = frequency;
                        int channels = (Integer) getChannelCount.invoke(buffer);
                        short[] samples = (short[]) getBuffer.invoke(buffer);
                        int bufferLength = (Integer) getBufferLength.invoke(buffer);
                        for (int offset = 0; offset < bufferLength; offset += channels) {
                            int sum = 0;
                            for (int channel = 0; channel < channels; channel++) {
                                sum += samples[offset + channel];
                            }
                            int sample = sum / channels;
                            mono.write(sample & 0xFF);
                            mono.write((sample >> 8) & 0xFF);
                        }
                    } finally {
                        closeFrame.invoke(bitstream);
                    }
                }
            } finally {
                closeBitstream.invoke(bitstream);
            }
            byte[] raw = mono.toByteArray();
            int sampleCount = raw.length / 2;
            if (sampleCount == 0 || sourceRate <= 0) {
                throw new IOException("Die Datei enthält keine lesbaren MP3-Audiodaten.");
            }
            double outputLength = Math.ceil(sampleCount * (double) SAMPLE_RATE / sourceRate);
            if (outputLength > Integer.MAX_VALUE - 8) {
                throw new IOException("Die MP3-Datei ist zu lang.");
            }
            short[] result = new short[(int) outputLength];
            for (int index = 0; index < result.length; index++) {
                if (index % SAMPLE_RATE == 0 && generation != request) {
                    throw new CancellationException();
                }
                double position = index * (double) sourceRate / SAMPLE_RATE;
                int left = Math.min((int) position, sampleCount - 1);
                int right = Math.min(left + 1, sampleCount - 1);
                double fraction = position - left;
                int a = (short) ((raw[left * 2] & 0xFF) | ((raw[left * 2 + 1] & 0xFF) << 8));
                int b = (short) ((raw[right * 2] & 0xFF) | ((raw[right * 2 + 1] & 0xFF) << 8));
                result[index] = (short) Math.round(a + (b - a) * fraction);
            }
            return result;
        }
    }

    private synchronized void playForEveryone(short[] audio, int request) {
        VoicechatServerApi api = serverApi;
        if (generation != request || api == null || !plugin.isEnabled()) {
            return;
        }
        int listeners = 0;
        for (Player target : plugin.getServer().getOnlinePlayers()) {
            VoicechatConnection connection = api.getConnectionOf(target.getUniqueId());
            if (connection == null || !connection.isConnected() || connection.isDisabled()) {
                continue;
            }
            OpusEncoder encoder = null;
            try {
                StaticAudioChannel channel = api.createStaticAudioChannel(UUID.randomUUID(),
                        api.fromServerLevel(target.getWorld()), connection);
                if (channel == null) {
                    continue;
                }
                encoder = api.createEncoder();
                OpusEncoder playerEncoder = encoder;
                Supplier<short[]> frames = new Supplier<>() {
                    private int cursor;

                    @Override
                    public short[] get() {
                        if (generation != request || cursor >= audio.length) {
                            return null;
                        }
                        short[] frame = Arrays.copyOfRange(audio, cursor, cursor + FRAME_SAMPLES);
                        cursor += FRAME_SAMPLES;
                        return frame;
                    }
                };
                AudioPlayer player = api.createAudioPlayer(channel, encoder, frames);
                player.setOnStopped(() -> {
                    activePlayers.remove(player);
                    playerEncoder.close();
                });
                activePlayers.add(player);
                try {
                    player.startPlaying();
                } catch (RuntimeException exception) {
                    activePlayers.remove(player);
                    throw exception;
                }
                listeners++;
            } catch (RuntimeException exception) {
                if (encoder != null && !encoder.isClosed()) {
                    encoder.close();
                }
                plugin.getLogger().log(Level.WARNING, "Musik für " + target.getName() + " konnte nicht starten.", exception);
            }
        }
        plugin.getLogger().info("sound.mp3 gestartet für " + listeners + " verbundene Voice-Chat-Spieler.");
    }

    private void stopPlayback() {
        for (AudioPlayer player : Set.copyOf(activePlayers)) {
            player.stopPlaying();
        }
        activePlayers.clear();
    }
}