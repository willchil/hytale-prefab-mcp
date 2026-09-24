package games.crescentnetwork.mcp.command;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.protocol.packets.interface_.Page;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The page {@code /prefab-mcp} opens, showing the connection details in fields a player can select
 * and copy.
 *
 * <p>Chat has no way to select text, and there is no clipboard packet in the protocol, so a page with
 * text fields is the only way to hand someone a long token without making them retype it. The fields
 * are inputs purely so they can be selected; nothing is ever read back from them.
 *
 * <p>Read-only by nature rather than by flag: the page sends no event bindings, so whatever a player
 * types locally goes nowhere and re-running the command restores it.
 */
public final class PrefabMcpPage extends InteractiveCustomUIPage<PrefabMcpPage.PageEventData> {

    /** Relative to {@code Common/UI/Custom/} in this plugin's asset pack. */
    private static final String LAYOUT = "PrefabMcpPage.ui";

    private final String instructions;
    private final String configurationJson;
    @Nullable
    private final String token;

    /**
     * @param instructions      what to do with the configuration, which depends on whether the
     *                          server is local only
     * @param configurationJson the pretty-printed client configuration
     * @param token             the player's personal token, or null when the server requires none
     */
    public PrefabMcpPage(@Nonnull PlayerRef playerRef, @Nonnull String instructions,
                         @Nonnull String configurationJson, @Nullable String token) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, PageEventData.CODEC);
        this.instructions = instructions;
        this.configurationJson = configurationJson;
        this.token = token;
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder commandBuilder,
                      @Nonnull UIEventBuilder eventBuilder, @Nonnull Store<EntityStore> store) {
        commandBuilder.append(LAYOUT);
        commandBuilder.set("#Instructions.Text", instructions);
        commandBuilder.set("#Config.Value", configurationJson);

        // The token section is hidden rather than left blank on a server that does not require one,
        // so the page shows only what is actually needed.
        boolean hasToken = token != null;
        commandBuilder.set("#TokenSection.Visible", hasToken);
        if (hasToken) {
            commandBuilder.set("#Token.Value", token);
        }

        eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#CloseButton");
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
                                @Nonnull PageEventData data) {
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player != null) {
            player.getPageManager().setPage(ref, store, Page.None);
        }
    }

    public static final class PageEventData {
        public static final BuilderCodec<PageEventData> CODEC = BuilderCodec
            .builder(PageEventData.class, PageEventData::new)
            .build();
    }
}
