package de.tante.tntcart;

import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ArrowItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;

/**
 * Clientseitiges Makro: Flame-Bogen spannen, loslassen, Schiene und TNT-Lore setzen.
 */
public class TntCartClient implements ClientModInitializer {
	private enum Phase { IDLE, DRAW, RAIL, CART }

	private static final double MAX_REACH = 4.0;
	private static final int DRAW_TICKS = 5;
	private static final int DRAW_TIMEOUT = 80;

	private KeyMapping fireKey;

	private Phase phase = Phase.IDLE;
	private int ticks;
	private int bowSlot;
	private int railSlot;
	private int cartSlot;
	private int originalSlot;
	private BlockPos railPos;

	@Override
	public void onInitializeClient() {
		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("tntcart", "main"));
		fireKey = KeyMappingHelper.registerKeyMapping(
				new KeyMapping("key.tntcart.fire", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_Y, category));

		ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
	}

	private void onTick(Minecraft mc) {
		LocalPlayer player = mc.player;
		if (player == null || mc.gameMode == null || mc.level == null) {
			phase = Phase.IDLE;
			return;
		}

		while (fireKey.consumeClick()) {
			if (phase == Phase.IDLE) {
				start(mc, player);
			}
		}

		switch (phase) {
			case DRAW -> {
				ticks++;
				if (player.getTicksUsingItem() >= DRAW_TICKS) {
					mc.gameMode.releaseUsingItem(player);
					phase = Phase.RAIL;
				} else if (ticks > DRAW_TIMEOUT) {
					abort(player, "Bogen konnte nicht gespannt werden.");
				}
			}
			case RAIL -> {
				player.getInventory().setSelectedSlot(railSlot);
				BlockPos below = railPos.below();
				BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(below).add(0, 0.5, 0), Direction.UP, below, false);
				mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
				phase = Phase.CART;
			}
			case CART -> {
				player.getInventory().setSelectedSlot(cartSlot);
				BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(railPos), Direction.UP, railPos, false);
				mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
				player.getInventory().setSelectedSlot(originalSlot);
				player.sendSystemMessage(Component.literal("TNT Cart: Combo ausgeloest!"));
				phase = Phase.IDLE;
			}
			default -> { }
		}
	}

	private void start(Minecraft mc, LocalPlayer player) {
		Inventory inv = player.getInventory();
		bowSlot = -1;
		railSlot = -1;
		cartSlot = -1;

		for (int i = 0; i < 9; i++) {
			ItemStack stack = inv.getItem(i);
			if (stack.isEmpty()) continue;
			if (bowSlot < 0 && stack.is(Items.BOW) && hasEnchant(stack, true)) bowSlot = i;
			else if (railSlot < 0 && stack.getItem() instanceof BlockItem bi && bi.getBlock() instanceof BaseRailBlock) railSlot = i;
			else if (cartSlot < 0 && stack.is(Items.TNT_MINECART)) cartSlot = i;
		}

		if (bowSlot < 0) { msg(player, "Kein Bogen mit Flame in der Hotbar."); return; }
		if (railSlot < 0) { msg(player, "Keine Schiene in der Hotbar."); return; }
		if (cartSlot < 0) { msg(player, "Keine TNT-Lore in der Hotbar."); return; }

		if (!player.hasInfiniteMaterials() && !hasEnchant(inv.getItem(bowSlot), false) && !hasArrow(inv)) {
			msg(player, "Keine Pfeile im Inventar.");
			return;
		}

		if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
			msg(player, "Schau auf einen Block (max. 4 Bloecke entfernt).");
			return;
		}
		if (hit.getDirection() != Direction.UP) {
			msg(player, "Schau auf die Oberseite eines Blocks.");
			return;
		}
		if (player.getEyePosition().distanceTo(hit.getLocation()) > MAX_REACH) {
			msg(player, "Block ist zu weit weg (max. 4 Bloecke).");
			return;
		}

		railPos = hit.getBlockPos().above();
		// railPos.below() == getroffener Block; RAIL-Phase platziert dort auf die Oberseite
		originalSlot = inv.getSelectedSlot();
		inv.setSelectedSlot(bowSlot);
		mc.gameMode.useItem(player, InteractionHand.MAIN_HAND);
		ticks = 0;
		phase = Phase.DRAW;
	}

	/** flame=true: Flame pruefen; flame=false: Infinity pruefen. */
	private static boolean hasEnchant(ItemStack stack, boolean flame) {
		for (var holder : stack.getEnchantments().keySet()) {
			if (flame ? holder.is(Enchantments.FLAME) : holder.is(Enchantments.INFINITY)) return true;
		}
		return false;
	}

	private static boolean hasArrow(Inventory inv) {
		for (int i = 0; i < inv.getContainerSize(); i++) {
			if (inv.getItem(i).getItem() instanceof ArrowItem) return true;
		}
		return false;
	}

	private void abort(LocalPlayer player, String text) {
		player.getInventory().setSelectedSlot(originalSlot);
		phase = Phase.IDLE;
		msg(player, text);
	}

	private static void msg(LocalPlayer player, String text) {
		player.sendSystemMessage(Component.literal("TNT Cart: " + text));
	}
}
