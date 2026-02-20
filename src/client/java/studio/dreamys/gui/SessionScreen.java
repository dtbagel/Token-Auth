package studio.dreamys.gui;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.session.Session;
import net.minecraft.text.Text;
import studio.dreamys.TokenAuth;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Optional;
import java.util.UUID;

/**
 * A custom screen that lets the player enter a new Minecraft access token
 * to swap their in-game session — or restore the original session.
 *
 * Ported from TokenAuth (studio.dreamys, Forge 1.12.x) → Fabric 1.21.10.
 * Uses Yarn mappings (1.21.10+build.2).
 */
public class SessionScreen extends Screen {

    private final Screen parent;
    private TextFieldWidget tokenField;
    private volatile String status = "";

    public SessionScreen(Screen parent) {
        super(Text.literal("Change Session"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        // Token input field
        tokenField = new TextFieldWidget(
                this.textRenderer,
                this.width / 2 - 150,
                this.height / 2 - 10,
                300, 20,
                Text.literal("Access Token")
        );
        tokenField.setMaxLength(2048);
        tokenField.setPlaceholder(Text.literal("Paste your access token here..."));
        this.addSelectableChild(tokenField);
        this.setInitialFocus(tokenField);

        // Login button
        this.addDrawableChild(ButtonWidget.builder(
                Text.literal("Login"),
                btn -> attemptLogin()
        ).position(this.width / 2 - 154, this.height / 2 + 16).size(100, 20).build());

        // Copy Token button — copies the current session's access token to clipboard
        this.addDrawableChild(ButtonWidget.builder(
                Text.literal("Copy Token"),
                btn -> copyCurrentToken()
        ).position(this.width / 2 - 50, this.height / 2 + 16).size(100, 20).build());

        // Restore button
        this.addDrawableChild(ButtonWidget.builder(
                Text.literal("Restore"),
                btn -> restoreSession()
        ).position(this.width / 2 + 54, this.height / 2 + 16).size(100, 20).build());

        // Back button
        this.addDrawableChild(ButtonWidget.builder(
                Text.literal("Back"),
                btn -> this.close()
        ).position(this.width / 2 - 75, this.height / 2 + 42).size(150, 20).build());
    }

    // ── Login logic ──────────────────────────────────────────────────────────

    private void attemptLogin() {
        String token = tokenField.getText().trim();

        if (token.isEmpty()) {
            status = "§cPlease enter a token.";
            return;
        }
        // Strip "Bearer " prefix if the user pasted the full header value
        if (token.toLowerCase().startsWith("bearer ")) {
            token = token.substring(7).trim();
        }

        status = "§eValidating token…";
        final String finalToken = token;

        Thread t = new Thread(() -> {
            try {
                MinecraftClient client = MinecraftClient.getInstance();

                // Save the original session on the first swap
                if (TokenAuth.originalSession == null) {
                    Session s = client.getSession();
                    TokenAuth.originalSession = new String[]{
                            s.getUsername(),
                            s.getUuidOrNull() != null ? s.getUuidOrNull().toString() : "",
                            s.getAccessToken(),
                            s.getXuid().orElse(""),
                            s.getClientId().orElse("")
                    };
                }

                // Validate token against Minecraft Services API
                URL url = new URL("https://api.minecraftservices.com/minecraft/profile");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Authorization", "Bearer " + finalToken);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.connect();

                int code = conn.getResponseCode();
                if (code != 200) {
                    status = "§cInvalid token (HTTP " + code + ")";
                    return;
                }

                InputStream stream = conn.getInputStream();
                JsonObject json = JsonParser.parseReader(new InputStreamReader(stream)).getAsJsonObject();

                String name = json.get("name").getAsString();
                String rawUuid = json.get("id").getAsString();
                // Insert dashes into the UUID returned by the API (it comes without them)
                String formattedUuid = rawUuid.replaceFirst(
                        "(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}+)",
                        "$1-$2-$3-$4-$5"
                );

                // Build and inject a new Session on the main thread
                client.execute(() -> {
                    try {
                        Session newSession = new Session(
                                name,
                                UUID.fromString(formattedUuid),
                                finalToken,
                                Optional.empty(),                   // xuid
                                Optional.empty(),                   // clientId
                                Session.AccountType.MSA
                        );
                        setSessionViaReflection(client, newSession);
                        status = "§aLogged in as: " + name;
                    } catch (Exception e) {
                        e.printStackTrace();
                        status = "§cError: Couldn't set session (check mc logs)";
                    }
                });

            } catch (Exception e) {
                e.printStackTrace();
                status = "§cError: " + e.getMessage();
            }
        }, "TokenAuth-Login");

        t.setDaemon(true);
        t.start();
    }

    // ── Restore logic ────────────────────────────────────────────────────────

    private void restoreSession() {
        if (TokenAuth.originalSession == null) {
            status = "§cNo original session saved.";
            return;
        }

        try {
            String[] s = TokenAuth.originalSession;
            Session restored = new Session(
                    s[0],                                                           // username
                    s[1].isEmpty() ? new UUID(0, 0) : UUID.fromString(s[1]),       // uuid
                    s[2],                                                           // accessToken
                    s[3].isEmpty() ? Optional.empty() : Optional.of(s[3]),         // xuid
                    s[4].isEmpty() ? Optional.empty() : Optional.of(s[4]),         // clientId
                    Session.AccountType.MSA                                        // accountType
            );
            setSessionViaReflection(MinecraftClient.getInstance(), restored);
            status = "§aRestored session: " + s[0];
            TokenAuth.originalSession = null;
        } catch (Exception e) {
            e.printStackTrace();
            status = "§cError: Couldn't restore session (check mc logs)";
        }
    }

    // ── Copy token logic ─────────────────────────────────────────────────────

    private void copyCurrentToken() {
        String token = MinecraftClient.getInstance().getSession().getAccessToken();
        if (token == null || token.isEmpty()) {
            status = "§cNo token available to copy.";
            return;
        }
        MinecraftClient.getInstance().keyboard.setClipboard(token);
        status = "§aToken copied to clipboard!";
    }

    // ── Reflection helper ────────────────────────────────────────────────────

    /**
     * Injects a new {@link Session} into MinecraftClient's private {@code session} field.
     * Yarn mapping: MinecraftClient#session (net.minecraft.session.Session)
     */
    private static void setSessionViaReflection(MinecraftClient client, Session session)
            throws NoSuchFieldException, IllegalAccessException {
        Field f = MinecraftClient.class.getDeclaredField("session");
        f.setAccessible(true);
        f.set(client, session);
    }

    // ── Rendering ────────────────────────────────────────────────────────────

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        // Translucent background
        this.renderBackground(context, mouseX, mouseY, delta);

        // Title
        context.drawCenteredTextWithShadow(this.textRenderer, this.title,
                this.width / 2, this.height / 2 - 50, 0xFFFFFF);

        // Current session info
        MinecraftClient client = MinecraftClient.getInstance();
        Session cur = client.getSession();
        String curUuid = cur.getUuidOrNull() != null ? cur.getUuidOrNull().toString() : "offline";
        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("Current: §a" + cur.getUsername() + "§r (" + curUuid + ")"),
                this.width / 2, this.height / 2 - 35, 0xCCCCCC);

        // Field label
        context.drawText(this.textRenderer, Text.literal("Access Token:"),
                this.width / 2 - 150, this.height / 2 - 22, 0xA0A0A0, false);

        // Widgets (buttons + text field)
        super.render(context, mouseX, mouseY, delta);

        // Draw text field separately (it's a selectable child, not a drawable child)
        tokenField.render(context, mouseX, mouseY, delta);

        // Status message
        if (!status.isEmpty()) {
            context.drawCenteredTextWithShadow(this.textRenderer,
                    Text.literal(status), this.width / 2, this.height / 2 + 68, 0xFFFFFF);
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void close() {
        this.client.setScreen(parent);
    }
}
