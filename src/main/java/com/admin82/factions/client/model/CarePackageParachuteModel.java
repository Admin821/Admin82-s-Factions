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

public final class CarePackageParachuteModel {
    public static final ModelLayerLocation LAYER = new ModelLayerLocation(
            ResourceLocation.fromNamespaceAndPath(AdminsFactions.MODID, "carepackage_parachute"), "main");

    private CarePackageParachuteModel() {}

    public static LayerDefinition createLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();

        PartDefinition main = root.addOrReplaceChild("main", CubeListBuilder.create()
                .texOffs(0, 33).addBox(-7.5F, -1.0F, -7.5F, 15.0F, 1.0F, 31.0F)
                .texOffs(0, 0).addBox(-7.5F, -14.0F, -7.5F, 15.0F, 2.0F, 31.0F)
                .texOffs(100, 59).addBox(-8.0F, -3.0F, -8.0F, 2.0F, 2.0F, 2.0F)
                .texOffs(108, 61).addBox(6.0F, -11.0F, -8.0F, 2.0F, 2.0F, 2.0F)
                .texOffs(116, 61).addBox(6.0F, -3.0F, -8.0F, 2.0F, 2.0F, 2.0F)
                .texOffs(108, 54).addBox(7.1F, -14.2F, -3.5F, 1.0F, 5.0F, 2.0F, new CubeDeformation(-0.3F))
                .texOffs(114, 54).addBox(7.1F, -14.2F, 17.6F, 1.0F, 5.0F, 2.0F, new CubeDeformation(-0.3F))
                .texOffs(92, 42).addBox(-7.0F, -12.0F, 22.0F, 14.0F, 11.0F, 1.0F)
                .texOffs(80, 65).addBox(-7.0F, -12.0F, -5.5F, 1.0F, 11.0F, 27.0F)
                .texOffs(56, 88).addBox(-5.5F, -12.0F, -7.0F, 11.0F, 11.0F, 1.0F)
                .texOffs(0, 88).addBox(6.0F, -12.0F, -5.5F, 1.0F, 11.0F, 27.0F)
                .texOffs(0, 233).addBox(-10.0F, -72.0F, -1.0F, 21.0F, 4.0F, 19.0F, new CubeDeformation(2.0F))
                .texOffs(184, 235).addBox(-9.0F, -77.0F, 0.0F, 19.0F, 4.0F, 17.0F),
                PartPose.offset(0.0F, 24.0F, 0.0F));

        addRotated(main, "canopy_east", 168, 5, -11, -19, -2, 21, 19, 2, 2.0F,
                9.3F, -67, 7.6F, -2.9671F, 1.5359F, 0);
        addRotated(main, "canopy_south", 168, 5, -11, -19, -2, 21, 19, 2, 2.0F,
                0.3F, -67, 17, -2.9671F, 0, 0);
        addRotated(main, "canopy_west", 168, 5, -11, -19, -2, 21, 19, 2, 2.0F,
                -8.9F, -67, 8.5F, 0.1745F, -1.5359F, -3.1416F);
        addRotated(main, "canopy_north", 168, 5, -11, -19, -2, 21, 19, 2, 2.0F,
                0.3F, -67, -1, 0.1745F, 0, -3.1416F);

        addRotated(main, "skirt_west", 0, 128, -11, -19, -2, 21, 19, 2, 1.0F,
                -8.7F, -31, 7.6F, -2.9671F, 1.5359F, 3.1416F);
        addRotated(main, "skirt_east", 0, 128, -11, -19, -2, 21, 19, 2, 1.0F,
                9.1F, -31, 8.5F, 0.1745F, -1.5359F, 0);
        addRotated(main, "skirt_south", 0, 128, -11, -19, -2, 21, 19, 2, 1.3F,
                0.3F, -31, 17, -2.9671F, 0, 3.1416F);
        addRotated(main, "skirt_north", 0, 128, -11, -19, -2, 21, 19, 2, 1.3F,
                0.3F, -31, -1, 0.1745F, 0, 0);

        addRotated(main, "cord_se", 80, 103, 0, -19, -1, 1, 19, 1, 0,
                6, -13, 15.1F, -1.4485F, -1.4488F, 1.6058F);
        addRotated(main, "cord_sw", 80, 103, 0, -19, -1, 1, 19, 1, 0,
                -6, -13, 14.6F, -2.9846F, 0.0043F, 3.0197F);
        addRotated(main, "cord_nw", 80, 103, 0, -19, -1, 1, 19, 1, 0,
                -6, -13, 0.7F, 1.6931F, 1.4488F, 1.5358F);
        addRotated(main, "cord_ne", 80, 103, 0, -19, -1, 1, 19, 1, 0,
                6, -13, 1.3F, 0.157F, -0.0043F, 0.1219F);

        addRotated(main, "corner_nw", 100, 59, -1, 6, -1, 2, 2, 2, 0,
                -7, -17, -7, 0, 1.5708F, 0);

        PartDefinition hardware = main.addOrReplaceChild("hardware", CubeListBuilder.create()
                .texOffs(90, 117).addBox(-5.5F, -3.1F, -2.6F, 2, 3, 1, new CubeDeformation(-0.1F))
                .texOffs(84, 117).addBox(15.6F, -3.1F, -2.6F, 2, 3, 1, new CubeDeformation(-0.1F))
                .texOffs(64, 116).addBox(-5.5F, -3, -16.8F, 2, 12, 2, new CubeDeformation(-0.1F))
                .texOffs(56, 116).addBox(15.6F, -3, -16.8F, 2, 12, 2, new CubeDeformation(-0.1F))
                .texOffs(108, 103).addBox(19.4F, -3, -16.4F, 2, 12, 2, new CubeDeformation(0.21F))
                .texOffs(100, 103).addBox(19.4F, -3, -3.6F, 2, 12, 2, new CubeDeformation(0.21F))
                .texOffs(92, 103).addBox(-9.4F, -3, -3.6F, 2, 12, 2, new CubeDeformation(0.21F))
                .texOffs(84, 103).addBox(-9.4F, -3, -16.4F, 2, 12, 2, new CubeDeformation(0.21F)),
                PartPose.offsetAndRotation(-9, -9, 2, 0, -1.5708F, 0));

        main.addOrReplaceChild("rear_hardware", CubeListBuilder.create()
                .texOffs(116, 111).addBox(-1, -2, -1, 2, 2, 2)
                .texOffs(116, 107).addBox(-1, 6, -1, 2, 2, 2),
                PartPose.offsetAndRotation(7, -9, 23, 0, -1.5708F, 0));
        main.addOrReplaceChild("rear_hardware_2", CubeListBuilder.create()
                .texOffs(116, 103).addBox(-1, 6, -1, 2, 2, 2)
                .texOffs(72, 116).addBox(-1, -2, -1, 2, 2, 2),
                PartPose.offsetAndRotation(-7, -9, 23, 0, 3.1416F, 0));

        addRotated(main, "detail_1", 116, 115, -2.7781F, -2.4757F, 16.2F, 1, 5, 1, -0.2F,
                -7.5F, -12, 8, 1.5708F, -0.0087F, 1.5708F);
        addRotated(main, "detail_2", 100, 117, -2.5891F, -16.7161F, 1.5F, 1, 4, 1, -0.25F,
                -7.5F, -12, 8, 0, 0, 1.5621F);

        main.addOrReplaceChild("label_details", CubeListBuilder.create()
                .texOffs(100, 54).addBox(8.5151F, 9.3522F, 9, 1, 2, 3, new CubeDeformation(0.01F))
                .texOffs(92, 59).addBox(8.5151F, 9.3522F, -1.5F, 1, 2, 3, new CubeDeformation(0.01F))
                .texOffs(92, 54).addBox(8.5151F, 9.3522F, -12, 1, 2, 3, new CubeDeformation(0.01F)),
                PartPose.offsetAndRotation(-7.5F, -12, 8, 0, 0, -1.0036F));
        main.addOrReplaceChild("front_details", CubeListBuilder.create()
                .texOffs(72, 100).addBox(2, 0, -1.5F, 1, 13, 3)
                .texOffs(64, 100).addBox(2, 0, -12, 1, 13, 3)
                .texOffs(56, 100).addBox(2, 0, 9, 1, 13, 3),
                PartPose.offsetAndRotation(-7.5F, -12, 8, 0, 0, -1.5708F));
        addRotated(main, "detail_3", 96, 117, -2.9107F, -16.6751F, -2.5F, 1, 4, 1, -0.25F,
                -7.5F, -12, 8, 0, 0, 1.5795F);

        return LayerDefinition.create(mesh, 256, 256);
    }

    private static void addRotated(PartDefinition parent, String name, int u, int v,
                                   float x, float y, float z, float width, float height, float depth,
                                   float deformation, float pivotX, float pivotY, float pivotZ,
                                   float pitch, float yaw, float roll) {
        parent.addOrReplaceChild(name, CubeListBuilder.create().texOffs(u, v)
                        .addBox(x, y, z, width, height, depth, new CubeDeformation(deformation)),
                PartPose.offsetAndRotation(pivotX, pivotY, pivotZ, pitch, yaw, roll));
    }
}