/*
 * This source file is part of BetterModel.
 * Copyright (c) 2025 toxicity188
 * Licensed under the MIT License.
 * See LICENSE.md file for full license text.
 */

package kr.toxicity.model.api.skin;

import kr.toxicity.model.api.armor.PlayerArmor;
import kr.toxicity.model.api.profile.ModelProfile;
import kr.toxicity.model.api.util.TransformedItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * Skin data of player.
 */
public interface SkinData {

    /**
     * Gets the material protocol used by this skin's vanilla-avatar items.
     * Version 1 uses 16 by 16 material-marker textures (RGB FC-F9-FB/FD/FC for observer,
     * owner and hands) and one UV texel per model pixel,
     * including outer-layer inflation. The matching item shader derives continuous display
     * scale from these UV metrics without modifying skin colors. Version 0 is unsupported.
     * <pre>{@code
     * if (skin.vanillaAvatarProtocol() == 1) {
     *     var parts = skin.vanillaParts(0x7f, true);
     * }
     * }</pre>
     *
     * @return the avatar material protocol version, or 0 when unsupported
     * @since 3.5.1
     */
    default int vanillaAvatarProtocol() {
        return 0;
    }

    /**
     * Creates the six rigid, unarmored parts of a vanilla-shaped avatar.
     * Geometry is expressed in blocks around the native part pivot, with +X to the player's right,
     * +Y up and -Z forward (native X and Y are negated),
     * before the native player's 0.9375 render scale. Use item display transform NONE with its
     * intrinsic half-turn cancelled. Keys are head, body, right_arm, left_arm, right_leg, left_leg.
     * Requires the matching ChronoCore item shader for the avatar texture markers.
     * The caller owns animation, visibility, and packet-only mounting; this never disguises a player.
     * <pre>{@code
     * var parts = skin.vanillaParts(0x7f, false);
     * var head = parts.get("head");
     * }</pre>
     *
     * @param skinParts vanilla skin customization bitmask
     * @param cameraOwner whether to use the owner camera-clearance texture marker; requires the
     *                    matching ChronoCore item shader's camera-distance policy
     * @return immutable part map, or an empty map when this skin implementation lacks avatar support
     * @since 3.5.1
     */
    default @NotNull Map<String, TransformedItemStack> vanillaParts(int skinParts, boolean cameraOwner) {
        return Map.of();
    }

    /**
     * Creates owner-only arm items with the inverse camera-clearance texture marker.
     * Requires the matching ChronoCore item shader; the caller must restrict their audience.
     * Geometry and coordinate conventions match {@link #vanillaParts(int, boolean)}.
     * <pre>{@code var arms = skin.firstPersonArms(0x7f); }</pre>
     * @param skinParts vanilla skin customization bitmask
     * @return right_arm and left_arm items, or an empty map when unsupported
     * @since 3.5.1
     */
    default @NotNull Map<String, TransformedItemStack> firstPersonArms(int skinParts) {
        return Map.of();
    }

    /**
     * Gets model skin
     * @return skin
     */
    @NotNull ModelProfile profile();

    /**
     * Gets head part
     * @param armor armor
     * @return head
     */
    @NotNull TransformedItemStack head(@NotNull PlayerArmor armor);

    /**
     * Gets hip part
     * @param armor armor
     * @return hip
     */
    @NotNull TransformedItemStack hip(@NotNull PlayerArmor armor);

    /**
     * Gets waist part
     * @param armor armor
     * @return waist
     */
    @NotNull TransformedItemStack waist(@NotNull PlayerArmor armor);

    /**
     * Gets chest part
     * @param armor armor
     * @return chest
     */
    @NotNull TransformedItemStack chest(@NotNull PlayerArmor armor);

    /**
     * Gets left arm part
     * @param armor armor
     * @return left arm
     */
    @NotNull TransformedItemStack leftArm(@NotNull PlayerArmor armor);

    /**
     * Gets left forearm part
     * @return left forearm
     */
    @NotNull TransformedItemStack leftForeArm();

    /**
     * Gets right arm part
     * @param armor armor
     * @return right arm
     */
    @NotNull TransformedItemStack rightArm(@NotNull PlayerArmor armor);

    /**
     * Gets right forearm part
     * @return right forearm
     */
    @NotNull TransformedItemStack rightForeArm();

    /**
     * Gets left leg part
     * @param armor armor
     * @return left leg
     */
    @NotNull TransformedItemStack leftLeg(@NotNull PlayerArmor armor);

    /**
     * Gets left foreleg part
     * @param armor armor
     * @return left foreleg
     */
    @NotNull TransformedItemStack leftForeLeg(@NotNull PlayerArmor armor);

    /**
     * Gets right leg part
     * @param armor armor
     * @return right leg
     */
    @NotNull TransformedItemStack rightLeg(@NotNull PlayerArmor armor);

    /**
     * Gets right foreleg part
     * @param armor armor
     * @return right foreleg
     */
    @NotNull TransformedItemStack rightForeLeg(@NotNull PlayerArmor armor);

    /**
     * Gets cape
     * @param armor armor
     * @return cape
     */
    @Nullable TransformedItemStack cape(@NotNull PlayerArmor armor);
}
