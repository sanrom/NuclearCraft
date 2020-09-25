package nc.multiblock.fission.salt;

import static nc.block.property.BlockProperties.*;
import static nc.config.NCConfig.*;

import java.util.*;

import javax.annotation.Nonnull;

import org.apache.commons.lang3.tuple.Pair;

import com.google.common.collect.Lists;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import nc.Global;
import nc.multiblock.Multiblock;
import nc.multiblock.fission.*;
import nc.multiblock.fission.salt.tile.*;
import nc.multiblock.fission.solid.tile.*;
import nc.multiblock.fission.tile.*;
import nc.multiblock.fission.tile.TileFissionSource.PrimingTargetInfo;
import nc.multiblock.fission.tile.port.*;
import nc.multiblock.network.*;
import nc.multiblock.tile.TileBeefAbstract.SyncReason;
import nc.recipe.*;
import nc.recipe.ingredient.IFluidIngredient;
import nc.tile.internal.fluid.Tank;
import nc.util.NCMath;
import net.minecraft.block.state.IBlockState;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;

public class MoltenSaltFissionLogic extends FissionReactorLogic {
	
	public List<Tank> tanks = Lists.newArrayList(new Tank(FissionReactor.BASE_TANK_CAPACITY, NCRecipes.fission_emergency_cooling_valid_fluids.get(0)), new Tank(FissionReactor.BASE_TANK_CAPACITY, null));
	
	public RecipeInfo<ProcessorRecipe> emergencyCoolingRecipeInfo;
	
	public int heaterCount = 0;
	public double meanHeatingSpeedMultiplier = 0D, totalHeatingSpeedMultiplier = 0D;
	
	public MoltenSaltFissionLogic(FissionReactorLogic oldLogic) {
		super(oldLogic);
		if (oldLogic instanceof MoltenSaltFissionLogic) {
			MoltenSaltFissionLogic oldMoltenSaltLogic = (MoltenSaltFissionLogic) oldLogic;
			heaterCount = oldMoltenSaltLogic.heaterCount;
			meanHeatingSpeedMultiplier = oldMoltenSaltLogic.meanHeatingSpeedMultiplier;
			totalHeatingSpeedMultiplier = oldMoltenSaltLogic.totalHeatingSpeedMultiplier;
		}
	}
	
	@Override
	public String getID() {
		return "molten_salt";
	}
	
	@Override
	public void onResetStats() {
		heaterCount = 0;
		meanHeatingSpeedMultiplier = totalHeatingSpeedMultiplier = 0D;
	}
	
	@Override
	public void onReactorFormed() {
		tanks.get(0).setCapacity(FissionReactor.BASE_TANK_CAPACITY * getCapacityMultiplier());
		tanks.get(1).setCapacity(FissionReactor.BASE_TANK_CAPACITY * getCapacityMultiplier());
		
		super.onReactorFormed();
	}
	
	@Override
	public boolean isMachineWhole(Multiblock multiblock) {
		return !containsBlacklistedPart();
	}
	
	public static final List<Pair<Class<? extends IFissionPart>, String>> MOLTEN_SALT_PART_BLACKLIST = Lists.newArrayList(Pair.of(TileSolidFissionCell.class, Global.MOD_ID + ".multiblock_validation.fission_reactor.prohibit_cells"), Pair.of(TileSolidFissionSink.class, Global.MOD_ID + ".multiblock_validation.fission_reactor.prohibit_sinks"));
	
	@Override
	public List<Pair<Class<? extends IFissionPart>, String>> getPartBlacklist() {
		return MOLTEN_SALT_PART_BLACKLIST;
	}
	
	@Override
	public void refreshConnections() {
		super.refreshConnections();
		refreshFilteredPorts(TileFissionVesselPort.class, TileSaltFissionVessel.class);
		refreshFilteredPorts(TileFissionHeaterPort.class, TileSaltFissionHeater.class);
	}
	
	@Override
	public void distributeFlux(final ObjectSet<IFissionFuelComponent> primedCache, final Long2ObjectMap<IFissionFuelComponent> primedFailCache) {
		for (TileFissionSource source : getParts(TileFissionSource.class)) {
			IBlockState state = getWorld().getBlockState(source.getPos());
			EnumFacing facing = source.getPartPosition().getFacing();
			source.refreshIsRedstonePowered(getWorld(), source.getPos());
			getWorld().setBlockState(source.getPos(), state.withProperty(FACING_ALL, facing != null ? facing : state.getValue(FACING_ALL)).withProperty(ACTIVE, source.getIsRedstonePowered()), 3);
			
			if (!source.getIsRedstonePowered()) {
				continue;
			}
			PrimingTargetInfo targetInfo = source.getPrimingTarget(false);
			if (targetInfo == null) {
				continue;
			}
			IFissionFuelComponent fuelComponent = targetInfo.fuelComponent;
			if (fuelComponent == null || primedFailCache.containsKey(fuelComponent.getTilePos().toLong())) {
				continue;
			}
			
			fuelComponent.tryPriming(getReactor());
			if (fuelComponent.isPrimed()) {
				primedCache.add(fuelComponent);
			}
		}
		
		for (IFissionFuelComponent primedComponent : primedCache) {
			iterateFluxSearch(primedComponent);
		}
		
		for (IFissionFuelComponent primedComponent : primedCache) {
			primedComponent.refreshIsProcessing(false);
			refreshFuelComponentLocal(primedComponent);
			primedComponent.unprime();
			
			if (!primedComponent.isFunctional()) {
				primedFailCache.put(primedComponent.getTilePos().toLong(), primedComponent);
				getReactor().refreshFlag = true;
			}
		}
	}
	
	@Override
	public void refreshClusters() {
		for (TileSaltFissionVessel vessel : getParts(TileSaltFissionVessel.class)) {
			refreshFuelComponentModerators(vessel, assumedValidCache);
		}
		
		getReactor().passiveModeratorCache.removeAll(getReactor().activeModeratorCache);
		
		for (IFissionComponent component : getParts(IFissionComponent.class)) {
			if (component != null && component.isClusterRoot()) {
				iterateClusterSearch(component);
			}
		}
		
		for (long posLong : getReactor().activeModeratorCache) {
			for (EnumFacing dir : EnumFacing.VALUES) {
				IFissionComponent component = getPartMap(IFissionComponent.class).get(BlockPos.fromLong(posLong).offset(dir).toLong());
				if (component != null) {
					iterateClusterSearch(component);
				}
			}
		}
		
		for (long posLong : getReactor().activeReflectorCache) {
			for (EnumFacing dir : EnumFacing.VALUES) {
				IFissionComponent component = getPartMap(IFissionComponent.class).get(BlockPos.fromLong(posLong).offset(dir).toLong());
				if (component != null) {
					iterateClusterSearch(component);
				}
			}
		}
		
		super.refreshClusters();
	}
	
	@Override
	public void refreshClusterStats(FissionCluster cluster) {
		super.refreshClusterStats(cluster);
		
		for (IFissionComponent component : cluster.getComponentMap().values()) {
			if (component.isFunctional()) {
				cluster.componentCount++;
				if (component instanceof IFissionHeatingComponent) {
					cluster.rawHeating += ((IFissionHeatingComponent) component).getRawHeating();
					cluster.effectiveHeating += ((IFissionHeatingComponent) component).getEffectiveHeating();
					if (component instanceof IFissionFuelComponent) {
						cluster.fuelComponentCount++;
						cluster.totalHeatMult += ((IFissionFuelComponent) component).getHeatMultiplier();
						cluster.totalEfficiency += ((IFissionFuelComponent) component).getEfficiency();
					}
				}
				if (component instanceof IFissionCoolingComponent) {
					cluster.cooling += ((IFissionCoolingComponent) component).getCooling();
				}
			}
		}
		
		if (getReactor().refreshFlag) {
			return;
		}
		
		cluster.overcoolingEfficiencyFactor = cluster.cooling == 0L ? 1D : Math.min(1D, (double) (cluster.rawHeating + fission_cooling_efficiency_leniency) / (double) cluster.cooling);
		cluster.undercoolingLifetimeFactor = cluster.rawHeating == 0L ? 1D : Math.min(1D, (double) (cluster.cooling + fission_cooling_efficiency_leniency) / (double) cluster.rawHeating);
		cluster.effectiveHeating *= cluster.overcoolingEfficiencyFactor;
		cluster.totalEfficiency *= cluster.overcoolingEfficiencyFactor;
		cluster.meanHeatMult = cluster.fuelComponentCount == 0 ? 0D : (double) cluster.totalHeatMult / (double) cluster.fuelComponentCount;
		cluster.meanEfficiency = cluster.fuelComponentCount == 0 ? 0D : cluster.totalEfficiency / cluster.fuelComponentCount;
		
		for (IFissionComponent component : cluster.getComponentMap().values()) {
			if (component instanceof IFissionFuelComponent) {
				IFissionFuelComponent fuelComponent = (IFissionFuelComponent) component;
				fuelComponent.setUndercoolingLifetimeFactor(cluster.undercoolingLifetimeFactor);
			}
		}
	}
	
	@Override
	public void refreshReactorStats() {
		super.refreshReactorStats();
		
		for (FissionCluster cluster : getReactor().getClusterMap().values()) {
			// if (cluster.connectedToWall) {
			getReactor().usefulPartCount += cluster.componentCount;
			getReactor().fuelComponentCount += cluster.fuelComponentCount;
			getReactor().cooling += cluster.cooling;
			getReactor().rawHeating += cluster.rawHeating;
			// effectiveHeating += cluster.effectiveHeating;
			getReactor().totalHeatMult += cluster.totalHeatMult;
			getReactor().totalEfficiency += cluster.totalEfficiency;
			// }
		}
		
		getReactor().usefulPartCount += getReactor().passiveModeratorCache.size() + getReactor().activeModeratorCache.size() + getReactor().activeReflectorCache.size();
		double usefulPartRatio = (double) getReactor().usefulPartCount / (double) getReactor().getInteriorVolume();
		getReactor().sparsityEfficiencyMult = usefulPartRatio >= fission_sparsity_penalty_params[1] ? 1D : (1D - fission_sparsity_penalty_params[0]) * Math.sin(usefulPartRatio * Math.PI / (2D * fission_sparsity_penalty_params[1])) + fission_sparsity_penalty_params[0];
		// effectiveHeating *= sparsityEfficiencyMult;
		getReactor().totalEfficiency *= getReactor().sparsityEfficiencyMult;
		getReactor().meanHeatMult = getReactor().fuelComponentCount == 0 ? 0D : (double) getReactor().totalHeatMult / (double) getReactor().fuelComponentCount;
		getReactor().meanEfficiency = getReactor().fuelComponentCount == 0 ? 0D : getReactor().totalEfficiency / getReactor().fuelComponentCount;
		
		for (FissionCluster cluster : getReactor().getClusterMap().values()) {
			cluster.meanHeatingSpeedMultiplier = cluster.totalHeatingSpeedMultiplier = 0D;
			int clusterHeaters = 0;
			for (IFissionComponent component : cluster.getComponentMap().values()) {
				if (component instanceof TileSaltFissionHeater) {
					TileSaltFissionHeater heater = (TileSaltFissionHeater) component;
					heater.heatingSpeedMultiplier = cluster.meanEfficiency * getReactor().sparsityEfficiencyMult * (cluster.rawHeating >= cluster.cooling ? 1D : (double) cluster.rawHeating / (double) cluster.cooling);
					cluster.totalHeatingSpeedMultiplier += heater.heatingSpeedMultiplier;
					clusterHeaters++;
				}
			}
			cluster.meanHeatingSpeedMultiplier = clusterHeaters == 0 ? 0D : cluster.totalHeatingSpeedMultiplier / clusterHeaters;
			totalHeatingSpeedMultiplier += cluster.meanHeatingSpeedMultiplier;
		}
		meanHeatingSpeedMultiplier = getReactor().getClusterMap().isEmpty() ? 0D : totalHeatingSpeedMultiplier / getReactor().getClusterMap().size();
	}
	
	// Server
	
	@Override
	public boolean onUpdateServer() {
		// heatBuffer.changeHeatStored(Math.max(0, getNetClusterHeating()));
		
		if (heatBuffer.isFull() && fission_overheat) {
			heatBuffer.setHeatStored(0);
			casingMeltdown();
			return true;
		}
		
		for (FissionCluster cluster : getClusterMap().values()) {
			long netHeating = cluster.getNetHeating();
			if (netHeating > 0 && cluster.connectedToWall) {
				heatBuffer.changeHeatStored(netHeating);
			}
			else {
				cluster.heatBuffer.changeHeatStored(netHeating);
			}
			
			if (cluster.heatBuffer.isFull() && fission_overheat) {
				cluster.heatBuffer.setHeatStored(0);
				clusterMeltdown(cluster);
				return true;
			}
		}
		
		if (heatBuffer.getHeatStored() > 0) {
			updateEmergencyCooling();
		}
		
		return super.onUpdateServer();
	}
	
	public void updateEmergencyCooling() {
		if (!getReactor().isReactorOn) {
			refreshRecipe();
			if (canProcessInputs()) {
				produceProducts();
				return;
			}
		}
	}
	
	public void refreshRecipe() {
		emergencyCoolingRecipeInfo = NCRecipes.fission_emergency_cooling.getRecipeInfoFromInputs(new ArrayList<>(), tanks.subList(0, 1));
	}
	
	public boolean canProcessInputs() {
		if (!setRecipeStats()) {
			return false;
		}
		return canProduceProducts();
	}
	
	public boolean setRecipeStats() {
		if (emergencyCoolingRecipeInfo == null) {
			return false;
		}
		return true;
	}
	
	public boolean canProduceProducts() {
		ProcessorRecipe recipe = emergencyCoolingRecipeInfo.getRecipe();
		IFluidIngredient fluidProduct = recipe.getFluidProducts().get(0);
		int productSize = fluidProduct.getMaxStackSize(0);
		if (productSize <= 0 || fluidProduct.getStack() == null) {
			return false;
		}
		
		return tanks.get(1).isEmpty() || tanks.get(1).getFluid().isFluidEqual(fluidProduct.getStack());
	}
	
	public void produceProducts() {
		ProcessorRecipe recipe = emergencyCoolingRecipeInfo.getRecipe();
		int usedInput = NCMath.toInt(Math.min(tanks.get(0).getFluidAmount() / recipe.getEmergencyCoolingHeatPerInputMB(), Math.min(heatBuffer.getHeatStored(), FissionReactor.BASE_TANK_CAPACITY * getPartCount(TileFissionVent.class))));
		
		tanks.get(0).changeFluidAmount(-usedInput);
		if (tanks.get(0).getFluidAmount() <= 0) {
			tanks.get(0).setFluidStored(null);
		}
		
		IFluidIngredient fluidProduct = recipe.getFluidProducts().get(0);
		if (fluidProduct.getMaxStackSize(0) > 0) {
			if (tanks.get(1).isEmpty()) {
				tanks.get(1).setFluidStored(fluidProduct.getNextStack(0));
				tanks.get(1).setFluidAmount(usedInput);
			}
			else if (tanks.get(1).getFluid().isFluidEqual(fluidProduct.getStack())) {
				tanks.get(1).changeFluidAmount(usedInput);
			}
		}
		
		heatBuffer.changeHeatStored((long) (-usedInput * recipe.getEmergencyCoolingHeatPerInputMB()));
	}
	
	public long getNetClusterHeating() {
		return getReactor().rawHeating - getReactor().cooling;
	}
	
	@Override
	public void clusterMeltdown(FissionCluster cluster) {
		final Iterator<IFissionComponent> componentIterator = cluster.getComponentMap().values().iterator();
		while (componentIterator.hasNext()) {
			IFissionComponent component = componentIterator.next();
			componentIterator.remove();
			component.onClusterMeltdown();
		}
		super.clusterMeltdown(cluster);
	}
	
	// Component Logic
	
	@Override
	public void distributeFluxFromFuelComponent(IFissionFuelComponent fuelComponent, final ObjectSet<IFissionFuelComponent> fluxSearchCache, final Long2ObjectMap<IFissionComponent> lineFailCache, final Long2ObjectMap<IFissionComponent> assumedValidCache) {
		fuelComponent.defaultDistributeFlux(fluxSearchCache, lineFailCache, assumedValidCache);
	}
	
	@Override
	public IFissionFuelComponent getNextFuelComponent(IFissionFuelComponent fuelComponent, BlockPos pos) {
		return getPartMap(TileSaltFissionVessel.class).get(pos.toLong());
	}
	
	@Override
	public void refreshFuelComponentLocal(IFissionFuelComponent fuelComponent) {
		fuelComponent.defaultRefreshLocal();
	}
	
	@Override
	public void refreshFuelComponentModerators(IFissionFuelComponent fuelComponent, final Long2ObjectMap<IFissionComponent> assumedValidCache) {
		fuelComponent.defaultRefreshModerators(assumedValidCache);
	}
	
	@Override
	public boolean isShieldActiveModerator(TileFissionShield shield, boolean activeModeratorPos) {
		// return activeModeratorPos;
		return super.isShieldActiveModerator(shield, activeModeratorPos);
	}
	
	@Override
	public @Nonnull List<Tank> getVentTanks(List<Tank> backupTanks) {
		return getReactor().isAssembled() ? tanks : backupTanks;
	}
	
	// Client
	
	@Override
	public void onUpdateClient() {
		if (getReactor().isReactorOn) {
			int i = getPartCount(TileSaltFissionVessel.class);
			for (TileSaltFissionVessel vessel : getParts(TileSaltFissionVessel.class)) {
				if (rand.nextDouble() < 1D / i && playFissionSound(vessel)) {
					return;
				}
				if (vessel.isFunctional()) {
					i--;
				}
			}
		}
	}
	
	// NBT
	
	@Override
	public void writeToLogicTag(NBTTagCompound logicTag, SyncReason syncReason) {
		super.writeToLogicTag(logicTag, syncReason);
		writeTanks(tanks, logicTag, "tanks");
		logicTag.setInteger("heaterCount", heaterCount);
		logicTag.setDouble("meanHeatingSpeedMultiplier", meanHeatingSpeedMultiplier);
		logicTag.setDouble("totalHeatingSpeedMultiplier", totalHeatingSpeedMultiplier);
	}
	
	@Override
	public void readFromLogicTag(NBTTagCompound logicTag, SyncReason syncReason) {
		super.readFromLogicTag(logicTag, syncReason);
		readTanks(tanks, logicTag, "tanks");
		heaterCount = logicTag.getInteger("heaterCount");
		meanHeatingSpeedMultiplier = logicTag.getDouble("meanHeatingSpeedMultiplier");
		totalHeatingSpeedMultiplier = logicTag.getDouble("totalHeatingSpeedMultiplier");
	}
	
	// Packets
	
	@Override
	public SaltFissionUpdatePacket getUpdatePacket() {
		return new SaltFissionUpdatePacket(getReactor().controller.getTilePos(), getReactor().isReactorOn, heatBuffer, getReactor().clusterCount, getReactor().cooling, getReactor().rawHeating, getReactor().totalHeatMult, getReactor().meanHeatMult, getReactor().fuelComponentCount, getReactor().usefulPartCount, getReactor().totalEfficiency, getReactor().meanEfficiency, getReactor().sparsityEfficiencyMult, meanHeatingSpeedMultiplier, totalHeatingSpeedMultiplier);
	}
	
	@Override
	public void onPacket(FissionUpdatePacket message) {
		super.onPacket(message);
		if (message instanceof SaltFissionUpdatePacket) {
			SaltFissionUpdatePacket packet = (SaltFissionUpdatePacket) message;
			meanHeatingSpeedMultiplier = packet.meanHeatingSpeedMultiplier;
			totalHeatingSpeedMultiplier = packet.totalHeatingSpeedMultiplier;
		}
	}
	
	@Override
	public void clearAllMaterial() {
		super.clearAllMaterial();
		for (Tank tank : tanks) {
			tank.setFluidStored(null);
		}
	}
}
