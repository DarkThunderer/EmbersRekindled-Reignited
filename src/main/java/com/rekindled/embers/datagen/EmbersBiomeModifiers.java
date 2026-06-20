package com.rekindled.embers.datagen;

import java.util.List;

import com.rekindled.embers.Embers;
import com.rekindled.embers.RegistryManager;

import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderOwner;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.BootstrapContext;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.common.world.BiomeModifier;
import net.neoforged.neoforge.common.world.BiomeModifiers.AddSpawnsBiomeModifier;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import net.neoforged.neoforge.registries.holdersets.AndHolderSet;
import net.neoforged.neoforge.registries.holdersets.NotHolderSet;
import net.neoforged.neoforge.registries.holdersets.OrHolderSet;

public class EmbersBiomeModifiers {

	public static final ResourceKey<BiomeModifier> GOLEM_SPAWN = ResourceKey.create(NeoForgeRegistries.Keys.BIOME_MODIFIERS, ResourceLocation.fromNamespaceAndPath(Embers.MODID, "add_golem_spawn"));

	public static final TagKey<Biome> NO_MONSTERS = TagKey.create(Registries.BIOME, ResourceLocation.fromNamespaceAndPath("c", "no_default_monsters"));

	public static void generate(BootstrapContext<BiomeModifier> bootstrap) {
		HolderGetter<Biome> biome = bootstrap.lookup(Registries.BIOME);
		HolderSet<Biome> overworldBiomes = biome.getOrThrow(BiomeTags.IS_OVERWORLD);
		List<HolderSet<Biome>> biomeBlackList = List.of(biome.getOrThrow(Tags.Biomes.IS_MUSHROOM), HolderSet.direct(biome.getOrThrow(Biomes.DEEP_DARK)), biome.getOrThrow(NO_MONSTERS));
		HolderSet<Biome> hostileSpawns = new AndHolderSet<Biome>(List.of(overworldBiomes, new NotHolderSetWrapper<Biome>(new OrHolderSet<Biome>(biomeBlackList))));

		bootstrap.register(GOLEM_SPAWN, new AddSpawnsBiomeModifier(hostileSpawns, List.of(new MobSpawnSettings.SpawnerData(RegistryManager.ANCIENT_GOLEM.get(), 15, 1, 1))));
	}

	//wow this is stupid
	public static class NotHolderSetWrapper<T> extends NotHolderSet<T> {
		public NotHolderSetWrapper(HolderSet<T> value) {
			super(null, value);
		}

		@Override
		public boolean canSerializeIn(HolderOwner<T> holderOwner) {
			return true;
		}
	}
}
