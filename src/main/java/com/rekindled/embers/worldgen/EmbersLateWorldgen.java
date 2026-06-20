package com.rekindled.embers.worldgen;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;

import com.rekindled.embers.ConfigManager;
import com.rekindled.embers.Embers;
import com.rekindled.embers.RegistryManager;
import com.rekindled.embers.datagen.EmbersConfiguredFeatures;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

public class EmbersLateWorldgen {
	private static final int CHUNKS_PER_TICK = 2;
	private static final int ORE_NEIGHBOR_RADIUS = 1;
	private static final int RUIN_AVERAGE_CHUNK_CHANCE = 49;
	private static final int RUIN_SALT = 193826405;
	private static final TagKey<Block> LEAD_ORES = BlockTags.create(ResourceLocation.fromNamespaceAndPath("c", "ores/lead"));
	private static final TagKey<Block> SILVER_ORES = BlockTags.create(ResourceLocation.fromNamespaceAndPath("c", "ores/silver"));
	private static final ResourceLocation[] RUINS = new ResourceLocation[] {
			ResourceLocation.fromNamespaceAndPath(Embers.MODID, "small_ruin_copper"),
			ResourceLocation.fromNamespaceAndPath(Embers.MODID, "small_ruin_iron"),
			ResourceLocation.fromNamespaceAndPath(Embers.MODID, "small_ruin_gold"),
			ResourceLocation.fromNamespaceAndPath(Embers.MODID, "small_ruin_lead"),
			ResourceLocation.fromNamespaceAndPath(Embers.MODID, "small_ruin_silver")
	};
	private static final int[] RUIN_WEIGHTS = new int[] { 2, 2, 1, 2, 1 };
	private static final Map<ServerLevel, Set<Long>> PENDING_CHUNKS = new HashMap<>();
	private static final Map<ServerLevel, Set<Long>> FORCED_CHUNKS = new HashMap<>();
	private static final Map<ChunkGenerator, ExternalOreGeneration> EXTERNAL_ORE_GENERATION = Collections.synchronizedMap(new WeakHashMap<>());
	private record QueuedChunk(ServerLevel level, ChunkPos pos, boolean force) {
	}
	private record OreGenerationSelection(boolean lead, boolean silver) {
	}
	private record ExternalOreGeneration(Set<ResourceLocation> leadOres, Set<ResourceLocation> silverOres) {
		private boolean hasLead() {
			return !leadOres.isEmpty();
		}

		private boolean hasSilver() {
			return !silverOres.isEmpty();
		}
	}

	private EmbersLateWorldgen() {
	}

	public static void onChunkLoad(ChunkEvent.Load event) {
		if (!event.isNewChunk() || !(event.getLevel() instanceof ServerLevel level) || !isOverworld(level)) {
			return;
		}
		ChunkPos pos = event.getChunk().getPos();
		queueChunk(level, pos, false);
	}

	public static void onServerTick(ServerTickEvent.Post event) {
		if (PENDING_CHUNKS.isEmpty()) {
			return;
		}

		List<QueuedChunk> batch = new ArrayList<>();
		int generated = 0;
		Iterator<Map.Entry<ServerLevel, Set<Long>>> levelIterator = PENDING_CHUNKS.entrySet().iterator();
		while (levelIterator.hasNext() && generated < CHUNKS_PER_TICK) {
			Map.Entry<ServerLevel, Set<Long>> entry = levelIterator.next();
			ServerLevel level = entry.getKey();
			Iterator<Long> chunkIterator = entry.getValue().iterator();
			while (chunkIterator.hasNext() && generated < CHUNKS_PER_TICK) {
				ChunkPos pos = new ChunkPos(chunkIterator.next());
				if (isReadyForRetrogen(level, pos)) {
					batch.add(new QueuedChunk(level, pos, consumeForce(level, pos)));
					chunkIterator.remove();
					generated++;
				}
			}
			if (entry.getValue().isEmpty()) {
				levelIterator.remove();
				FORCED_CHUNKS.remove(level);
			}
		}

		for (QueuedChunk queued : batch) {
			try {
				populateChunk(queued.level(), queued.pos(), queued.force());
			} catch (RuntimeException exception) {
				Embers.LOGGER.warn("Skipping Embers regeneration for chunk {}, {}", queued.pos().x, queued.pos().z, exception);
			}
		}
	}

	public static int queueRegeneration(ServerLevel level, ChunkPos center, int radius) {
		if (!isOverworld(level)) {
			return 0;
		}

		int queued = 0;
		for (int x = center.x - radius; x <= center.x + radius; x++) {
			for (int z = center.z - radius; z <= center.z + radius; z++) {
				if (level.hasChunk(x, z) && queueChunk(level, new ChunkPos(x, z), true)) {
					queued++;
				}
			}
		}
		return queued;
	}

	public static void onPotentialSpawns(LevelEvent.PotentialSpawns event) {
		if (event.getMobCategory() != MobCategory.MONSTER || !(event.getLevel() instanceof ServerLevel level) || !isValidBiome(level, event.getPos())) {
			return;
		}
		for (MobSpawnSettings.SpawnerData data : event.getSpawnerDataList()) {
			if (data.type == RegistryManager.ANCIENT_GOLEM.get()) {
				return;
			}
		}
		event.addSpawnerData(new MobSpawnSettings.SpawnerData(RegistryManager.ANCIENT_GOLEM.get(), 15, 1, 1));
	}

	private static boolean queueChunk(ServerLevel level, ChunkPos pos, boolean force) {
		long packedPos = pos.toLong();
		boolean added = PENDING_CHUNKS.computeIfAbsent(level, ignored -> new HashSet<>()).add(packedPos);
		if (force) {
			FORCED_CHUNKS.computeIfAbsent(level, ignored -> new HashSet<>()).add(packedPos);
		}
		return added || force;
	}

	private static boolean consumeForce(ServerLevel level, ChunkPos pos) {
		Set<Long> forced = FORCED_CHUNKS.get(level);
		if (forced == null) {
			return false;
		}
		boolean force = forced.remove(pos.toLong());
		if (forced.isEmpty()) {
			FORCED_CHUNKS.remove(level);
		}
		return force;
	}

	private static void populateChunk(ServerLevel level, ChunkPos pos, boolean force) {
		RandomSource random = RandomSource.create(Mth.getSeed(pos.x, RUIN_SALT, pos.z) ^ level.getSeed());
		placeMissingOreFeatures(level, pos, random, force);
		if (shouldPlaceRuin(level, pos, random, force)) {
			placeRuin(level, pos, random);
		}
	}

	private static void placeMissingOreFeatures(ServerLevel level, ChunkPos pos, RandomSource random, boolean force) {
		OreGenerationSelection selection = getOreGenerationSelection(level);
		if (!selection.lead() && !selection.silver()) {
			return;
		}

		BlockPos origin = new BlockPos(pos.getMinBlockX(), level.getMinBuildHeight(), pos.getMinBlockZ());
		BlockPos biomeCheck = new BlockPos(pos.getMiddleBlockX(), Mth.clamp(level.getSeaLevel(), level.getMinBuildHeight(), level.getMaxBuildHeight() - 1), pos.getMiddleBlockZ());
		ChunkGenerator generator = level.getChunkSource().getGenerator();
		if (!hasLoadedNeighborRing(level, pos, ORE_NEIGHBOR_RADIUS)) {
			return;
		}
		if (selection.lead()) {
			placeMissingOreFeature(level, generator, random, origin, biomeCheck, force, 8, -28, 28, EmbersConfiguredFeatures.ORE_LEAD);
		}
		if (selection.silver()) {
			placeMissingOreFeature(level, generator, random, origin, biomeCheck, force, 4, level.getMinBuildHeight(), level.getMinBuildHeight() + 64, EmbersConfiguredFeatures.ORE_SILVER);
		}
	}

	private static void placeMissingOreFeature(ServerLevel level, ChunkGenerator generator, RandomSource random, BlockPos origin, BlockPos biomeCheck, boolean force, int count, int minY, int maxY, ConfiguredFeature<?, ?> feature) {
		if (!force && !isValidBiome(level, biomeCheck)) {
			return;
		}
		for (int i = 0; i < count; i++) {
			BlockPos orePos = new BlockPos(origin.getX() + random.nextInt(16), sampleTriangle(random, level, minY, maxY), origin.getZ() + random.nextInt(16));
			feature.place(level, generator, random, orePos);
		}
	}

	private static OreGenerationSelection getOreGenerationSelection(ServerLevel level) {
		return switch (ConfigManager.ORE_GENERATION.get()) {
			case NEVER -> new OreGenerationSelection(false, false);
			case ALWAYS -> new OreGenerationSelection(true, true);
			case AUTO -> {
				ExternalOreGeneration external = EXTERNAL_ORE_GENERATION.computeIfAbsent(level.getChunkSource().getGenerator(),
						ignored -> detectExternalOreGeneration(level));
				yield new OreGenerationSelection(!external.hasLead(), !external.hasSilver());
			}
		};
	}

	private static ExternalOreGeneration detectExternalOreGeneration(ServerLevel level) {
		Set<ResourceLocation> leadOres = new HashSet<>();
		Set<ResourceLocation> silverOres = new HashSet<>();
		ChunkGenerator generator = level.getChunkSource().getGenerator();

		for (Holder<Biome> biome : generator.getBiomeSource().possibleBiomes()) {
			if (!biome.is(BiomeTags.IS_OVERWORLD)) {
				continue;
			}
			for (HolderSet<PlacedFeature> step : biome.value().getGenerationSettings().features()) {
				for (Holder<PlacedFeature> placedFeature : step) {
					placedFeature.value().getFeatures().forEach(feature -> collectExternalOreTargets(feature, leadOres, silverOres));
				}
			}
		}

		ExternalOreGeneration detected = new ExternalOreGeneration(Set.copyOf(leadOres), Set.copyOf(silverOres));
		Embers.LOGGER.info("Embers automatic ore detection found external lead ores {} and external silver ores {}", detected.leadOres(), detected.silverOres());
		return detected;
	}

	private static void collectExternalOreTargets(ConfiguredFeature<?, ?> feature, Set<ResourceLocation> leadOres, Set<ResourceLocation> silverOres) {
		if (!(feature.config() instanceof OreConfiguration oreConfiguration)) {
			return;
		}
		for (OreConfiguration.TargetBlockState target : oreConfiguration.targetStates) {
			ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(target.state.getBlock());
			if (Embers.MODID.equals(blockId.getNamespace())) {
				continue;
			}
			if (target.state.is(LEAD_ORES)) {
				leadOres.add(blockId);
			}
			if (target.state.is(SILVER_ORES)) {
				silverOres.add(blockId);
			}
		}
	}

	private static int sampleTriangle(RandomSource random, ServerLevel level, int minY, int maxY) {
		int clampedMin = Mth.clamp(minY, level.getMinBuildHeight(), level.getMaxBuildHeight() - 1);
		int clampedMax = Mth.clamp(maxY, clampedMin, level.getMaxBuildHeight() - 1);
		int range = Math.max(1, clampedMax - clampedMin + 1);
		return clampedMin + (random.nextInt(range) + random.nextInt(range)) / 2;
	}

	private static boolean shouldPlaceRuin(ServerLevel level, ChunkPos pos, RandomSource random, boolean force) {
		boolean hasStructureStart = !level.structureManager().startsForStructure(pos, EmbersLateWorldgen::isEmbersCaveStructure).isEmpty();
		if (!hasStructureStart && random.nextInt(RUIN_AVERAGE_CHUNK_CHANCE) != 0) {
			return false;
		}
		if (!isValidBiome(level, new BlockPos(pos.getMiddleBlockX(), level.getMinBuildHeight() + 48, pos.getMiddleBlockZ()))) {
			return false;
		}
		return force || !hasStructureStart;
	}

	private static boolean isEmbersCaveStructure(Structure structure) {
		return structure.type() == RegistryManager.CAVE_STRUCTURE.get();
	}

	private static void placeRuin(ServerLevel level, ChunkPos pos, RandomSource random) {
		ResourceLocation templateId = chooseRuin(random);
		Optional<StructureTemplate> optionalTemplate = level.getStructureManager().get(templateId);
		if (optionalTemplate.isEmpty()) {
			return;
		}

		StructureTemplate template = optionalTemplate.get();
		Rotation rotation = Rotation.getRandom(random);
		Vec3i size = template.getSize(rotation);
		if (size.getX() > 16 || size.getZ() > 16) {
			return;
		}

		int x = pos.getMinBlockX() + random.nextInt(17 - size.getX());
		int z = pos.getMinBlockZ() + random.nextInt(17 - size.getZ());
		int y = findCaveFloor(level, x + size.getX() / 2, z + size.getZ() / 2);
		if (y == Integer.MIN_VALUE) {
			return;
		}

		BlockPos placePos = new BlockPos(x, y, z);
		BoundingBox box = new BoundingBox(pos.getMinBlockX(), level.getMinBuildHeight(), pos.getMinBlockZ(),
				pos.getMaxBlockX(), level.getMaxBuildHeight(), pos.getMaxBlockZ());
		StructurePlaceSettings settings = new StructurePlaceSettings()
				.setMirror(Mirror.NONE)
				.setRotation(rotation)
				.setBoundingBox(box)
				.setRandom(random)
				.setLiquidSettings(LiquidSettings.APPLY_WATERLOGGING)
				.setFinalizeEntities(true)
				.addProcessor(new CrystalSeedStructureProcessor(210000, 5050000, 900))
				.addProcessor(EntityMobilizerStructureProcessor.INSTANCE);

		template.placeInWorld(level, placePos, placePos, settings, random, Block.UPDATE_ALL);
	}

	private static ResourceLocation chooseRuin(RandomSource random) {
		int total = 0;
		for (int weight : RUIN_WEIGHTS) {
			total += weight;
		}
		int selection = random.nextInt(total);
		for (int i = 0; i < RUIN_WEIGHTS.length; i++) {
			selection -= RUIN_WEIGHTS[i];
			if (selection < 0) {
				return RUINS[i];
			}
		}
		return RUINS[0];
	}

	private static int findCaveFloor(ServerLevel level, int x, int z) {
		int maxY = Math.min(level.getMinBuildHeight() + 128, level.getMaxBuildHeight() - 2);
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, level.getMinBuildHeight() + 9, z);
		int fallback = Integer.MIN_VALUE;
		for (int y = level.getMinBuildHeight() + 9; y <= maxY; y++) {
			pos.setY(y);
			boolean solid = !level.getBlockState(pos).canBeReplaced();
			pos.setY(y + 1);
			boolean openAbove = level.getBlockState(pos).canBeReplaced();
			if (solid && openAbove) {
				if (fallback != Integer.MIN_VALUE) {
					return y;
				}
				fallback = y;
			}
		}
		return fallback;
	}

	private static boolean isReadyForRetrogen(ServerLevel level, ChunkPos pos) {
		ServerChunkCache chunkSource = level.getChunkSource();
		return chunkSource.isPositionTicking(pos.toLong()) && hasLoadedNeighborRing(level, pos, ORE_NEIGHBOR_RADIUS);
	}

	private static boolean hasLoadedNeighborRing(ServerLevel level, ChunkPos center, int radius) {
		ServerChunkCache chunkSource = level.getChunkSource();
		for (int x = center.x - radius; x <= center.x + radius; x++) {
			for (int z = center.z - radius; z <= center.z + radius; z++) {
				long chunkKey = ChunkPos.asLong(x, z);
				if (!chunkSource.isPositionTicking(chunkKey)) {
					return false;
				}
				LevelChunk chunk = chunkSource.getChunkNow(x, z);
				if (chunk == null) {
					return false;
				}
			}
		}
		return true;
	}

	private static boolean isValidBiome(ServerLevel level, BlockPos pos) {
		Holder<Biome> biome = level.getBiome(pos);
		return isOverworld(level)
				&& biome.is(BiomeTags.IS_OVERWORLD)
				&& !biome.is(Tags.Biomes.IS_MUSHROOM)
				&& !biome.is(Tags.Biomes.NO_DEFAULT_MONSTERS)
				&& !biome.is(net.minecraft.world.level.biome.Biomes.DEEP_DARK);
	}

	private static boolean isOverworld(ServerLevel level) {
		return level.dimension() == Level.OVERWORLD;
	}
}
