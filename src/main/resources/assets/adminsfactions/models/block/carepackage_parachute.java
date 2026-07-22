// Made with Blockbench 5.1.5
// Exported for Minecraft version 1.17 or later with Mojang mappings
// Paste this class into your mod and generate all required imports


public class carepackage_parachute<T extends Entity> extends EntityModel<T> {
	// This layer location should be baked with EntityRendererProvider.Context in the entity renderer and passed into this model's constructor
	public static final ModelLayerLocation LAYER_LOCATION = new ModelLayerLocation(new ResourceLocation("modid", "carepackage_parachute"), "main");
	private final ModelPart bb_main;

	public carepackage_parachute(ModelPart root) {
		this.bb_main = root.getChild("bb_main");
	}

	public static LayerDefinition createBodyLayer() {
		MeshDefinition meshdefinition = new MeshDefinition();
		PartDefinition partdefinition = meshdefinition.getRoot();

		PartDefinition bb_main = partdefinition.addOrReplaceChild("bb_main", CubeListBuilder.create().texOffs(0, 33).addBox(-7.5F, -1.0F, -7.5F, 15.0F, 1.0F, 31.0F, new CubeDeformation(0.0F))
		.texOffs(0, 0).addBox(-7.5F, -14.0F, -7.5F, 15.0F, 2.0F, 31.0F, new CubeDeformation(0.0F))
		.texOffs(100, 59).addBox(-8.0F, -3.0F, -8.0F, 2.0F, 2.0F, 2.0F, new CubeDeformation(0.0F))
		.texOffs(108, 61).addBox(6.0F, -11.0F, -8.0F, 2.0F, 2.0F, 2.0F, new CubeDeformation(0.0F))
		.texOffs(116, 61).addBox(6.0F, -3.0F, -8.0F, 2.0F, 2.0F, 2.0F, new CubeDeformation(0.0F))
		.texOffs(108, 54).addBox(7.1F, -14.2F, -3.5F, 1.0F, 5.0F, 2.0F, new CubeDeformation(-0.3F))
		.texOffs(114, 54).addBox(7.1F, -14.2F, 17.6F, 1.0F, 5.0F, 2.0F, new CubeDeformation(-0.3F))
		.texOffs(92, 42).addBox(-7.0F, -12.0F, 22.0F, 14.0F, 11.0F, 1.0F, new CubeDeformation(0.0F))
		.texOffs(80, 65).addBox(-7.0F, -12.0F, -5.5F, 1.0F, 11.0F, 27.0F, new CubeDeformation(0.0F))
		.texOffs(56, 88).addBox(-5.5F, -12.0F, -7.0F, 11.0F, 11.0F, 1.0F, new CubeDeformation(0.0F))
		.texOffs(0, 88).addBox(6.0F, -12.0F, -5.5F, 1.0F, 11.0F, 27.0F, new CubeDeformation(0.0F))
		.texOffs(0, 233).addBox(-10.0F, -72.0F, -1.0F, 21.0F, 4.0F, 19.0F, new CubeDeformation(2.0F))
		.texOffs(184, 235).addBox(-9.0F, -77.0F, 0.0F, 19.0F, 4.0F, 17.0F, new CubeDeformation(0.0F)), PartPose.offset(0.0F, 24.0F, 0.0F));

		PartDefinition cube_r1 = bb_main.addOrReplaceChild("cube_r1", CubeListBuilder.create().texOffs(168, 5).addBox(-11.0F, -19.0F, -2.0F, 21.0F, 19.0F, 2.0F, new CubeDeformation(2.0F)), PartPose.offsetAndRotation(9.3F, -67.0F, 7.6F, -2.9671F, 1.5359F, 0.0F));

		PartDefinition cube_r2 = bb_main.addOrReplaceChild("cube_r2", CubeListBuilder.create().texOffs(168, 5).addBox(-11.0F, -19.0F, -2.0F, 21.0F, 19.0F, 2.0F, new CubeDeformation(2.0F)), PartPose.offsetAndRotation(0.3F, -67.0F, 17.0F, -2.9671F, 0.0F, 0.0F));

		PartDefinition cube_r3 = bb_main.addOrReplaceChild("cube_r3", CubeListBuilder.create().texOffs(168, 5).addBox(-11.0F, -19.0F, -2.0F, 21.0F, 19.0F, 2.0F, new CubeDeformation(2.0F)), PartPose.offsetAndRotation(-8.9F, -67.0F, 8.5F, 0.1745F, -1.5359F, -3.1416F));

		PartDefinition cube_r4 = bb_main.addOrReplaceChild("cube_r4", CubeListBuilder.create().texOffs(168, 5).addBox(-11.0F, -19.0F, -2.0F, 21.0F, 19.0F, 2.0F, new CubeDeformation(2.0F)), PartPose.offsetAndRotation(0.3F, -67.0F, -1.0F, 0.1745F, 0.0F, -3.1416F));

		PartDefinition cube_r5 = bb_main.addOrReplaceChild("cube_r5", CubeListBuilder.create().texOffs(0, 128).addBox(-11.0F, -19.0F, -2.0F, 21.0F, 19.0F, 2.0F, new CubeDeformation(1.0F)), PartPose.offsetAndRotation(-8.7F, -31.0F, 7.6F, -2.9671F, 1.5359F, 3.1416F));

		PartDefinition cube_r6 = bb_main.addOrReplaceChild("cube_r6", CubeListBuilder.create().texOffs(0, 128).addBox(-11.0F, -19.0F, -2.0F, 21.0F, 19.0F, 2.0F, new CubeDeformation(1.0F)), PartPose.offsetAndRotation(9.1F, -31.0F, 8.5F, 0.1745F, -1.5359F, 0.0F));

		PartDefinition cube_r7 = bb_main.addOrReplaceChild("cube_r7", CubeListBuilder.create().texOffs(0, 128).addBox(-11.0F, -19.0F, -2.0F, 21.0F, 19.0F, 2.0F, new CubeDeformation(1.3F)), PartPose.offsetAndRotation(0.3F, -31.0F, 17.0F, -2.9671F, 0.0F, 3.1416F));

		PartDefinition cube_r8 = bb_main.addOrReplaceChild("cube_r8", CubeListBuilder.create().texOffs(0, 128).addBox(-11.0F, -19.0F, -2.0F, 21.0F, 19.0F, 2.0F, new CubeDeformation(1.3F)), PartPose.offsetAndRotation(0.3F, -31.0F, -1.0F, 0.1745F, 0.0F, 0.0F));

		PartDefinition cube_r9 = bb_main.addOrReplaceChild("cube_r9", CubeListBuilder.create().texOffs(80, 103).addBox(0.0F, -19.0F, -1.0F, 1.0F, 19.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(6.0F, -13.0F, 15.1F, -1.4485F, -1.4488F, 1.6058F));

		PartDefinition cube_r10 = bb_main.addOrReplaceChild("cube_r10", CubeListBuilder.create().texOffs(80, 103).addBox(0.0F, -19.0F, -1.0F, 1.0F, 19.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(-6.0F, -13.0F, 14.6F, -2.9846F, 0.0043F, 3.0197F));

		PartDefinition cube_r11 = bb_main.addOrReplaceChild("cube_r11", CubeListBuilder.create().texOffs(80, 103).addBox(0.0F, -19.0F, -1.0F, 1.0F, 19.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(-6.0F, -13.0F, 0.7F, 1.6931F, 1.4488F, 1.5358F));

		PartDefinition cube_r12 = bb_main.addOrReplaceChild("cube_r12", CubeListBuilder.create().texOffs(80, 103).addBox(0.0F, -19.0F, -1.0F, 1.0F, 19.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(6.0F, -13.0F, 1.3F, 0.157F, -0.0043F, 0.1219F));

		PartDefinition cube_r13 = bb_main.addOrReplaceChild("cube_r13", CubeListBuilder.create().texOffs(100, 59).addBox(-1.0F, 6.0F, -1.0F, 2.0F, 2.0F, 2.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(-7.0F, -17.0F, -7.0F, 0.0F, 1.5708F, 0.0F));

		PartDefinition cube_r14 = bb_main.addOrReplaceChild("cube_r14", CubeListBuilder.create().texOffs(90, 117).addBox(-5.5F, -3.1F, -2.6F, 2.0F, 3.0F, 1.0F, new CubeDeformation(-0.1F))
		.texOffs(84, 117).addBox(15.6F, -3.1F, -2.6F, 2.0F, 3.0F, 1.0F, new CubeDeformation(-0.1F))
		.texOffs(64, 116).addBox(-5.5F, -3.0F, -16.8F, 2.0F, 12.0F, 2.0F, new CubeDeformation(-0.1F))
		.texOffs(56, 116).addBox(15.6F, -3.0F, -16.8F, 2.0F, 12.0F, 2.0F, new CubeDeformation(-0.1F))
		.texOffs(108, 103).addBox(19.4F, -3.0F, -16.4F, 2.0F, 12.0F, 2.0F, new CubeDeformation(0.21F))
		.texOffs(100, 103).addBox(19.4F, -3.0F, -3.6F, 2.0F, 12.0F, 2.0F, new CubeDeformation(0.21F))
		.texOffs(92, 103).addBox(-9.4F, -3.0F, -3.6F, 2.0F, 12.0F, 2.0F, new CubeDeformation(0.21F))
		.texOffs(84, 103).addBox(-9.4F, -3.0F, -16.4F, 2.0F, 12.0F, 2.0F, new CubeDeformation(0.21F)), PartPose.offsetAndRotation(-9.0F, -9.0F, 2.0F, 0.0F, -1.5708F, 0.0F));

		PartDefinition cube_r15 = bb_main.addOrReplaceChild("cube_r15", CubeListBuilder.create().texOffs(116, 111).addBox(-1.0F, -2.0F, -1.0F, 2.0F, 2.0F, 2.0F, new CubeDeformation(0.0F))
		.texOffs(116, 107).addBox(-1.0F, 6.0F, -1.0F, 2.0F, 2.0F, 2.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(7.0F, -9.0F, 23.0F, 0.0F, -1.5708F, 0.0F));

		PartDefinition cube_r16 = bb_main.addOrReplaceChild("cube_r16", CubeListBuilder.create().texOffs(116, 103).addBox(-1.0F, 6.0F, -1.0F, 2.0F, 2.0F, 2.0F, new CubeDeformation(0.0F))
		.texOffs(72, 116).addBox(-1.0F, -2.0F, -1.0F, 2.0F, 2.0F, 2.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(-7.0F, -9.0F, 23.0F, 0.0F, 3.1416F, 0.0F));

		PartDefinition cube_r17 = bb_main.addOrReplaceChild("cube_r17", CubeListBuilder.create().texOffs(116, 115).addBox(-2.7781F, -2.4757F, 16.2F, 1.0F, 5.0F, 1.0F, new CubeDeformation(-0.2F)), PartPose.offsetAndRotation(-7.5F, -12.0F, 8.0F, 1.5708F, -0.0087F, 1.5708F));

		PartDefinition cube_r18 = bb_main.addOrReplaceChild("cube_r18", CubeListBuilder.create().texOffs(100, 117).addBox(-2.5891F, -16.7161F, 1.5F, 1.0F, 4.0F, 1.0F, new CubeDeformation(-0.25F)), PartPose.offsetAndRotation(-7.5F, -12.0F, 8.0F, 0.0F, 0.0F, 1.5621F));

		PartDefinition cube_r19 = bb_main.addOrReplaceChild("cube_r19", CubeListBuilder.create().texOffs(100, 54).addBox(8.5151F, 9.3522F, 9.0F, 1.0F, 2.0F, 3.0F, new CubeDeformation(0.01F))
		.texOffs(92, 59).addBox(8.5151F, 9.3522F, -1.5F, 1.0F, 2.0F, 3.0F, new CubeDeformation(0.01F))
		.texOffs(92, 54).addBox(8.5151F, 9.3522F, -12.0F, 1.0F, 2.0F, 3.0F, new CubeDeformation(0.01F)), PartPose.offsetAndRotation(-7.5F, -12.0F, 8.0F, 0.0F, 0.0F, -1.0036F));

		PartDefinition cube_r20 = bb_main.addOrReplaceChild("cube_r20", CubeListBuilder.create().texOffs(72, 100).addBox(2.0F, 0.0F, -1.5F, 1.0F, 13.0F, 3.0F, new CubeDeformation(0.0F))
		.texOffs(64, 100).addBox(2.0F, 0.0F, -12.0F, 1.0F, 13.0F, 3.0F, new CubeDeformation(0.0F))
		.texOffs(56, 100).addBox(2.0F, 0.0F, 9.0F, 1.0F, 13.0F, 3.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(-7.5F, -12.0F, 8.0F, 0.0F, 0.0F, -1.5708F));

		PartDefinition cube_r21 = bb_main.addOrReplaceChild("cube_r21", CubeListBuilder.create().texOffs(96, 117).addBox(-2.9107F, -16.6751F, -2.5F, 1.0F, 4.0F, 1.0F, new CubeDeformation(-0.25F)), PartPose.offsetAndRotation(-7.5F, -12.0F, 8.0F, 0.0F, 0.0F, 1.5795F));

		return LayerDefinition.create(meshdefinition, 256, 256);
	}

	@Override
	public void setupAnim(Entity entity, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch) {

	}

	@Override
	public void renderToBuffer(PoseStack poseStack, VertexConsumer vertexConsumer, int packedLight, int packedOverlay, float red, float green, float blue, float alpha) {
		bb_main.render(poseStack, vertexConsumer, packedLight, packedOverlay, red, green, blue, alpha);
	}
}