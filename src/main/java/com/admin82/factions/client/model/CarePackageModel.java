package com.admin82.factions.client.model;

import com.admin82.factions.AdminsFactions;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.resources.ResourceLocation;

public final class CarePackageModel {
    public static final ModelLayerLocation CLOSED_LAYER = layer("carepackage_closed");
    public static final ModelLayerLocation OPEN_LAYER = layer("carepackage_open");

    private CarePackageModel() {}

    private static ModelLayerLocation layer(String path) {
        return new ModelLayerLocation(ResourceLocation.fromNamespaceAndPath(AdminsFactions.MODID, path), "main");
    }

    public static LayerDefinition createClosedLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition main = mesh.getRoot().addOrReplaceChild("main", closedBase(), PartPose.offset(0, 24, 0));
        addClosedDetails(main);
        return LayerDefinition.create(mesh, 128, 128);
    }

    public static LayerDefinition createOpenLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition main = mesh.getRoot().addOrReplaceChild("main", CubeListBuilder.create()
                .texOffs(0, 33).addBox(-7.5F, -1, -7.5F, 15, 1, 31)
                .texOffs(62, 103).addBox(-8, -3, -8, 2, 2, 2)
                .texOffs(70, 103).addBox(6, -11, -8, 2, 2, 2)
                .texOffs(78, 103).addBox(6, -3, -8, 2, 2, 2)
                .texOffs(92, 0).addBox(-7, -12, 22, 14, 11, 1)
                .texOffs(0, 65).addBox(-7, -12, -5.5F, 1, 11, 27)
                .texOffs(92, 12).addBox(-5.5F, -12, -7, 11, 11, 1)
                .texOffs(56, 65).addBox(6, -12, -5.5F, 1, 11, 27), PartPose.offset(0, 24, 0));

        rotated(main, "interior_cube", CubeListBuilder.create()
                        .texOffs(92, 24).addBox(-5, -4, -2, 6, 6, 6),
                -0.7F, -7, 9.4F, 0, -1.5708F, -0.2618F);
        rotated(main, "corner", CubeListBuilder.create().texOffs(62, 103).addBox(-1, 6, -1, 2, 2, 2),
                -7, -17, -7, 0, 1.5708F, 0);
        rotated(main, "hardware", openHardware(), -9, -9, 2, 0, -1.5708F, 0);
        rotated(main, "lid", CubeListBuilder.create()
                        .texOffs(56, 103).addBox(14.6F, -2.2F, 9.6F, 1, 5, 2, new CubeDeformation(-0.3F))
                        .texOffs(100, 57).addBox(14.6F, -2.2F, -11.5F, 1, 5, 2, new CubeDeformation(-0.3F))
                        .texOffs(0, 0).addBox(0, -2, -15.5F, 15, 2, 31),
                -7.5F, -12, 8, 0, 0, -0.5672F);
        addRearHardware(main, true);
        addOpenDetails(main);
        return LayerDefinition.create(mesh, 128, 128);
    }

    private static CubeListBuilder closedBase() {
        return CubeListBuilder.create()
                .texOffs(0, 33).addBox(-7.5F, -1, -7.5F, 15, 1, 31)
                .texOffs(0, 0).addBox(-7.5F, -14, -7.5F, 15, 2, 31)
                .texOffs(52, 103).addBox(-8, -3, -8, 2, 2, 2)
                .texOffs(60, 103).addBox(6, -11, -8, 2, 2, 2)
                .texOffs(68, 103).addBox(6, -3, -8, 2, 2, 2)
                .texOffs(40, 103).addBox(7.1F, -14.2F, -3.5F, 1, 5, 2, new CubeDeformation(-0.3F))
                .texOffs(46, 103).addBox(7.1F, -14.2F, 17.6F, 1, 5, 2, new CubeDeformation(-0.3F))
                .texOffs(92, 0).addBox(-7, -12, 22, 14, 11, 1)
                .texOffs(0, 65).addBox(-7, -12, -5.5F, 1, 11, 27)
                .texOffs(92, 12).addBox(-5.5F, -12, -7, 11, 11, 1)
                .texOffs(56, 65).addBox(6, -12, -5.5F, 1, 11, 27);
    }

    private static CubeListBuilder closedHardware() {
        return CubeListBuilder.create()
                .texOffs(58, 107).addBox(-5.5F, -3.1F, -2.6F, 2, 3, 1, new CubeDeformation(-0.1F))
                .texOffs(52, 107).addBox(15.6F, -3.1F, -2.6F, 2, 3, 1, new CubeDeformation(-0.1F))
                .texOffs(32, 103).addBox(-5.5F, -3, -16.8F, 2, 12, 2, new CubeDeformation(-0.1F))
                .texOffs(24, 103).addBox(15.6F, -3, -16.8F, 2, 12, 2, new CubeDeformation(-0.1F))
                .texOffs(16, 103).addBox(19.4F, -3, -16.4F, 2, 12, 2, new CubeDeformation(0.21F))
                .texOffs(8, 103).addBox(19.4F, -3, -3.6F, 2, 12, 2, new CubeDeformation(0.21F))
                .texOffs(0, 103).addBox(-9.4F, -3, -3.6F, 2, 12, 2, new CubeDeformation(0.21F))
                .texOffs(100, 40).addBox(-9.4F, -3, -16.4F, 2, 12, 2, new CubeDeformation(0.21F));
    }

    private static CubeListBuilder openHardware() {
        return CubeListBuilder.create()
                .texOffs(66, 107).addBox(-5.5F, -3.1F, -2.6F, 2, 3, 1, new CubeDeformation(-0.1F))
                .texOffs(106, 61).addBox(15.6F, -3.1F, -2.6F, 2, 3, 1, new CubeDeformation(-0.1F))
                .texOffs(48, 103).addBox(-5.5F, -3, -16.8F, 2, 12, 2, new CubeDeformation(-0.1F))
                .texOffs(40, 103).addBox(15.6F, -3, -16.8F, 2, 12, 2, new CubeDeformation(-0.1F))
                .texOffs(32, 103).addBox(19.4F, -3, -16.4F, 2, 12, 2, new CubeDeformation(0.21F))
                .texOffs(24, 103).addBox(19.4F, -3, -3.6F, 2, 12, 2, new CubeDeformation(0.21F))
                .texOffs(16, 103).addBox(-9.4F, -3, -3.6F, 2, 12, 2, new CubeDeformation(0.21F))
                .texOffs(8, 103).addBox(-9.4F, -3, -16.4F, 2, 12, 2, new CubeDeformation(0.21F));
    }

    private static void addClosedDetails(PartDefinition main) {
        rotated(main, "corner", CubeListBuilder.create().texOffs(52, 103).addBox(-1, 6, -1, 2, 2, 2),
                -7, -17, -7, 0, 1.5708F, 0);
        rotated(main, "hardware", closedHardware(), -9, -9, 2, 0, -1.5708F, 0);
        addRearHardware(main, false);
        detail(main, "detail_1", 64, 107, -2.7781F, -2.4757F, 16.2F, 1, 5, 1, -0.2F, 1.5708F, -0.0087F, 1.5708F);
        detail(main, "detail_2", 72, 107, -2.5891F, -16.7161F, 1.5F, 1, 4, 1, -0.25F, 0, 0, 1.5621F);
        rotated(main, "labels", CubeListBuilder.create()
                        .texOffs(100, 59).addBox(8.5151F, 9.3522F, 9, 1, 2, 3, new CubeDeformation(0.01F))
                        .texOffs(100, 54).addBox(8.5151F, 9.3522F, -1.5F, 1, 2, 3, new CubeDeformation(0.01F))
                        .texOffs(92, 56).addBox(8.5151F, 9.3522F, -12, 1, 2, 3, new CubeDeformation(0.01F)),
                -7.5F, -12, 8, 0, 0, -1.0036F);
        rotated(main, "front_details", CubeListBuilder.create()
                        .texOffs(100, 24).addBox(2, 0, -1.5F, 1, 13, 3)
                        .texOffs(92, 40).addBox(2, 0, -12, 1, 13, 3)
                        .texOffs(92, 24).addBox(2, 0, 9, 1, 13, 3),
                -7.5F, -12, 8, 0, 0, -1.5708F);
        detail(main, "detail_3", 68, 107, -2.9107F, -16.6751F, -2.5F, 1, 4, 1, -0.25F, 0, 0, 1.5795F);
    }

    private static void addOpenDetails(PartDefinition main) {
        detail(main, "detail_1", 62, 107, -2.7781F, -2.4757F, 16.2F, 1, 5, 1, -0.2F, 1.5708F, -0.0087F, 1.0036F);
        detail(main, "detail_2", 76, 107, -2.5891F, -16.7161F, 1.5F, 1, 4, 1, -0.25F, 0, 0, 0.9948F);
        rotated(main, "labels", CubeListBuilder.create()
                        .texOffs(100, 52).addBox(8.5151F, 9.3522F, 9, 1, 2, 3, new CubeDeformation(0.01F))
                        .texOffs(92, 57).addBox(8.5151F, 9.3522F, -1.5F, 1, 2, 3, new CubeDeformation(0.01F))
                        .texOffs(92, 52).addBox(8.5151F, 9.3522F, -12, 1, 2, 3, new CubeDeformation(0.01F)),
                -7.5F, -12, 8, 0, 0, -1.5708F);
        rotated(main, "front_details", CubeListBuilder.create()
                        .texOffs(0, 103).addBox(2, 0, -1.5F, 1, 13, 3)
                        .texOffs(100, 36).addBox(2, 0, -12, 1, 13, 3)
                        .texOffs(92, 36).addBox(2, 0, 9, 1, 13, 3),
                -7.5F, -12, 8, 0, 0, -2.138F);
        detail(main, "detail_3", 72, 107, -2.9107F, -16.6751F, -2.5F, 1, 4, 1, -0.25F, 0, 0, 1.0123F);
    }

    private static void addRearHardware(PartDefinition main, boolean open) {
        int firstU = open ? 106 : 100;
        int firstV = open ? 57 : 103;
        int secondU = open ? 102 : 92;
        main.addOrReplaceChild("rear_hardware", CubeListBuilder.create()
                        .texOffs(firstU, firstV).addBox(-1, -2, -1, 2, 2, 2)
                        .texOffs(secondU, 103).addBox(-1, 6, -1, 2, 2, 2),
                PartPose.offsetAndRotation(7, -9, 23, 0, -1.5708F, 0));
        main.addOrReplaceChild("rear_hardware_2", CubeListBuilder.create()
                        .texOffs(open ? 94 : 84, 103).addBox(-1, 6, -1, 2, 2, 2)
                        .texOffs(open ? 86 : 76, 103).addBox(-1, -2, -1, 2, 2, 2),
                PartPose.offsetAndRotation(-7, -9, 23, 0, 3.1416F, 0));
    }

    private static void detail(PartDefinition main, String name, int u, int v,
                               float x, float y, float z, float width, float height, float depth,
                               float deformation, float pitch, float yaw, float roll) {
        rotated(main, name, CubeListBuilder.create().texOffs(u, v)
                        .addBox(x, y, z, width, height, depth, new CubeDeformation(deformation)),
                -7.5F, -12, 8, pitch, yaw, roll);
    }

    private static void rotated(PartDefinition parent, String name, CubeListBuilder cubes,
                                float x, float y, float z, float pitch, float yaw, float roll) {
        parent.addOrReplaceChild(name, cubes, PartPose.offsetAndRotation(x, y, z, pitch, yaw, roll));
    }
}