// Made with Blockbench 5.1.5
// Exported for Minecraft version 1.17 or later with Mojang mappings
// Paste this class into your mod and generate all required imports


public class carepackage<T extends Entity> extends EntityModel<T> {
	// This layer location should be baked with EntityRendererProvider.Context in the entity renderer and passed into this model's constructor
	public static final ModelLayerLocation LAYER_LOCATION = new ModelLayerLocation(new ResourceLocation("modid", "carepackage"), "main");
	private final ModelPart bb_main;

	public carepackage(ModelPart root) {
		this.bb_main = root.getChild("bb_main");
	}

	public static LayerDefinition createBodyLayer() {
		MeshDefinition meshdefinition = new MeshDefinition();
		PartDefinition partdefinition = meshdefinition.getRoot();

		PartDefinition bb_main = partdefinition.addOrReplaceChild("bb_main", CubeListBuilder.create().texOffs(0, 33).addBox(-7.5F, -1.0F, -7.5F, 15.0F, 1.0F, 31.0F, new CubeDeformation(0.0F))
		.texOffs(0, 0).addBox(-7.5F, -14.0F, -7.5F, 15.0F, 2.0F, 31.0F, new CubeDeformation(0.0F))
		.texOffs(52, 103).addBox(-8.0F, -3.0F, -8.0F, 2.0F, 2.0F, 2.0F, new CubeDeformation(0.0F))
		.texOffs(60, 103).addBox(6.0F, -11.0F, -8.0F, 2.0F, 2.0F, 2.0F, new CubeDeformation(0.0F))
		.texOffs(68, 103).addBox(6.0F, -3.0F, -8.0F, 2.0F, 2.0F, 2.0F, new CubeDeformation(0.0F))
		.texOffs(40, 103).addBox(7.1F, -14.2F, -3.5F, 1.0F, 5.0F, 2.0F, new CubeDeformation(-0.3F))
		.texOffs(46, 103).addBox(7.1F, -14.2F, 17.6F, 1.0F, 5.0F, 2.0F, new CubeDeformation(-0.3F))
		.texOffs(92, 0).addBox(-7.0F, -12.0F, 22.0F, 14.0F, 11.0F, 1.0F, new CubeDeformation(0.0F))
		.texOffs(0, 65).addBox(-7.0F, -12.0F, -5.5F, 1.0F, 11.0F, 27.0F, new CubeDeformation(0.0F))
		.texOffs(92, 12).addBox(-5.5F, -12.0F, -7.0F, 11.0F, 11.0F, 1.0F, new CubeDeformation(0.0F))
		.texOffs(56, 65).addBox(6.0F, -12.0F, -5.5F, 1.0F, 11.0F, 27.0F, new CubeDeformation(0.0F)), PartPose.offset(0.0F, 24.0F, 0.0F));

		PartDefinition cube_r1 = bb_main.addOrReplaceChild("cube_r1", CubeListBuilder.create().texOffs(52, 103).addBox(-1.0F, 6.0F, -1.0F, 2.0F, 2.0F, 2.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(-7.0F, -17.0F, -7.0F, 0.0F, 1.5708F, 0.0F));

		PartDefinition cube_r2 = bb_main.addOrReplaceChild("cube_r2", CubeListBuilder.create().texOffs(58, 107).addBox(-5.5F, -3.1F, -2.6F, 2.0F, 3.0F, 1.0F, new CubeDeformation(-0.1F))
		.texOffs(52, 107).addBox(15.6F, -3.1F, -2.6F, 2.0F, 3.0F, 1.0F, new CubeDeformation(-0.1F))
		.texOffs(32, 103).addBox(-5.5F, -3.0F, -16.8F, 2.0F, 12.0F, 2.0F, new CubeDeformation(-0.1F))
		.texOffs(24, 103).addBox(15.6F, -3.0F, -16.8F, 2.0F, 12.0F, 2.0F, new CubeDeformation(-0.1F))
		.texOffs(16, 103).addBox(19.4F, -3.0F, -16.4F, 2.0F, 12.0F, 2.0F, new CubeDeformation(0.21F))
		.texOffs(8, 103).addBox(19.4F, -3.0F, -3.6F, 2.0F, 12.0F, 2.0F, new CubeDeformation(0.21F))
		.texOffs(0, 103).addBox(-9.4F, -3.0F, -3.6F, 2.0F, 12.0F, 2.0F, new CubeDeformation(0.21F))
		.texOffs(100, 40).addBox(-9.4F, -3.0F, -16.4F, 2.0F, 12.0F, 2.0F, new CubeDeformation(0.21F)), PartPose.offsetAndRotation(-9.0F, -9.0F, 2.0F, 0.0F, -1.5708F, 0.0F));

		PartDefinition cube_r3 = bb_main.addOrReplaceChild("cube_r3", CubeListBuilder.create().texOffs(100, 103).addBox(-1.0F, -2.0F, -1.0F, 2.0F, 2.0F, 2.0F, new CubeDeformation(0.0F))
		.texOffs(92, 103).addBox(-1.0F, 6.0F, -1.0F, 2.0F, 2.0F, 2.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(7.0F, -9.0F, 23.0F, 0.0F, -1.5708F, 0.0F));

		PartDefinition cube_r4 = bb_main.addOrReplaceChild("cube_r4", CubeListBuilder.create().texOffs(84, 103).addBox(-1.0F, 6.0F, -1.0F, 2.0F, 2.0F, 2.0F, new CubeDeformation(0.0F))
		.texOffs(76, 103).addBox(-1.0F, -2.0F, -1.0F, 2.0F, 2.0F, 2.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(-7.0F, -9.0F, 23.0F, 0.0F, 3.1416F, 0.0F));

		PartDefinition cube_r5 = bb_main.addOrReplaceChild("cube_r5", CubeListBuilder.create().texOffs(64, 107).addBox(-2.7781F, -2.4757F, 16.2F, 1.0F, 5.0F, 1.0F, new CubeDeformation(-0.2F)), PartPose.offsetAndRotation(-7.5F, -12.0F, 8.0F, 1.5708F, -0.0087F, 1.5708F));

		PartDefinition cube_r6 = bb_main.addOrReplaceChild("cube_r6", CubeListBuilder.create().texOffs(72, 107).addBox(-2.5891F, -16.7161F, 1.5F, 1.0F, 4.0F, 1.0F, new CubeDeformation(-0.25F)), PartPose.offsetAndRotation(-7.5F, -12.0F, 8.0F, 0.0F, 0.0F, 1.5621F));

		PartDefinition cube_r7 = bb_main.addOrReplaceChild("cube_r7", CubeListBuilder.create().texOffs(100, 59).addBox(8.5151F, 9.3522F, 9.0F, 1.0F, 2.0F, 3.0F, new CubeDeformation(0.01F))
		.texOffs(100, 54).addBox(8.5151F, 9.3522F, -1.5F, 1.0F, 2.0F, 3.0F, new CubeDeformation(0.01F))
		.texOffs(92, 56).addBox(8.5151F, 9.3522F, -12.0F, 1.0F, 2.0F, 3.0F, new CubeDeformation(0.01F)), PartPose.offsetAndRotation(-7.5F, -12.0F, 8.0F, 0.0F, 0.0F, -1.0036F));

		PartDefinition cube_r8 = bb_main.addOrReplaceChild("cube_r8", CubeListBuilder.create().texOffs(100, 24).addBox(2.0F, 0.0F, -1.5F, 1.0F, 13.0F, 3.0F, new CubeDeformation(0.0F))
		.texOffs(92, 40).addBox(2.0F, 0.0F, -12.0F, 1.0F, 13.0F, 3.0F, new CubeDeformation(0.0F))
		.texOffs(92, 24).addBox(2.0F, 0.0F, 9.0F, 1.0F, 13.0F, 3.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(-7.5F, -12.0F, 8.0F, 0.0F, 0.0F, -1.5708F));

		PartDefinition cube_r9 = bb_main.addOrReplaceChild("cube_r9", CubeListBuilder.create().texOffs(68, 107).addBox(-2.9107F, -16.6751F, -2.5F, 1.0F, 4.0F, 1.0F, new CubeDeformation(-0.25F)), PartPose.offsetAndRotation(-7.5F, -12.0F, 8.0F, 0.0F, 0.0F, 1.5795F));

		return LayerDefinition.create(meshdefinition, 128, 128);
	}

	@Override
	public void setupAnim(Entity entity, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch) {

	}

	@Override
	public void renderToBuffer(PoseStack poseStack, VertexConsumer vertexConsumer, int packedLight, int packedOverlay, float red, float green, float blue, float alpha) {
		bb_main.render(poseStack, vertexConsumer, packedLight, packedOverlay, red, green, blue, alpha);
	}
}