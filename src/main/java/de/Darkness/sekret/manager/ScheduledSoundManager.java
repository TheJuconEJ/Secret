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
import de.maxhenkel.voicechat.api.events.PlayerConnectedEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStoppedEvent;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;
import org.bukkit.command.CommandSender;
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

import static de.Darkness.sekret.util.PluginMessages.text;

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
    private BukkitTask registrationTask;
    private boolean registered;
    private ZoneId zone;
    private LocalTime time;
    private ZonedDateTime nextPlayback;

    public ScheduledSoundManager(SekretPlugin plugin) {
        this.plugin = plugin;
        this.soundFile = new File(plugin.getDataFolder(), "sounds/sound.mp3");
    }

    public void start() {
        reload();
        if (!registered) {
            registrationTask = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
                if (registerVoicechat() && registrationTask != null) {
                    registrationTask.cancel();
                    registrationTask = null;
                }
            }, 20L, 100L);
        }
    }

    private boolean registerVoicechat() {
        if (registered) {
            return true;
        }
        BukkitVoicechatService service = plugin.getServer().getServicesManager()
                .load(BukkitVoicechatService.class);
        if (service == null) {
            return false;
        }
        service.registerPlugin(this);
        registered = true;
        plugin.getLogger().info("Vision bei der Simple-Voice-Chat-Bukkit-Schnittstelle registriert.");
        return true;
    }

    public synchronized void reload() {
        stopTasks();
        if (!registerVoicechat()) {
            plugin.getLogger().warning("Keine Simple-Voice-Chat-Bukkit-Schnittstelle verfügbar. Eine Forge-/NeoForge-Installation allein stellt diese nicht zwingend bereit. /vision voicechat zeigt den Status.");
        }
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
        if (registrationTask != null) {
            registrationTask.cancel();
            registrationTask = null;
        }
        stopTasks();
        serverApi = null;
    }

    private void stopTasks() {
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
        registration.registerEvent(VoicechatServerStartedEvent.class, event -> {
            serverApi = event.getVoicechat();
            plugin.getLogger().info("Simple Voice Chat ist für die Musikwiedergabe bereit.");
        });
        registration.registerEvent(PlayerConnectedEvent.class, event -> serverApi = event.getVoicechat());
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

    public void sendStatus(CommandSender sender) {
        VoicechatServerApi api = serverApi;
        sender.sendMessage("§bVoice Chat §8| §f" + plugin.getServer().getName());
        sender.sendMessage(text(plugin, sender, "§7Bukkit-Schnittstelle registriert: ", "§7Bukkit API registered: ") + registered);
        sender.sendMessage(text(plugin, sender, "§7Voice-Chat-Server bereit: ", "§7Voice chat server ready: ") + (api != null));
        sender.sendMessage("§7MP3: §f" + soundFile + " §8| §7" + soundFile.isFile());
        sender.sendMessage(text(plugin, sender, "§7Musik aktiviert: ", "§7Music enabled: ")
                + plugin.getConfig().getBoolean("scheduled_sound.enabled", true));
        if (nextPlayback != null) {
            sender.sendMessage(text(plugin, sender, "§7Nächste Wiedergabe: §f", "§7Next playback: §f") + nextPlayback);
        }
        if (api != null) {
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                VoicechatConnection connection = api.getConnectionOf(player.getUniqueId());
                String state = connection == null ? "§c—" : connection.isDisabled() ? "§cOFF"
                        : connection.isConnected() ? "§aOK" : "§cUDP / Client";
                sender.sendMessage("§7" + player.getName() + " §8» " + state);
            }
        }
        if (!registered) {
            sender.sendMessage(text(plugin, sender,
                    "§eArclight: Die Mod allein genügt für diese Bukkit-Anbindung nicht. Nicht blind Mod und Plugin gleichzeitig installieren; zuerst die Voice-Chat-Serverlog prüfen.",
                    "§eArclight: The mod alone is not sufficient for this Bukkit integration. Do not blindly install both mod and plugin; check the voice chat server log first."));
        }
        sender.sendMessage(text(plugin, sender,
                "§7Bei UDP / Client: passenden Client-Mod, aktivierten Voice Chat und den konfigurierten UDP-Port prüfen (normalerweise 24454). Vision kann keinen gesperrten Port öffnen.",
                "§7For UDP / Client: check the matching client mod, enabled voice chat and configured UDP port (usually 24454). Vision cannot open a blocked port."));
    }

    public synchronized void testPlayback(CommandSender sender) {
        if (!plugin.getConfig().getBoolean("scheduled_sound.enabled", true) || serverApi == null || !soundFile.isFile()) {
            sendStatus(sender);
            sender.sendMessage(text(plugin, sender, "§cMusiktest nicht gestartet: siehe Status oben.", "§cMusic test not started: see status above."));
            return;
        }
        sender.sendMessage(text(plugin, sender, "§eMP3 wird geladen …", "§eLoading MP3 …"));
        beginPlayback(sender);
    }

    private synchronized void beginPlayback() {
        beginPlayback(null);
    }

    private synchronized void beginPlayback(CommandSender feedback) {
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
                if (plugin.isEnabled()) {
                    plugin.getServer().getScheduler().runTask(plugin, () -> playForEveryone(audio, request, feedback));
                }
            } catch (CancellationException ignored) {
            } catch (Exception exception) {
                if (generation == request) {
                    plugin.getLogger().log(Level.WARNING, "sound.mp3 konnte nicht abgespielt werden.", exception);
                    if (feedback != null && plugin.isEnabled()) {
                        plugin.getServer().getScheduler().runTask(plugin, () -> feedback.sendMessage(text(plugin, feedback,
                                "§cMP3 konnte nicht gelesen werden. Details stehen in der Serverkonsole.",
                                "§cCould not read MP3. See the server console for details.")));
                    }
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

    private synchronized void playForEveryone(short[] audio, int request, CommandSender feedback) {
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
        if (feedback != null) {
            feedback.sendMessage(text(plugin, feedback, "§7Musik gestartet für §f", "§7Music started for §f") + listeners
                    + text(plugin, feedback, "§7 verbundene Voice-Chat-Spieler.", "§7 connected voice chat players."));
        }
        if (listeners == 0) {
            plugin.getLogger().warning("Keine Zuhörer: Voice-Chat-Verbindung, Client-Mod, Lautstärke und UDP-Port prüfen. /vision voicechat zeigt Verbindungszustände.");
        }
    }

    private void stopPlayback() {
        for (AudioPlayer player : Set.copyOf(activePlayers)) {
            player.stopPlaying();
        }
        activePlayers.clear();
    }
}