package io.github.izakyl.folkways.plugins.person;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.EnumSet;
import java.util.Locale;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.armortrim.ArmorTrim;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.ClientHooks;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.renderer.layer.GeoRenderLayer;

// A replaced-entity animatable is not a LivingEntity, so GeckoLib's ItemArmorGeoLayer cannot read its
// slots. Read the real resident instead and retain NeoForge's generic model hook (used by straw hats).
@OnlyIn(Dist.CLIENT)
final class ResidentGeoArmorLayer extends GeoRenderLayer<ResidentGeoBackend.Crew> {
    private final ResidentGeoBackend backend;
    private final HumanoidModel<ResidentEntity> inner;
    private final HumanoidModel<ResidentEntity> outer;
    private final ModelManager models;
    private final EnumSet<Part> drawn = EnumSet.noneOf(Part.class);

    ResidentGeoArmorLayer(ResidentGeoBackend backend, EntityRendererProvider.Context context) {
        super(backend);
        this.backend = backend;
        inner = new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR));
        outer = new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR));
        models = context.getModelManager();
    }

    @Override
    public void preRender(PoseStack pose, ResidentGeoBackend.Crew crew, BakedGeoModel baked,
            RenderType renderType, MultiBufferSource buffers, VertexConsumer buffer, float partialTick,
            int light, int overlay) {
        drawn.clear();
    }

    @Override
    public void renderForBone(PoseStack pose, ResidentGeoBackend.Crew crew, GeoBone bone,
            RenderType renderType, MultiBufferSource buffers, VertexConsumer buffer, float partialTick,
            int light, int overlay) {
        ResidentEntity resident = backend.getCurrentEntity();
        Part part = Part.of(bone.getName());
        if (resident == null || part == null || bone.isHidden() || !drawn.add(part)) {
            return;
        }
        for (EquipmentSlot slot : part.slots) {
            ItemStack stack = resident.getItemBySlot(slot);
            if (!(stack.getItem() instanceof ArmorItem armor) || armor.getEquipmentSlot() != slot) {
                continue;
            }
            HumanoidModel<ResidentEntity> original = slot == EquipmentSlot.LEGS ? inner : outer;
            original.setAllVisible(false);
            ModelPart anchor = part.in(original);
            anchor.loadPose(anchor.getInitialPose());
            anchor.setPos(0.0F, 0.0F, 0.0F);
            anchor.setRotation(0.0F, 0.0F, 0.0F);
            anchor.visible = true;
            if (part == Part.HEAD) {
                original.hat.copyFrom(anchor);
                original.hat.visible = true;
            }
            original.young = resident.isBaby();
            original.crouching = resident.isCrouching();
            pose.pushPose();
            try {
                // The layer is already inside this bone's animated transform. Move to its pivot and
                // change from Gecko's upward Y axis to the humanoid model's downward Y axis once.
                pose.translate(bone.getPivotX() / 16.0F, bone.getPivotY() / 16.0F, bone.getPivotZ() / 16.0F);
                pose.scale(-1.0F, -1.0F, 1.0F);
                if (!bone.getCubes().isEmpty()) {
                    Vec3 size = bone.getCubes().getFirst().size();
                    pose.scale(ratio(size.x, part.width), ratio(size.y, part.height), ratio(size.z, part.depth));
                }
                draw(pose, buffers, resident, stack, armor, slot, original, partialTick, light);
            } finally {
                pose.popPose();
            }
        }
        // Layer buffers can invalidate the bone renderer's active consumer.
        if (renderType != null) {
            buffers.getBuffer(renderType);
        }
    }

    private void draw(PoseStack pose, MultiBufferSource buffers, ResidentEntity resident, ItemStack stack,
            ArmorItem armor, EquipmentSlot slot, HumanoidModel<ResidentEntity> original,
            float partialTick, int light) {
        Model model = ClientHooks.getArmorModel(resident, stack, slot, original);
        IClientItemExtensions extensions = IClientItemExtensions.of(stack);
        extensions.setupModelAnimations(resident, stack, slot, model, 0.0F, 0.0F, partialTick,
            resident.tickCount + partialTick, 0.0F, 0.0F);
        ArmorMaterial material = armor.getMaterial().value();
        int fallback = extensions.getDefaultDyeColor(stack);
        for (int i = 0; i < material.layers().size(); i++) {
            ArmorMaterial.Layer layer = material.layers().get(i);
            int tint = extensions.getArmorLayerTintColor(stack, resident, layer, i, fallback);
            if (tint != 0) {
                var texture = ClientHooks.getArmorTexture(resident, stack, layer, slot == EquipmentSlot.LEGS, slot);
                model.renderToBuffer(pose, buffers.getBuffer(RenderType.armorCutoutNoCull(texture)),
                    light, OverlayTexture.NO_OVERLAY, tint);
            }
        }
        ArmorTrim trim = stack.get(DataComponents.TRIM);
        if (trim != null) {
            var texture = slot == EquipmentSlot.LEGS
                ? trim.innerTexture(armor.getMaterial()) : trim.outerTexture(armor.getMaterial());
            var sprite = models.getAtlas(Sheets.ARMOR_TRIMS_SHEET).getSprite(texture);
            var consumer = sprite.wrap(buffers.getBuffer(Sheets.armorTrimsSheet(trim.pattern().value().decal())));
            model.renderToBuffer(pose, consumer, light, OverlayTexture.NO_OVERLAY);
        }
        if (stack.hasFoil()) {
            model.renderToBuffer(pose, buffers.getBuffer(RenderType.armorEntityGlint()),
                light, OverlayTexture.NO_OVERLAY);
        }
    }

    private static float ratio(double size, float standard) {
        return size > 0.0 && Double.isFinite(size) ? (float) (size / standard) : 1.0F;
    }

    // These conventional humanoid bone names cover the distributed YSM models. An arbitrary custom
    // geometry without these anchors keeps its own shape; it is never assigned a guessed body part.
    private enum Part {
        HEAD(8, 8, 8, EquipmentSlot.HEAD),
        BODY(8, 12, 4, EquipmentSlot.CHEST, EquipmentSlot.LEGS),
        LEFT_ARM(4, 12, 4, EquipmentSlot.CHEST),
        RIGHT_ARM(4, 12, 4, EquipmentSlot.CHEST),
        LEFT_LEG(4, 12, 4, EquipmentSlot.LEGS, EquipmentSlot.FEET),
        RIGHT_LEG(4, 12, 4, EquipmentSlot.LEGS, EquipmentSlot.FEET);

        final float width;
        final float height;
        final float depth;
        final EquipmentSlot[] slots;

        Part(float width, float height, float depth, EquipmentSlot... slots) {
            this.width = width;
            this.height = height;
            this.depth = depth;
            this.slots = slots;
        }

        ModelPart in(HumanoidModel<?> model) {
            return switch (this) {
                case HEAD -> model.head;
                case BODY -> model.body;
                case LEFT_ARM -> model.leftArm;
                case RIGHT_ARM -> model.rightArm;
                case LEFT_LEG -> model.leftLeg;
                case RIGHT_LEG -> model.rightLeg;
            };
        }

        static Part of(String name) {
            return switch (name.toLowerCase(Locale.ROOT).replace("_", "")) {
                case "head" -> HEAD;
                case "body", "upperbody" -> BODY;
                case "leftarm" -> LEFT_ARM;
                case "rightarm" -> RIGHT_ARM;
                case "leftleg" -> LEFT_LEG;
                case "rightleg" -> RIGHT_LEG;
                default -> null;
            };
        }
    }
}
