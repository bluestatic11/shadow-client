package app.shadowclient.chat.ui;

import app.shadowclient.chat.ShadowChatClient;
import app.shadowclient.chat.cmd.CoordsHelper;
import app.shadowclient.chat.config.AuthConfig;
import app.shadowclient.chat.config.ModConfig;
import app.shadowclient.chat.relay.Messages.ServerEvent;
import app.shadowclient.chat.voice.VoiceController;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Fullscreen Discord-style chat screen.
 *
 * <p>Takes over the whole MC window when the user opens chat: a left
 * server rail, a channel/voice sidebar, and a main message area with an
 * input pill at the bottom. Click a channel row to switch, click
 * "+ Create group" to spawn one, click "Coords" to paste the player's
 * X/Y/Z into the buffer.
 *
 * <p>The HUD-mode {@link ChatOverlay} is suppressed while this screen
 * is open ({@code onRender} checks {@code mc.screen instanceof DiscordChatScreen}).
 *
 * <h2>1.21.11 API note</h2>
 * Uses {@link KeyEvent} / {@link CharacterEvent} / {@link MouseButtonEvent}
 * record forms — the older {@code (int,int,int)} signatures were removed.
 */
public final class DiscordChatScreen extends Screen {

    // ---- Layout (GUI-scaled px) ----
    private static final int SERVER_RAIL_W = 56;
    private static final int SIDEBAR_W = 220;
    private static final int HEADER_H = 36;
    private static final int INPUT_H = 36;
    private static final int USERBAR_H = 48;
    private static final int CHAN_ROW_H = 20;
    private static final int CATEGORY_H = 18;
    private static final int PILL_SIZE = 44;

    // ---- Colors (ARGB) ----
    private static final int FILM = 0xE0000000;
    private static final int SERVER_RAIL_BG = 0xFF15171A;
    private static final int SIDEBAR_BG = 0xFF1F2125;
    private static final int MAIN_BG = 0xFF2A2C32;
    private static final int HEADER_BG = 0xFF34373F;
    private static final int INPUT_BG = 0xFF1F2125;
    private static final int CHIP_ACTIVE = 0xFF404249;
    private static final int CHIP_HOVER = 0xFF323439;
    private static final int TEXT_DIM = 0xFF8E9197;
    private static final int TEXT = 0xFFDADBDE;
    private static final int TEXT_BRIGHT = 0xFFFFFFFF;
    private static final int ACCENT = 0xFF7AA8E0;
    private static final int RED_PILL = 0xFFEC4747;
    private static final int GREEN = 0xFF4ADE80;
    private static final int DIVIDER = 0xFF34373F;
    private static final int COORDS_HIGHLIGHT = 0x402D5A8C;

    // Stable colors keyed off speaker-name hash so different people stand out.
    private static final int[] NAME_COLORS = {
            0xFFFF6B6B, 0xFFFFA94D, 0xFFFFD43B, 0xFFA9E34B,
            0xFF63E6BE, 0xFF4DABF7, 0xFFB197FC, 0xFFE599F7,
    };

    private static final DateTimeFormatter HHMMSS =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private static final int MAX_INPUT = 512;

    private final ChatOverlay overlayLegacy; // unused — kept to preserve old signature compat
    private final StringBuilder buffer = new StringBuilder();

    /** Hit-test rectangle for a clickable channel row. */
    private record ChannelHit(int x1, int y1, int x2, int y2, String channelKey) {}

    private final List<ChannelHit> channelHits = new ArrayList<>();
    private int coordsBtnX1, coordsBtnY1, coordsBtnX2, coordsBtnY2;
    private int createGroupX1, createGroupY1, createGroupX2, createGroupY2;
    private int voiceToggleX1, voiceToggleY1, voiceToggleX2, voiceToggleY2;
    private int copyIdBtnX1, copyIdBtnY1, copyIdBtnX2, copyIdBtnY2;
    /** Set when the Copy ID button was clicked — paints "Copied!" for ~1s. */
    private long copiedFlashUntil = 0;
    /**
     * How many message-lines back from the newest the log is scrolled.
     * Zero means "stuck to the bottom showing the latest". Bumped by
     * mouse wheel up; clamped against the log's length on render.
     */
    private int scrollOffset = 0;
    /** Channel the scroll offset is currently anchored to — reset when it changes. */
    private String scrollAnchorChannel = null;

    public DiscordChatScreen(ChatOverlay legacyOverlay) {
        super(Component.literal("Shadow Chat"));
        this.overlayLegacy = legacyOverlay;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ============================================================ render

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        // Dim the world behind. We don't call super.render() because the
        // default Screen renderer paints either a vanilla dirt background
        // (when no level) or its own dim which fights with our colors.
        gfx.fill(0, 0, this.width, this.height, FILM);

        channelHits.clear();
        coordsBtnX2 = 0;
        createGroupX2 = 0;
        voiceToggleX2 = 0;
        copyIdBtnX2 = 0;

        drawServerRail(gfx, 0, 0, SERVER_RAIL_W, this.height);
        drawSidebar(gfx, SERVER_RAIL_W, 0, SIDEBAR_W, this.height, mouseX, mouseY);

        int mainX = SERVER_RAIL_W + SIDEBAR_W;
        drawMain(gfx, mainX, 0, this.width - mainX, this.height, mouseX, mouseY);
    }

    // ------------------------------------------------------ server rail

    private void drawServerRail(GuiGraphics gfx, int x, int y, int w, int h) {
        gfx.fill(x, y, x + w, y + h, SERVER_RAIL_BG);
        int pillX = x + (w - PILL_SIZE) / 2;
        int pillY = y + 14;
        // Active-channel indicator strip (left edge).
        gfx.fill(x, pillY + 6, x + 3, pillY + PILL_SIZE - 6, TEXT_BRIGHT);
        // Red "S" pill — Shadow Client brand badge.
        gfx.fill(pillX, pillY, pillX + PILL_SIZE, pillY + PILL_SIZE, RED_PILL);
        String letter = "S";
        int lw = this.font.width(letter);
        gfx.drawString(this.font, letter,
                pillX + (PILL_SIZE - lw) / 2,
                pillY + (PILL_SIZE - this.font.lineHeight) / 2 + 1,
                TEXT_BRIGHT, false);
    }

    // ------------------------------------------------------ sidebar

    private void drawSidebar(GuiGraphics gfx, int x, int y, int w, int h, int mouseX, int mouseY) {
        gfx.fill(x, y, x + w, y + h, SIDEBAR_BG);

        ShadowChatClient sc = ShadowChatClient.get();
        InputState st = sc.uiState();
        ModConfig cfg = sc.modConfig();

        // -- branding header --
        int rowY = y + 12;
        gfx.drawString(this.font, "SHADOW CHAT",
                x + 12, rowY, TEXT_BRIGHT, false);
        rowY += this.font.lineHeight + 6;
        gfx.fill(x + 10, rowY, x + w - 10, rowY + 1, DIVIDER);
        rowY += 10;

        String activeChannel = st.activeChannel();

        // -- SERVER --
        rowY = drawCategoryLabel(gfx, x, rowY, "SERVER");
        String serverLabel = sc.currentServerHost();
        boolean serverOk = !serverLabel.isEmpty();
        String displayedServer = serverOk ? serverLabel : "(not connected)";
        rowY = drawChannelRow(gfx, x, rowY, w, "# " + displayedServer,
                serverOk ? ModConfig.CHANNEL_SERVER : null,
                ModConfig.CHANNEL_SERVER.equals(activeChannel),
                mouseX, mouseY, st.unreadFor(ModConfig.CHANNEL_SERVER), serverOk);
        rowY += 6;

        // -- GROUPS --
        rowY = drawCategoryLabel(gfx, x, rowY, "GROUPS");
        for (ModConfig.Group g : new ArrayList<>(cfg.joinedGroups())) {
            String key = "group:" + g.id;
            String name = (g.name == null || g.name.isBlank()) ? shortId(g.id) : g.name;
            rowY = drawChannelRow(gfx, x, rowY, w, "# " + name, key,
                    key.equals(activeChannel), mouseX, mouseY,
                    st.unreadFor(key), true);
        }
        rowY = drawCreateGroupRow(gfx, x, rowY, w, mouseX, mouseY);
        rowY += 6;

        // -- VOICE --
        rowY = drawCategoryLabel(gfx, x, rowY, "VOICE");
        VoiceController vc = sc.voice();
        List<UUID> speakers = (vc != null) ? vc.playback().currentSpeakers() : List.of();
        int rosterSize = st.voiceRosterFor(activeChannel).size();
        rowY = drawVoiceToggleRow(gfx, x, rowY, w, sc.isInVoice(), rosterSize,
                mouseX, mouseY);
        for (UUID id : speakers) {
            String name = sc.displayNameForUuid(id);
            rowY = drawSpeakerRow(gfx, x, rowY, w, name);
        }

        // -- user info bar pinned to bottom --
        drawUserBar(gfx, x, y + h - USERBAR_H, w, USERBAR_H);
    }

    private int drawCategoryLabel(GuiGraphics gfx, int x, int y, String label) {
        gfx.drawString(this.font, label, x + 12, y + 4, TEXT_DIM, false);
        return y + CATEGORY_H;
    }

    /**
     * Draw a channel row with optional highlight (active) / hover / unread badge.
     * Returns y-after-row so callers can stack rows.
     *
     * @param channelKey  if non-null, registers a click hit-test for switching
     * @param unreadBadge unread message count; rendered as a red pill on the right when > 0
     * @param enabled     when false, the row is dimmed and not clickable
     */
    private int drawChannelRow(GuiGraphics gfx, int x, int y, int w, String label,
                               String channelKey, boolean active,
                               int mouseX, int mouseY, int unreadBadge, boolean enabled) {
        int rowX = x + 6;
        int rowW = w - 12;
        boolean hover = enabled && channelKey != null
                && mouseX >= rowX && mouseX <= rowX + rowW
                && mouseY >= y && mouseY <= y + CHAN_ROW_H;
        int bg = active ? CHIP_ACTIVE : (hover ? CHIP_HOVER : 0);
        if (bg != 0) gfx.fill(rowX, y, rowX + rowW, y + CHAN_ROW_H, bg);
        // Rows with unread messages get brighter label text so the eye
        // catches them even before the pill registers.
        int color = !enabled ? TEXT_DIM
                : (active ? TEXT_BRIGHT
                : (unreadBadge > 0 ? TEXT_BRIGHT : TEXT));
        gfx.drawString(this.font, label,
                rowX + 8, y + (CHAN_ROW_H - this.font.lineHeight) / 2 + 1,
                color, false);
        if (unreadBadge > 0) {
            String txt = unreadBadge >= 99 ? "99+" : String.valueOf(unreadBadge);
            int tw = this.font.width(txt) + 8;
            int badgeX = rowX + rowW - tw - 4;
            gfx.fill(badgeX, y + 4, badgeX + tw, y + CHAN_ROW_H - 4, RED_PILL);
            gfx.drawString(this.font, txt,
                    badgeX + 4, y + (CHAN_ROW_H - this.font.lineHeight) / 2 + 1,
                    TEXT_BRIGHT, false);
        }
        if (channelKey != null && enabled) {
            channelHits.add(new ChannelHit(rowX, y, rowX + rowW, y + CHAN_ROW_H, channelKey));
        }
        return y + CHAN_ROW_H;
    }

    private int drawCreateGroupRow(GuiGraphics gfx, int x, int y, int w, int mouseX, int mouseY) {
        int rowX = x + 6;
        int rowW = w - 12;
        boolean hover = mouseX >= rowX && mouseX <= rowX + rowW
                && mouseY >= y && mouseY <= y + CHAN_ROW_H;
        if (hover) gfx.fill(rowX, y, rowX + rowW, y + CHAN_ROW_H, CHIP_HOVER);
        gfx.drawString(this.font, "+ Create group",
                rowX + 8, y + (CHAN_ROW_H - this.font.lineHeight) / 2 + 1,
                hover ? TEXT_BRIGHT : TEXT_DIM, false);
        createGroupX1 = rowX; createGroupY1 = y;
        createGroupX2 = rowX + rowW; createGroupY2 = y + CHAN_ROW_H;
        return y + CHAN_ROW_H;
    }

    /**
     * Render the Join/Leave Voice button. When opted-in we paint it
     * green-ish; when out we paint it dim. Click hit-tested in
     * {@link #mouseClicked}.
     */
    private int drawVoiceToggleRow(GuiGraphics gfx, int x, int y, int w,
                                   boolean inVoice, int rosterSize,
                                   int mouseX, int mouseY) {
        int rowX = x + 6;
        int rowW = w - 12;
        int btnH = CHAN_ROW_H + 4;
        boolean hover = mouseX >= rowX && mouseX <= rowX + rowW
                && mouseY >= y && mouseY <= y + btnH;
        int bg = inVoice
                ? (hover ? 0xFFC23A3A : RED_PILL)
                : (hover ? 0xFF3C8C46 : 0xFF2A6638);
        gfx.fill(rowX, y, rowX + rowW, y + btnH, bg);
        String label = inVoice ? "Leave voice" : "Join voice";
        if (rosterSize > 0) label = label + "  (" + rosterSize + " in)";
        gfx.drawString(this.font, label,
                rowX + 10, y + (btnH - this.font.lineHeight) / 2 + 1,
                TEXT_BRIGHT, false);
        voiceToggleX1 = rowX; voiceToggleY1 = y;
        voiceToggleX2 = rowX + rowW; voiceToggleY2 = y + btnH;
        return y + btnH;
    }

    private int drawSpeakerRow(GuiGraphics gfx, int x, int y, int w, String name) {
        int rowX = x + 18;
        int dotX = rowX + 4;
        int dotY = y + CHAN_ROW_H / 2;
        // Green speaking dot.
        gfx.fill(dotX - 3, dotY - 3, dotX + 3, dotY + 3, GREEN);
        gfx.drawString(this.font, name,
                rowX + 14, y + (CHAN_ROW_H - this.font.lineHeight) / 2 + 1,
                TEXT, false);
        return y + CHAN_ROW_H;
    }

    private void drawUserBar(GuiGraphics gfx, int x, int y, int w, int h) {
        gfx.fill(x, y, x + w, y + h, SERVER_RAIL_BG);
        ShadowChatClient sc = ShadowChatClient.get();
        AuthConfig auth = sc.authConfig();
        String displayName = auth.isUsable() ? auth.name() : "Not signed in";
        char initial = (displayName != null && !displayName.isEmpty())
                ? Character.toUpperCase(displayName.charAt(0)) : '?';
        // Avatar
        int avSize = 28;
        int avX = x + 8;
        int avY = y + (h - avSize) / 2;
        gfx.fill(avX, avY, avX + avSize, avY + avSize,
                NAME_COLORS[Math.floorMod((displayName == null ? 0 : displayName.hashCode()), NAME_COLORS.length)]);
        String letter = String.valueOf(initial);
        int lw = this.font.width(letter);
        gfx.drawString(this.font, letter,
                avX + (avSize - lw) / 2,
                avY + (avSize - this.font.lineHeight) / 2 + 1,
                TEXT_BRIGHT, false);
        // Name + status
        gfx.drawString(this.font, displayName,
                avX + avSize + 8, avY + 3, TEXT_BRIGHT, false);
        String status = auth.isUsable() ? "Online" : "Sign in via launcher";
        gfx.drawString(this.font, status,
                avX + avSize + 8, avY + 3 + this.font.lineHeight + 2,
                auth.isUsable() ? GREEN : TEXT_DIM, false);
    }

    // ------------------------------------------------------ main area

    private void drawMain(GuiGraphics gfx, int x, int y, int w, int h, int mouseX, int mouseY) {
        gfx.fill(x, y, x + w, y + h, MAIN_BG);

        ShadowChatClient sc = ShadowChatClient.get();
        InputState st = sc.uiState();
        String activeChannel = st.activeChannel();

        // ---- header ----
        gfx.fill(x, y, x + w, y + HEADER_H, HEADER_BG);
        String headerLabel = "# " + channelDisplayName(activeChannel);
        int labelY = y + (HEADER_H - this.font.lineHeight) / 2 + 1;
        gfx.drawString(this.font, headerLabel, x + 12, labelY, TEXT_BRIGHT, false);
        int cursor = x + 12 + this.font.width(headerLabel);
        int presence = presenceCount(st, activeChannel);
        if (presence > 0) {
            String count = presence + " online";
            cursor += 12;
            gfx.drawString(this.font, count, cursor, labelY, TEXT_DIM, false);
            cursor += this.font.width(count);
        }

        // Group ID chip + Copy button on group channels — makes invitation
        // a one-click affair instead of fishing through the chat history.
        if (activeChannel != null && activeChannel.startsWith("group:")) {
            String id = activeChannel.substring("group:".length());
            String idShort = id.length() > 8 ? id.substring(0, 8) + "…" : id;
            cursor += 16;
            gfx.drawString(this.font, "ID  " + idShort, cursor, labelY, TEXT_DIM, false);
            cursor += this.font.width("ID  " + idShort) + 8;
            // Copy button — fixed width chip.
            boolean copied = System.currentTimeMillis() < copiedFlashUntil;
            String btn = copied ? "Copied" : "Copy ID";
            int btnW = this.font.width(btn) + 10;
            int btnX1 = cursor;
            int btnY1 = y + 6;
            int btnX2 = cursor + btnW;
            int btnY2 = y + HEADER_H - 6;
            gfx.fill(btnX1, btnY1, btnX2, btnY2, copied ? GREEN : ACCENT);
            gfx.drawString(this.font, btn,
                    btnX1 + 5, btnY1 + ((btnY2 - btnY1) - this.font.lineHeight) / 2 + 1,
                    TEXT_BRIGHT, false);
            copyIdBtnX1 = btnX1; copyIdBtnY1 = btnY1;
            copyIdBtnX2 = btnX2; copyIdBtnY2 = btnY2;
        }

        // Status line on the right.
        String status = sc.statusLine();
        if (status != null && !status.isBlank()) {
            int statusW = this.font.width(status);
            gfx.drawString(this.font, status,
                    x + w - statusW - 12, labelY, TEXT_DIM, false);
        }
        gfx.fill(x, y + HEADER_H - 1, x + w, y + HEADER_H, DIVIDER);

        // Empty state — replaces the input + log when the launcher hasn't
        // written usable auth yet. Most first-time users will see this.
        if (!sc.authConfig().isUsable()) {
            drawSignInCard(gfx, x, y + HEADER_H, w, h - HEADER_H);
            return;
        }

        // ---- input row (bottom) ----
        drawInput(gfx, x, y + h - INPUT_H - 8, w, INPUT_H);

        // ---- message log (fills middle) ----
        int logTop = y + HEADER_H + 4;
        int logBottom = y + h - INPUT_H - 12;
        drawMessages(gfx, x, logTop, w, logBottom - logTop, st, activeChannel);
    }

    /** Centered empty-state card: "Sign in via the launcher to chat". */
    private void drawSignInCard(GuiGraphics gfx, int x, int y, int w, int h) {
        int cardW = Math.min(420, w - 80);
        int cardH = 150;
        int cardX = x + (w - cardW) / 2;
        int cardY = y + (h - cardH) / 2;
        gfx.fill(cardX, cardY, cardX + cardW, cardY + cardH, SIDEBAR_BG);
        // Brand-blue accent strip on the left edge.
        gfx.fill(cardX, cardY, cardX + 4, cardY + cardH, ACCENT);

        int textX = cardX + 24;
        int lineY = cardY + 22;
        gfx.drawString(this.font, "Sign in to chat", textX, lineY, TEXT_BRIGHT, false);
        lineY += this.font.lineHeight + 10;
        gfx.drawString(this.font, "Shadow Chat needs your Microsoft account",
                textX, lineY, TEXT, false);
        lineY += this.font.lineHeight + 4;
        gfx.drawString(this.font, "to verify who you are when sending messages.",
                textX, lineY, TEXT, false);
        lineY += this.font.lineHeight + 12;
        gfx.drawString(this.font, "1. Open the Shadow Client launcher",
                textX, lineY, TEXT_DIM, false);
        lineY += this.font.lineHeight + 2;
        gfx.drawString(this.font, "2. Click Sign in with Microsoft",
                textX, lineY, TEXT_DIM, false);
        lineY += this.font.lineHeight + 2;
        gfx.drawString(this.font, "3. Launch this profile again",
                textX, lineY, TEXT_DIM, false);
    }

    private void drawMessages(GuiGraphics gfx, int x, int y, int w, int h,
                              InputState st, String activeChannel) {
        int lineH = this.font.lineHeight + 4;
        int maxLines = Math.max(0, h / lineH);
        if (maxLines == 0) return;
        // Reset scroll if user switched channels — different log, different
        // bottom. Sticking to the previous offset would land us mid-log.
        if (!activeChannel.equals(scrollAnchorChannel)) {
            scrollOffset = 0;
            scrollAnchorChannel = activeChannel;
        }
        List<InputState.DisplayLine> lines = st.linesFor(activeChannel);
        // Clamp scrollOffset against current log size so it can't sit past
        // the oldest message after eviction.
        int maxScroll = Math.max(0, lines.size() - maxLines);
        if (scrollOffset > maxScroll) scrollOffset = maxScroll;
        if (scrollOffset < 0) scrollOffset = 0;
        int endExclusive = lines.size() - scrollOffset;
        int start = Math.max(0, endExclusive - maxLines);
        int visible = endExclusive - start;
        int drawY = y + Math.max(0, h - visible * lineH);
        int textMaxRight = x + w - 16;
        for (int i = start; i < endExclusive; i++) {
            InputState.DisplayLine line = lines.get(i);
            drawMessageLine(gfx, x + 12, drawY, textMaxRight, line);
            drawY += lineH;
        }
        // "Scrolled up — jump to latest" hint when we're not anchored to bottom.
        if (scrollOffset > 0) {
            String hint = "↑ Scrolled up " + scrollOffset + " line"
                    + (scrollOffset == 1 ? "" : "s")
                    + " — scroll down to follow";
            int hw = this.font.width(hint);
            gfx.fill(x + w - hw - 24, y + h - this.font.lineHeight - 10,
                     x + w - 8, y + h - 4, 0xC0202329);
            gfx.drawString(this.font, hint,
                    x + w - hw - 16, y + h - this.font.lineHeight - 6,
                    ACCENT, false);
        }
    }

    /** Render one message line: timestamp + colored name + » + text (or system/error variant). */
    private void drawMessageLine(GuiGraphics gfx, int x, int y, int xRight,
                                 InputState.DisplayLine line) {
        String ts = HHMMSS.format(Instant.ofEpochMilli(line.ts()));
        int cursor = x;
        gfx.drawString(this.font, ts, cursor, y, TEXT_DIM, false);
        cursor += this.font.width(ts) + 6;
        if (line.error()) {
            gfx.drawString(this.font, line.text(), cursor, y, RED_PILL, false);
            return;
        }
        if (line.system()) {
            gfx.drawString(this.font, line.text(), cursor, y, TEXT_DIM, false);
            return;
        }
        String name = line.name() == null ? "?" : line.name();
        String sep = " » ";
        boolean isCoords = looksLikeCoords(line.text());
        if (isCoords) {
            int rightEdge = Math.min(xRight,
                    cursor + this.font.width(name) + this.font.width(sep) + this.font.width(line.text()) + 6);
            gfx.fill(cursor - 2, y - 1, rightEdge + 2, y + this.font.lineHeight + 1, COORDS_HIGHLIGHT);
        }
        gfx.drawString(this.font, name, cursor, y, nameColor(name), false);
        cursor += this.font.width(name);
        gfx.drawString(this.font, sep, cursor, y, TEXT_DIM, false);
        cursor += this.font.width(sep);
        // Truncate body so we don't paint past xRight.
        String text = line.text();
        if (cursor + this.font.width(text) > xRight) {
            while (text.length() > 1 && cursor + this.font.width(text + "...") > xRight) {
                text = text.substring(0, text.length() - 1);
            }
            text = text + "...";
        }
        gfx.drawString(this.font, text, cursor, y, isCoords ? ACCENT : TEXT_BRIGHT, false);
    }

    private void drawInput(GuiGraphics gfx, int x, int y, int w, int h) {
        int padX = 12;
        int boxX1 = x + padX;
        int boxX2 = x + w - padX;
        gfx.fill(boxX1, y, boxX2, y + h, INPUT_BG);

        // "+" pill on far left of input
        int plusW = 24;
        int plusX1 = boxX1 + 4;
        int plusX2 = plusX1 + plusW;
        int plusY1 = y + 6;
        int plusY2 = y + h - 6;
        gfx.fill(plusX1, plusY1, plusX2, plusY2, CHIP_HOVER);
        int plusTextW = this.font.width("+");
        gfx.drawString(this.font, "+",
                plusX1 + (plusW - plusTextW) / 2,
                plusY1 + ((plusY2 - plusY1) - this.font.lineHeight) / 2 + 1,
                TEXT, false);

        // Coords button — right side
        String btnLabel = "Coords";
        int btnW = this.font.width(btnLabel) + 14;
        int btnX2 = boxX2 - 6;
        int btnX1 = btnX2 - btnW;
        int btnY1 = y + 6;
        int btnY2 = y + h - 6;
        boolean coordsEnabled = CoordsHelper.hasPlayer();
        int btnBg = coordsEnabled ? ACCENT : 0xFF2A2A2A;
        int btnFg = coordsEnabled ? TEXT_BRIGHT : TEXT_DIM;
        gfx.fill(btnX1, btnY1, btnX2, btnY2, btnBg);
        gfx.drawString(this.font, btnLabel,
                btnX1 + 7,
                btnY1 + ((btnY2 - btnY1) - this.font.lineHeight) / 2 + 1,
                btnFg, false);
        if (coordsEnabled) {
            coordsBtnX1 = btnX1; coordsBtnY1 = btnY1;
            coordsBtnX2 = btnX2; coordsBtnY2 = btnY2;
        }

        // Text caret area between plus and coords.
        int textX1 = plusX2 + 8;
        int textX2 = btnX1 - 8;
        String placeholder = "Message #" + channelDisplayName(ShadowChatClient.get().uiState().activeChannel()) + "...";
        String shown = buffer.length() == 0 ? placeholder : buffer.toString();
        int color = buffer.length() == 0 ? TEXT_DIM : TEXT_BRIGHT;
        // Truncate if it would overflow the input box.
        if (this.font.width(shown) > textX2 - textX1) {
            while (shown.length() > 1 && this.font.width(shown + "...") > textX2 - textX1) {
                shown = shown.substring(0, shown.length() - 1);
            }
            shown = shown + "...";
        }
        gfx.drawString(this.font, shown,
                textX1, y + (h - this.font.lineHeight) / 2 + 1, color, false);
        // Blinking caret when we have a buffer.
        if (buffer.length() > 0 && (System.currentTimeMillis() % 1000 < 500)) {
            int caretX = textX1 + Math.min(this.font.width(buffer.toString()), textX2 - textX1 - 2);
            gfx.fill(caretX, y + 8, caretX + 1, y + h - 8, TEXT_BRIGHT);
        } else if (buffer.length() == 0 && (System.currentTimeMillis() % 1000 < 500)) {
            int caretX = textX1;
            gfx.fill(caretX, y + 8, caretX + 1, y + h - 8, TEXT_DIM);
        }
    }

    // ============================================================ input

    @Override
    public boolean keyPressed(KeyEvent event) {
        int keyCode = event.key();
        int modifiers = event.modifiers();
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            String text = buffer.toString().trim();
            buffer.setLength(0);
            if (!text.isEmpty()) {
                ShadowChatClient.get().submitInput(text);
            }
            this.onClose();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
            if (buffer.length() > 0) buffer.setLength(buffer.length() - 1);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            buffer.setLength(0);
            this.onClose();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_V && (modifiers & GLFW.GLFW_MOD_CONTROL) != 0) {
            if (this.minecraft != null) {
                String clip = this.minecraft.keyboardHandler.getClipboard();
                if (clip != null) appendSafe(clip.replace('\n', ' ').replace('\r', ' '));
            }
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        // Three lines per notch — feels about right vs Discord's smoother
        // pixel scrolling.
        int delta = (int) Math.signum(scrollY) * 3;
        // scrollY positive = wheel up = look further back in history.
        scrollOffset += delta;
        if (scrollOffset < 0) scrollOffset = 0;
        return true;
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        int cp = event.codepoint();
        if (cp >= 32 && cp != 127) {
            appendSafe(new String(Character.toChars(cp)));
            return true;
        }
        return super.charTyped(event);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() != 0) return super.mouseClicked(event, doubleClick);
        double mx = event.x();
        double my = event.y();

        // Coords paste button.
        if (coordsBtnX2 > 0
                && mx >= coordsBtnX1 && mx <= coordsBtnX2
                && my >= coordsBtnY1 && my <= coordsBtnY2) {
            CoordsHelper.currentCoords().ifPresent(coords -> {
                if (buffer.length() > 0 && buffer.charAt(buffer.length() - 1) != ' ') appendSafe(" ");
                appendSafe(coords);
            });
            return true;
        }

        // "+ Create group" — pops a default-named group and switches to it.
        if (createGroupX2 > 0
                && mx >= createGroupX1 && mx <= createGroupX2
                && my >= createGroupY1 && my <= createGroupY2) {
            ShadowChatClient.get().createGroupFromUi();
            return true;
        }

        // Voice toggle button.
        if (voiceToggleX2 > 0
                && mx >= voiceToggleX1 && mx <= voiceToggleX2
                && my >= voiceToggleY1 && my <= voiceToggleY2) {
            ShadowChatClient.get().toggleVoiceOptIn();
            return true;
        }

        // Copy group ID button — pulls the active channel's group UUID
        // onto the system clipboard and flashes "Copied" for ~1.2s.
        if (copyIdBtnX2 > 0
                && mx >= copyIdBtnX1 && mx <= copyIdBtnX2
                && my >= copyIdBtnY1 && my <= copyIdBtnY2) {
            String ch = ShadowChatClient.get().uiState().activeChannel();
            if (ch != null && ch.startsWith("group:") && this.minecraft != null) {
                this.minecraft.keyboardHandler.setClipboard(ch.substring("group:".length()));
                copiedFlashUntil = System.currentTimeMillis() + 1200;
            }
            return true;
        }

        // Channel rows.
        for (ChannelHit hit : channelHits) {
            if (mx >= hit.x1 && mx <= hit.x2 && my >= hit.y1 && my <= hit.y2) {
                ShadowChatClient.get().switchChannel(hit.channelKey);
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    // ============================================================ helpers

    private void appendSafe(String s) {
        int room = MAX_INPUT - buffer.length();
        if (room <= 0) return;
        if (s.length() > room) s = s.substring(0, room);
        buffer.append(s);
    }

    private static int nameColor(String name) {
        return NAME_COLORS[Math.floorMod(name == null ? 0 : name.hashCode(), NAME_COLORS.length)];
    }

    private static String shortId(String id) {
        if (id == null) return "?";
        return id.length() > 6 ? id.substring(0, 6) : id;
    }

    private static int presenceCount(InputState st, String channel) {
        List<ServerEvent.User> p = st.presenceFor(channel);
        return p == null ? 0 : p.size();
    }

    /** Human-friendly channel name for the main header / input placeholder. */
    private static String channelDisplayName(String channel) {
        if (channel == null) return "?";
        if (ModConfig.CHANNEL_SERVER.equals(channel)) {
            String host = ShadowChatClient.get().currentServerHost();
            return host.isEmpty() ? "server" : host;
        }
        if (channel.startsWith("group:")) {
            ModConfig.Group g = ShadowChatClient.get().modConfig().findGroup(channel.substring("group:".length()));
            if (g != null && g.name != null && !g.name.isBlank()) return g.name;
            return shortId(channel.substring("group:".length()));
        }
        return channel;
    }

    /**
     * Cheap heuristic — was this line emitted from the coords-paste path?
     * Used to give the line a brand-blue highlight in the message list.
     */
    private static boolean looksLikeCoords(String text) {
        if (text == null) return false;
        return text.startsWith("X: ") && text.contains(" Y: ") && text.contains(" Z: ");
    }
}
