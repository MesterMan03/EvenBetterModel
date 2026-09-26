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
     * Gets this skin's resolved texture as a 64 by 64 RGBA PNG, without another download.
     * Legacy skins have mirrored left limbs and native legacy hat transparency; base-layer
     * pixels are opaque, while modern outer-layer alpha is preserved. These are the same
     * normalized pixels used to build this skin's model colors. Each call returns a copy.
     * <pre>{@code
     * var png = skin.skinTexturePng();
     * if (png != null) { Files.write(texturePath, png); }
     * }</pre>
     *
     * @return a normalized PNG, or null when this implementation does not expose its texture
     * @throws java.io.UncheckedIOException if the PNG cannot be encoded
     * @throws IllegalStateException if no PNG encoder is available
     * @since 3.5.1
     */
    default @Nullable byte[] skinTexturePng() {
        return null;
    }

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
     * Gets the supported rigid avatar-armor protocol. Version 1 uses the same geometry coordinates
     * and material markers as {@link #vanillaParts(int, boolean)}. Availability of a particular
     * equipment asset is checked by {@link #vanillaArmor(String, String, Integer, String, String, boolean)}.
     * Version 2 additionally supports the explicit-opacity overload, with opaque and 40% variants.
     * <pre>{@code boolean supported = skin.vanillaArmorProtocol() >= 1; }</pre>
     * @return the supported protocol version, or 0 when unavailable
     * @since 3.5.1
     */
    default int vanillaArmorProtocol() {
        return 0;
    }

    /**
     * Creates rigid armor for one equipment slot, keyed by its avatar bones. Head supplies head;
     * chest supplies body and both arms; legs supplies body and both legs; feet supplies both legs.
     * Asset, pattern and material identifiers must include their namespace. Only supported vanilla
     * equipment textures and trims are accepted; unknown or unavailable assets return null.
     * The caller keeps these separate from skin and other equipment slots, applies the original
     * item's glint, and hides native armor only after every worn slot has been reproduced.
     * <pre>{@code
     * var armor = skin.vanillaArmor("chest", "minecraft:iron", null, "minecraft:coast", "minecraft:gold", false);
     * if (armor != null) { var chest = armor.get("body"); }
     * }</pre>
     * @param slot head, chest, legs or feet
     * @param assetId namespaced equipment asset identifier
     * @param dyedColor optional 24-bit RGB color; null retains the equipment's undyed color
     * @param trimPattern namespaced trim pattern identifier, or null when untrimmed
     * @param trimMaterial namespaced trim material identifier, or null when untrimmed
     * @param cameraOwner whether the armor uses the owner camera-clearance marker
     * @return immutable bone map, or null when the request cannot be reproduced faithfully
     * @since 3.5.1
     */
    default @Nullable Map<String, TransformedItemStack> vanillaArmor(
            @NotNull String slot, @NotNull String assetId, @Nullable Integer dyedColor,
            @Nullable String trimPattern, @Nullable String trimMaterial, boolean cameraOwner
    ) {
        return null;
    }

    /**
     * Creates rigid armor with an explicit opacity, keeping the source cutout holes and colors.
     * Geometry and slot keys match {@link #vanillaArmor(String, String, Integer, String, String, boolean)}.
     * Opacity 1 is fully opaque and 0.4 is translucent; other values return null. The matching
     * ChronoCore shader must preserve marker texture alpha when adding enchantment glint.
     * <pre>{@code var armor = skin.vanillaArmor("feet", "minecraft:iron", null, null, null, false, 0.4F); }</pre>
     * @param slot head, chest, legs or feet
     * @param assetId namespaced equipment asset identifier
     * @param dyedColor optional 24-bit RGB dye
     * @param trimPattern optional namespaced trim pattern
     * @param trimMaterial optional namespaced trim material
     * @param cameraOwner whether to use the owner camera-clearance marker
     * @param opacity supported opacity, either 1 or 0.4
     * @return immutable bone map, or null when unsupported
     * @since 3.5.1
     */
    default @Nullable Map<String, TransformedItemStack> vanillaArmor(
            @NotNull String slot, @NotNull String assetId, @Nullable Integer dyedColor,
            @Nullable String trimPattern, @Nullable String trimMaterial, boolean cameraOwner, float opacity
    ) {
        return opacity == 1F ? vanillaArmor(slot, assetId, dyedColor, trimPattern, trimMaterial, cameraOwner) : null;
    }

    /**
     * Gets the supported segmented animation-armor protocol. Version 1 uses avatar material markers,
     * metric UVs and the segmented player animation bone pivots, with opacity 1 or 0.4.
     * <pre>{@code boolean supported = skin.animationArmorProtocol() == 1; }</pre>
     * @return 1 when segmented animation armor is available, otherwise 0
     * @since 3.5.1
     */
    default int animationArmorProtocol() {
        return 0;
    }

    /**
     * Creates equipment for the segmented animation rig. Head supplies head; chest supplies chest,
     * waist, hip, both arms and forearms; legs supplies chest, waist, hip, both legs and forelegs;
     * feet supplies both legs and forelegs. Callers attach each item to its named animation bone
     * and apply per-slot glint. Unsupported equipment or opacity returns null.
     * <pre>{@code var armor = skin.animationArmor("head", "minecraft:iron", null, null, null, false, 1F); }</pre>
     * @param slot head, chest, legs or feet
     * @param assetId namespaced equipment asset identifier
     * @param dyedColor optional 24-bit RGB dye
     * @param trimPattern optional namespaced trim pattern
     * @param trimMaterial optional namespaced trim material
     * @param cameraOwner whether to use the owner camera-clearance marker
     * @param opacity supported opacity, either 1 or 0.4
     * @return immutable animation bone map, or null when unsupported
     * @since 3.5.1
     */
    default @Nullable Map<String, TransformedItemStack> animationArmor(
            @NotNull String slot, @NotNull String assetId, @Nullable Integer dyedColor,
            @Nullable String trimPattern, @Nullable String trimMaterial, boolean cameraOwner, float opacity
    ) {
        return null;
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
