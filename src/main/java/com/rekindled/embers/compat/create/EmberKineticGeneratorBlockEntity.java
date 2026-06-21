package com.rekindled.embers.compat.create;

import java.util.LinkedList;
import java.util.List;

import com.rekindled.embers.api.capabilities.EmbersCapabilities;
import com.rekindled.embers.api.event.EmberEvent;
import com.rekindled.embers.api.power.IEmberCapability;
import com.rekindled.embers.api.tile.ICatalyticPlugLimitProvider;
import com.rekindled.embers.api.tile.IUpgradeable;
import com.rekindled.embers.api.upgrades.UpgradeContext;
import com.rekindled.embers.api.upgrades.UpgradeUtil;
import com.rekindled.embers.power.DefaultEmberCapability;
import com.simibubi.create.content.kinetics.base.GeneratingKineticBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;
import com.rekindled.embers.compat.legacy.capabilities.Capability;
import com.rekindled.embers.compat.legacy.LazyOptional;

public class EmberKineticGeneratorBlockEntity extends GeneratingKineticBlockEntity implements IUpgradeable, ICatalyticPlugLimitProvider {
	public static final float BASE_GENERATED_RPM = 16.0f;
	public static final float BASE_STRESS_CAPACITY = 64.0f;
	public static final double BASE_EMBER_PER_TICK = 1.0;
	private static final int WORK_INTERVAL = 20;
	private static final int MAX_CATALYTIC_PLUGS = 4;

	private final IEmberCapability ember = new DefaultEmberCapability() {
		@Override
		public void onContentsChanged() {
			EmberKineticGeneratorBlockEntity.this.setChanged();
		}

		@Override
		public boolean acceptsVolatile() {
			return false;
		}
	};
	private boolean active;
	private final int scheduled_time_offset = Math.floorMod(worldPosition.getX() * 31 + worldPosition.getY() * 17 + worldPosition.getZ() * 13, WORK_INTERVAL);
	private long fundedUntilTick;
	private double emberPerTick;
	private double prevSpeed;
	private final List<UpgradeContext> upgrades = new LinkedList<>();

	public EmberKineticGeneratorBlockEntity(BlockPos pos, BlockState state) {
		super(CreateCompat.EMBER_KINETIC_GENERATOR_ENTITY.get(), pos, state);
		ember.setEmberCapacity(4000);
	}

	@Override
	public void initialize() {
		super.initialize();
		updateGeneratedRotation();
	}

	@Override
	public void tick() {
		super.tick();
		if (level == null || level.isClientSide) {
			return;
		}
		long gameTime = level.getGameTime();
		refreshUpgrades();
		if (active) {
			UpgradeUtil.doWork(this, upgrades);
			if (gameTime<fundedUntilTick & !isScheduledWorkTick(gameTime)){
				return;
			}
		}
		int fundedTicks = fundWorkTicks(emberPerTick);
		boolean nextActive = fundedTicks > 0;
		if (nextActive) {
			fundedUntilTick = gameTime + fundedTicks;
		}
		if (active != nextActive) {
			active = nextActive;
			updateGeneratedRotation();
			sendData();
			setChanged();
		}
	}

	@Override
	public float getGeneratedSpeed() {
		if (!active) {
			return 0;
		}
			return convertToDirection((float) Math.round(BASE_GENERATED_RPM * UpgradeUtil.getTotalSpeedModifier(this, upgrades)), getBlockState().getValue(EmberKineticGeneratorBlock.FACING));
	}

	@Override
	public float calculateAddedStressCapacity() {
		if (UpgradeUtil.getTotalSpeedModifier(this, upgrades)>=1){
			lastCapacityProvided = active ? Math.round(BASE_STRESS_CAPACITY * UpgradeUtil.getTotalSpeedModifier(this, upgrades)) : 0;
		}
		else lastCapacityProvided = active ? Math.round(BASE_STRESS_CAPACITY / (UpgradeUtil.getTotalSpeedModifier(this, upgrades)*2)) : 0;
		return lastCapacityProvided;
	}

	public <T> LazyOptional<T> getCapability(Capability<T> capability, Direction side) {
		if (!isRemoved() && capability == EmbersCapabilities.EMBER_CAPABILITY && ember instanceof DefaultEmberCapability defaultEmber) {
			return defaultEmber.getCapability(capability, side);
		}
		return LazyOptional.empty();
	}

	public IEmberCapability getEmberCapability() {
		return ember;
	}

	@Override
	protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
		super.write(tag, registries, clientPacket);
		ember.writeToNBT(tag);
		tag.putBoolean("Active", active);
	}

	@Override
	protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
		super.read(tag, registries, clientPacket);
		ember.deserializeNBT(tag);
		active = tag.getBoolean("Active");
	}

	@Override
	public boolean isSideUpgradeSlot(Direction face) {
		return face.getAxis() != getBlockState().getValue(EmberKineticGeneratorBlock.FACING).getAxis();
	}

	@Override
	public int getCatalyticPlugLimit() {
		return MAX_CATALYTIC_PLUGS;
	}

	private void refreshUpgrades() {
		upgrades.clear();
		UpgradeUtil.getUpgrades(level, worldPosition, Direction.values(), upgrades);
		UpgradeUtil.verifyUpgrades(this, upgrades);
		emberPerTick = UpgradeUtil.getTotalEmberConsumption(this, BASE_EMBER_PER_TICK, upgrades);
		double currSpeed = UpgradeUtil.getTotalSpeedModifier(this, upgrades);
		if (currSpeed<1) {
			emberPerTick*=currSpeed;
		}
		if (prevSpeed!=currSpeed){
			updateGeneratedRotation();
			sendData();
			setChanged();
		}
		prevSpeed=currSpeed;
	}

	private boolean isScheduledWorkTick(long gameTime) {
		return (gameTime + scheduled_time_offset) % WORK_INTERVAL == 0;
	}

	private int fundWorkTicks(double emberCost) {
		if (emberCost <= 0) {
			return WORK_INTERVAL;
		}
		double fullCost = emberCost * WORK_INTERVAL;
		if (ember.removeAmount(fullCost, false) >= fullCost) {
			ember.removeAmount(fullCost, true);
			UpgradeUtil.throwEvent(this, new EmberEvent(this, EmberEvent.EnumType.CONSUME, fullCost), upgrades);
			return WORK_INTERVAL;
		}
		int affordableTicks = Math.min(WORK_INTERVAL, (int) Math.floor(ember.getEmber() / emberCost));
		if (affordableTicks <= 0) {
			return 0;
		}
		ember.removeAmount(emberCost * affordableTicks, true);
		UpgradeUtil.throwEvent(this, new EmberEvent(this, EmberEvent.EnumType.CONSUME, emberCost * affordableTicks), upgrades);
		return affordableTicks;
	}
}
