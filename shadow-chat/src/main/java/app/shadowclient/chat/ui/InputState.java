package app.shadowclient.chat.ui;

import app.shadowclient.chat.relay.Messages.ServerEvent;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * In-memory state for the chat overlay.
 *
 * <p>Holds:
 * <ul>
 *   <li>Whether the overlay is visible at all.</li>
 *   <li>Per-channel message history (so switching channels shows the right log).</li>
 *   <li>Per-channel presence (who's currently in the room).</li>
 *   <li>The currently-active channel string.</li>
 *   <li>A transient "status" message (errors, connection state) shown inline.</li>
 * </ul>
 *
 * <p>Mutated from both the Minecraft client thread (UI events) and the
 * relay's HTTP executor (incoming frames), so all collections are
 * concurrent / synchronized.
 */
public final class InputState {

    /** A rendered chat line. Either a real message or an inline system/error notice. */
    public record DisplayLine(String name, String text, long ts, boolean error, boolean system) {
        public static DisplayLine chat(String name, String text, long ts) {
            return new DisplayLine(name, text, ts, false, false);
        }
        public static DisplayLine system(String text) {
            return new DisplayLine(null, text, System.currentTimeMillis(), false, true);
        }
        public static DisplayLine error(String text) {
            return new DisplayLine(null, text, System.currentTimeMillis(), true, true);
        }
    }

    /** How many lines we keep per channel before evicting oldest. */
    private static final int MAX_LINES_PER_CHANNEL = 200;

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());

    /** Backing buffer keyed by channel string. LinkedHashMap so iteration order is stable. */
    private final Map<String, Deque<DisplayLine>> linesByChannel = Collections.synchronizedMap(new LinkedHashMap<>());
    private final Map<String, List<ServerEvent.User>> presenceByChannel = Collections.synchronizedMap(new LinkedHashMap<>());
    /** Members (uuid + name) opted-in to voice, keyed by channel. Populated by voice:roster pushes. */
    private final Map<String, List<ServerEvent.User>> voiceRosterByChannel = Collections.synchronizedMap(new LinkedHashMap<>());
    /**
     * Unread chat-message count per channel. Incremented by
     * {@link #incrementUnread} when a real message lands on a channel
     * the user isn't currently reading — a non-active channel, or any
     * channel while the fullscreen chat screen is closed — and reset
     * to zero by {@link #clearUnread} when the channel becomes active
     * or the chat screen opens on it. System / error / presence
     * updates do NOT count — only actual chat lines from other people.
     */
    private final Map<String, Integer> unreadByChannel = Collections.synchronizedMap(new LinkedHashMap<>());

    private volatile boolean overlayVisible = false;
    private volatile String activeChannel = "server";

    /**
     * Local wall-clock time of the most recent {@link #append} on the
     * ACTIVE channel. Drives the HUD overlay's activity window: the
     * passive bottom-left panel only shows for a few seconds after
     * something new arrived, instead of squatting on the screen for
     * the rest of the session. Local receive time, not the line's own
     * ts — relay clocks can be skewed.
     */
    private volatile long lastActiveAppendAtMs = 0L;

    public boolean isOverlayVisible() { return overlayVisible; }
    public void setOverlayVisible(boolean v) { this.overlayVisible = v; }

    /** ms epoch of the last append to the active channel; 0 = never. */
    public long lastActiveAppendAtMs() { return lastActiveAppendAtMs; }

    public String activeChannel() { return activeChannel; }
    public void setActiveChannel(String c) {
        this.activeChannel = c;
        clearUnread(c);
    }

    /** Reset the unread counter for a channel — called when it becomes
     *  the active channel and when the chat screen opens onto it. */
    public void clearUnread(String channel) {
        if (channel != null) unreadByChannel.put(channel, 0);
    }

    /** Get the unread chat-message count for the given channel. Zero by default. */
    public int unreadFor(String channel) {
        Integer v = unreadByChannel.get(channel);
        return v == null ? 0 : v;
    }

    /**
     * Bump unread count for the given channel by one (capped at 99 for
     * display sanity). Skipped when {@code channel} is the active channel
     * AND {@code activeVisible} is true — the user has that log on screen
     * right now, so the message isn't "unread". Messages landing on the
     * active channel while the chat screen is closed DO count; they feed
     * the NEW-messages divider next time the screen opens.
     */
    public void incrementUnread(String channel, boolean activeVisible) {
        if (channel == null || (activeVisible && channel.equals(activeChannel))) return;
        unreadByChannel.merge(channel, 1, (a, b) -> Math.min(99, a + b));
    }

    /** Get a snapshot of the lines for the active channel (oldest first). */
    public List<DisplayLine> linesFor(String channel) {
        Deque<DisplayLine> q = linesByChannel.get(channel);
        if (q == null) return List.of();
        synchronized (q) {
            return new ArrayList<>(q);
        }
    }

    /** Get a snapshot of presence for the given channel. */
    public List<ServerEvent.User> presenceFor(String channel) {
        List<ServerEvent.User> p = presenceByChannel.get(channel);
        return p == null ? List.of() : new ArrayList<>(p);
    }

    /** Append a line to the named channel, evicting the oldest if over capacity. */
    public void append(String channel, DisplayLine line) {
        Deque<DisplayLine> q = linesByChannel.computeIfAbsent(channel,
                k -> new ConcurrentLinkedDeque<>());
        q.add(line);
        while (q.size() > MAX_LINES_PER_CHANNEL) q.pollFirst();
        // Wake the HUD overlay's activity window — only for the channel
        // the user would actually see in the passive panel.
        if (channel != null && channel.equals(activeChannel)) {
            lastActiveAppendAtMs = System.currentTimeMillis();
        }
    }

    public void setPresence(String channel, List<ServerEvent.User> users) {
        presenceByChannel.put(channel, users);
    }

    /** Snapshot of who's opted in to voice on the given channel. */
    public List<ServerEvent.User> voiceRosterFor(String channel) {
        List<ServerEvent.User> r = voiceRosterByChannel.get(channel);
        return r == null ? List.of() : new ArrayList<>(r);
    }

    public void setVoiceRoster(String channel, List<ServerEvent.User> members) {
        voiceRosterByChannel.put(channel, members);
    }

    /** Clear all history for a channel — used when leaving a group. */
    public void clear(String channel) {
        linesByChannel.remove(channel);
        presenceByChannel.remove(channel);
        voiceRosterByChannel.remove(channel);
        unreadByChannel.remove(channel);
    }

    public static String formatTimestamp(long ts) {
        return TIME_FMT.format(Instant.ofEpochMilli(ts));
    }
}
