package studio.dreamys;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.session.Session;
import net.minecraft.text.Text;
import studio.dreamys.gui.SessionScreen;

public class TokenAuthClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {

        // ── After a screen initializes, inject the "Session" button ──────────
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (!(screen instanceof MultiplayerScreen)) return;

            // "Session" button — bottom-left area, matching the original mod's placement
            ButtonWidget sessionButton = ButtonWidget.builder(
                    Text.literal("Session"),
                    btn -> client.setScreen(new SessionScreen(screen))
            )
                    .position(scaledWidth / 2 - 154, scaledHeight - 52)
                    .size(100, 20)
                    .build();

            Screens.getButtons(screen).add(sessionButton);

            // ── After the screen renders, draw username + UUID overlay ────────
            ScreenEvents.afterRender(screen).register((s, drawContext, mouseX, mouseY, tickDelta) -> {
                Session session = client.getSession();
                String username = session.getUsername();
                String uuid = session.getUuidOrNull() != null
                        ? session.getUuidOrNull().toString()
                        : "offline";
                String info = "User: §a" + username + " §rUUID: §a" + uuid;
                drawContext.drawText(client.textRenderer, info, 2, 2, 0xFFFFFF, false);
            });
        });
    }
}
