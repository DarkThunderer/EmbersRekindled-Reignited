package com.rekindled.embers.worldgen;

import java.util.List;
import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.rekindled.embers.RegistryManager;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.VerticalAnchor;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.heightproviders.ConstantHeight;
import net.minecraft.world.level.levelgen.heightproviders.HeightProvider;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePiecesBuilder;
import net.minecraft.world.level.levelgen.structure.pools.DimensionPadding;
import net.minecraft.world.level.levelgen.structure.pools.JigsawPlacement;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.pools.alias.PoolAliasBinding;
import net.minecraft.world.level.levelgen.structure.pools.alias.PoolAliasLookup;
import net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings;

public class CaveStructure extends Structure {

	public static final MapCodec<CaveStructure> CODEC = RecordCodecBuilder.<CaveStructure>mapCodec(instance -> instance.group(
			settingsCodec(instance),
			StructureTemplatePool.CODEC.fieldOf("start_pool").forGetter(structure -> structure.startPool),
			ResourceLocation.CODEC.optionalFieldOf("start_jigsaw_name").forGetter(structure -> structure.startJigsawName),
			Codec.intRange(0, 20).fieldOf("size").forGetter(structure -> structure.maxDepth),
			HeightProvider.CODEC.fieldOf("start_height").forGetter(structure -> structure.startHeight),
			HeightProvider.CODEC.optionalFieldOf("max_height", ConstantHeight.of(VerticalAnchor.top())).forGetter(structure -> structure.maxHeight),
			Codec.BOOL.fieldOf("use_expansion_hack").forGetter(structure -> structure.useExpansionHack),
			Heightmap.Types.CODEC.optionalFieldOf("project_start_to_heightmap").forGetter(structure -> structure.projectStartToHeightmap),
			Codec.intRange(1, 128).fieldOf("max_distance_from_center").forGetter(structure -> structure.maxDistanceFromCenter),
			Codec.list(PoolAliasBinding.CODEC).optionalFieldOf("pool_aliases", List.of()).forGetter(structure -> structure.poolAliases),
			DimensionPadding.CODEC.optionalFieldOf("dimension_padding", DimensionPadding.ZERO).forGetter(structure -> structure.dimensionPadding),
			LiquidSettings.CODEC.optionalFieldOf("liquid_settings", LiquidSettings.APPLY_WATERLOGGING).forGetter(structure -> structure.liquidSettings)
	).apply(instance, CaveStructure::new)).validate(CaveStructure::verifyRange);

	private final Holder<StructureTemplatePool> startPool;
	private final Optional<ResourceLocation> startJigsawName;
	private final int maxDepth;
	private final HeightProvider startHeight;
	private final HeightProvider maxHeight;
	private final boolean useExpansionHack;
	private final Optional<Heightmap.Types> projectStartToHeightmap;
	private final int maxDistanceFromCenter;
	private final List<PoolAliasBinding> poolAliases;
	private final DimensionPadding dimensionPadding;
	private final LiquidSettings liquidSettings;

	private static DataResult<CaveStructure> verifyRange(CaveStructure structure) {
		int terrainPadding = switch (structure.terrainAdaptation()) {
			case NONE -> 0;
			case BURY, BEARD_THIN, BEARD_BOX, ENCAPSULATE -> 12;
		};
		return structure.maxDistanceFromCenter + terrainPadding > 128
				? DataResult.error(() -> "Structure size including terrain adaptation must not exceed 128")
				: DataResult.success(structure);
	}

	public CaveStructure(StructureSettings settings, Holder<StructureTemplatePool> startPool,
			Optional<ResourceLocation> startJigsawName, int maxDepth, HeightProvider startHeight,
			HeightProvider maxHeight, boolean useExpansionHack, Optional<Heightmap.Types> projectStartToHeightmap,
			int maxDistanceFromCenter, List<PoolAliasBinding> poolAliases, DimensionPadding dimensionPadding,
			LiquidSettings liquidSettings) {
		super(settings);
		this.startPool = startPool;
		this.startJigsawName = startJigsawName;
		this.maxDepth = maxDepth;
		this.startHeight = startHeight;
		this.maxHeight = maxHeight;
		this.useExpansionHack = useExpansionHack;
		this.projectStartToHeightmap = projectStartToHeightmap;
		this.maxDistanceFromCenter = maxDistanceFromCenter;
		this.poolAliases = poolAliases;
		this.dimensionPadding = dimensionPadding;
		this.liquidSettings = liquidSettings;
	}

	public CaveStructure(StructureSettings settings, Holder<StructureTemplatePool> startPool, int maxDepth,
			HeightProvider startHeight, HeightProvider maxHeight, boolean useExpansionHack) {
		this(settings, startPool, Optional.empty(), maxDepth, startHeight, maxHeight, useExpansionHack,
				Optional.empty(), 80, List.of(), DimensionPadding.ZERO, LiquidSettings.APPLY_WATERLOGGING);
	}

	public CaveStructure(StructureSettings settings, Holder<StructureTemplatePool> startPool, int maxDepth,
			HeightProvider startHeight, HeightProvider maxHeight, boolean useExpansionHack, Heightmap.Types heightmap) {
		this(settings, startPool, Optional.empty(), maxDepth, startHeight, maxHeight, useExpansionHack,
				Optional.of(heightmap), 80, List.of(), DimensionPadding.ZERO, LiquidSettings.APPLY_WATERLOGGING);
	}

	@Override
	public Optional<GenerationStub> findGenerationPoint(GenerationContext context) {
		ChunkPos chunk = context.chunkPos();
		WorldGenerationContext worldGenerationContext = new WorldGenerationContext(context.chunkGenerator(), context.heightAccessor());
		int startY = startHeight.sample(context.random(), worldGenerationContext);
		int maxY = maxHeight.sample(context.random(), worldGenerationContext);
		BlockPos start = new BlockPos(chunk.getMinBlockX(), startY, chunk.getMinBlockZ());
		Optional<GenerationStub> jigsaw = JigsawPlacement.addPieces(context, startPool, startJigsawName, maxDepth,
				start, useExpansionHack, Optional.empty(), maxDistanceFromCenter,
				PoolAliasLookup.create(poolAliases, start, context.seed()), dimensionPadding, liquidSettings);
		if (jigsaw.isEmpty()) {
			return Optional.empty();
		}

		StructurePiecesBuilder generatedPieces = jigsaw.get().getPiecesBuilder();
		if (generatedPieces.isEmpty()) {
			return Optional.empty();
		}

		PiecesContainer pieces = generatedPieces.build();
		BoundingBox bounds = pieces.calculateBoundingBox();
		int centerX = (bounds.minX() + bounds.maxX()) / 2;
		int centerZ = (bounds.minZ() + bounds.maxZ()) / 2;
		int searchTop = Math.min(maxY, getProjectedSearchTop(context, centerX, centerZ, startY));
		int caveFloor = findCaveFloor(context, bounds, centerX, centerZ, searchTop, maxY);
		if (caveFloor == Integer.MIN_VALUE) {
			return Optional.empty();
		}

		int verticalOffset = caveFloor - bounds.minY();
		pieces.pieces().forEach(piece -> piece.move(0, verticalOffset, 0));
		BlockPos structurePosition = new BlockPos(centerX, caveFloor, centerZ);
		return Optional.of(new GenerationStub(structurePosition,
				builder -> pieces.pieces().forEach(builder::addPiece)));
	}

	private int getProjectedSearchTop(GenerationContext context, int x, int z, int startY) {
		return projectStartToHeightmap
				.map(heightmap -> startY + context.chunkGenerator().getFirstFreeHeight(x, z, heightmap,
						context.heightAccessor(), context.randomState()))
				.orElse(context.heightAccessor().getMaxBuildHeight() - 1);
	}

	private static int findCaveFloor(GenerationContext context, BoundingBox bounds, int x, int z,
			int searchTop, int ceilingLimit) {
		int structureHeight = bounds.getYSpan();
		int minY = context.heightAccessor().getMinBuildHeight() + 9;
		int maxY = Math.min(searchTop, context.heightAccessor().getMaxBuildHeight() - structureHeight - 1);
		int maxCeilingY = Math.min(ceilingLimit, context.heightAccessor().getMaxBuildHeight() - 1);
		if (maxY < minY) {
			return Integer.MIN_VALUE;
		}

		NoiseColumn centerColumn = context.chunkGenerator().getBaseColumn(x, z, context.heightAccessor(), context.randomState());
		BlockPos.MutableBlockPos checkPos = new BlockPos.MutableBlockPos(x, minY, z);
		int firstFloor = Integer.MIN_VALUE;
		for (int y = minY; y <= maxY; y++) {
			BlockState floor = centerColumn.getBlock(y);
			if (!floor.isFaceSturdy(EmptyBlockGetter.INSTANCE, checkPos.setY(y), Direction.UP)
					|| !centerColumn.getBlock(y + 1).isAir()) {
				continue;
			}

			int ceilingY = findDryCeiling(centerColumn, y + 1, maxCeilingY);
			if (ceilingY == Integer.MIN_VALUE || ceilingY - y < structureHeight
					|| !isDryAndUnderground(context, bounds, y)) {
				continue;
			}

			if (firstFloor != Integer.MIN_VALUE) {
				return y;
			}
			firstFloor = y;
		}
		return firstFloor;
	}

	private static int findDryCeiling(NoiseColumn column, int startY, int maxY) {
		for (int y = startY; y <= maxY; y++) {
			BlockState state = column.getBlock(y);
			if (!state.isAir()) {
				return state.getFluidState().isEmpty() ? y : Integer.MIN_VALUE;
			}
		}
		return Integer.MIN_VALUE;
	}

	private static boolean isDryAndUnderground(GenerationContext context, BoundingBox bounds, int floorY) {
		int topY = floorY + bounds.getYSpan() - 1;
		for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
			for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
				int surfaceY = context.chunkGenerator().getFirstFreeHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG,
						context.heightAccessor(), context.randomState());
				if (topY + 2 >= surfaceY) {
					return false;
				}

				NoiseColumn column = context.chunkGenerator().getBaseColumn(x, z, context.heightAccessor(), context.randomState());
				for (int y = floorY; y <= topY; y++) {
					if (!column.getBlock(y).getFluidState().isEmpty()) {
						return false;
					}
				}
			}
		}
		return true;
	}

	@Override
	public StructureType<?> type() {
		return RegistryManager.CAVE_STRUCTURE.get();
	}
}
