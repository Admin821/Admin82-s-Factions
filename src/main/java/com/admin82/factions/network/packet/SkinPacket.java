package com.admin82.factions.network.packet;

import com.admin82.factions.AdminsFactions;
import net.minecraft.client.Minecraft;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server → Client, from {@code /faction skin}: switches the mod's look for this player between Modern (the default
 * models) and Medieval (the built-in "medieval" resource pack with the older models). Purely visual and per player;
 * the choice is kept with the player's resource pack settings.
 */
public record SkinPacket(int mode) implements CustomPacketPayload {

    public static final int TOGGLE = 0, MODERN = 1, MEDIEVAL = 2;

    public static final Type<SkinPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(AdminsFactions.MODID, "skin"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SkinPacket> STREAM_CODEC =
            ByteBufCodecs.VAR_INT.<RegistryFriendlyByteBuf>cast().map(SkinPacket::new, SkinPacket::mode);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(SkinPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (FMLEnvironment.dist == Dist.CLIENT) Client.apply(pkt.mode());
        });
    }

    @OnlyIn(Dist.CLIENT)
    private static final class Client {
        static void apply(int mode) {
            Minecraft mc = Minecraft.getInstance();
            var repo = mc.getResourcePackRepository();
            String id = repo.getAvailableIds().stream()
                    .filter(s -> s.contains(AdminsFactions.MODID) && s.contains("medieval")).findFirst().orElse(null);
            if (id == null) {
                say(mc, "§c[Skin] The medieval skin isn't available.");
                return;
            }
            boolean medieval = repo.getSelectedIds().contains(id);
            boolean want = mode == TOGGLE ? !medieval : mode == MEDIEVAL;
            if (want == medieval) {
                say(mc, "§7[Skin] You're already using the §f" + (medieval ? "Medieval" : "Modern") + "§7 skin.");
                return;
            }
            if (want) repo.addPack(id); else repo.removePack(id);
            mc.options.updateResourcePacks(repo); // saves the choice and reloads resources
            say(mc, "§a[Skin] Switched to the §f" + (want ? "Medieval" : "Modern") + "§a skin.");
        }

        private static void say(Minecraft mc, String text) {
            if (mc.player != null) mc.player.displayClientMessage(Component.literal(text), false);
        }
    }
}
