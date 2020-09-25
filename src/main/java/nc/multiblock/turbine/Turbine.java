package nc.multiblock.turbine;

import static nc.config.NCConfig.turbine_max_size;

import java.lang.reflect.Constructor;
import java.util.List;

import javax.annotation.Nonnull;
import javax.vecmath.Vector3f;

import com.google.common.collect.Lists;

import it.unimi.dsi.fastutil.doubles.*;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.objects.*;
import nc.Global;
import nc.handler.SoundHandler.SoundInfo;
import nc.multiblock.*;
import nc.multiblock.cuboidal.CuboidalMultiblock;
import nc.multiblock.network.*;
import nc.multiblock.tile.ITileMultiblockPart;
import nc.multiblock.tile.TileBeefAbstract.SyncReason;
import nc.multiblock.turbine.TurbineRotorBladeUtil.*;
import nc.multiblock.turbine.tile.*;
import nc.recipe.*;
import nc.tile.internal.energy.EnergyStorage;
import nc.tile.internal.fluid.Tank;
import nc.util.NCMath;
import net.minecraft.block.state.IBlockState;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.BlockPos.MutableBlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.*;

public class Turbine extends CuboidalMultiblock<ITurbinePart, TurbineUpdatePacket> implements ILogicMultiblock<TurbineLogic, ITurbinePart> {
	
	public static final ObjectSet<Class<? extends ITurbinePart>> PART_CLASSES = new ObjectOpenHashSet<>();
	public static final Object2ObjectMap<String, Constructor<? extends TurbineLogic>> LOGIC_MAP = new Object2ObjectOpenHashMap<>();
	
	protected @Nonnull TurbineLogic logic = new TurbineLogic(this);
	
	protected final PartSuperMap<ITurbinePart> partSuperMap = new PartSuperMap<>();
	
	public ITurbineController controller;
	
	public final EnergyStorage energyStorage = new EnergyStorage(BASE_MAX_ENERGY);
	public final List<Tank> tanks = Lists.newArrayList(new Tank(BASE_MAX_INPUT, NCRecipes.turbine_valid_fluids.get(0)), new Tank(BASE_MAX_OUTPUT, null));
	public static final int BASE_MAX_ENERGY = 64000, BASE_MAX_INPUT = 4000, BASE_MAX_OUTPUT = 16000;
	
	public RecipeInfo<ProcessorRecipe> recipeInfo;
	
	public boolean isTurbineOn, computerActivated, isProcessing;
	public double power = 0D, conductivity = 0D, rotorEfficiency = 0D, powerBonus = 0D;
	public double rawPower = 0D, rawLimitPower = 0D, rawMaxPower = 0D;
	public EnumFacing flowDir = null;
	public int shaftWidth = 0, inertia = 0, bladeLength = 0, noBladeSets = 0, recipeInputRate = 0, dynamoCoilCount = 0, dynamoCoilCountOpposite = 0;
	public double totalExpansionLevel = 1D, idealTotalExpansionLevel = 1D, basePowerPerMB = 0D, recipeInputRateFP = 0D;
	public double minBladeExpansionCoefficient = Double.MAX_VALUE;
	public double maxBladeExpansionCoefficient = 1D;
	public double minStatorExpansionCoefficient = 1D;
	public double maxStatorExpansionCoefficient = Double.MIN_VALUE;
	public int effectiveMaxLength = turbine_max_size;
	public double bearingTension = 0D;
	public DoubleList expansionLevels = new DoubleArrayList(), rawBladeEfficiencies = new DoubleArrayList();
	
	@SideOnly(Side.CLIENT)
	public List<SoundInfo> activeSounds;
	public int soundCount = rand.nextInt(20);
	public boolean refreshSoundInfo = true;
	public float prevAngVel = 0F;
	
	public String particleEffect = "cloud";
	public double particleSpeedMult = 1D / 23.2D;
	public float angVel = 0F, rotorAngle = 0F;
	public long prevRenderTime = 0L;
	public Iterable<MutableBlockPos>[] inputPlane = new Iterable[4];
	
	public boolean shouldRenderRotor = false;
	public BlockPos[] bladePosArray = null;
	public Vector3f[] renderPosArray = null;
	public float[] bladeAngleArray = null;
	public IBlockState[] rotorStateArray = null;
	public IntList bladeDepths = null, statorDepths = null;
	
	public Turbine(World world) {
		super(world);
		for (Class<? extends ITurbinePart> clazz : PART_CLASSES) {
			partSuperMap.equip(clazz);
		}
	}
	
	@Override
	public @Nonnull TurbineLogic getLogic() {
		return logic;
	}
	
	@Override
	public void setLogic(String logicID) {
		if (logicID.equals(logic.getID())) {
			return;
		}
		logic = getNewLogic(LOGIC_MAP.get(logicID));
	}
	
	// Multiblock Part Getters
	
	@Override
	public PartSuperMap<ITurbinePart> getPartSuperMap() {
		return partSuperMap;
	}
	
	// Multiblock Size Limits
	
	@Override
	protected int getMinimumInteriorLength() {
		return logic.getMinimumInteriorLength();
	}
	
	@Override
	protected int getMaximumInteriorLength() {
		return logic.getMaximumInteriorLength();
	}
	
	@Override
	protected int getMinimumNumberOfBlocksForAssembledMachine() {
		return NCMath.hollowCuboid(Math.max(5, getMinimumInteriorLength() + 2), Math.max(5, getMinimumInteriorLength() + 2), getMinimumInteriorLength() + 2);
	}
	
	// Multiblock Methods
	
	@Override
	public void onAttachedPartWithMultiblockData(ITileMultiblockPart part, NBTTagCompound data) {
		logic.onAttachedPartWithMultiblockData(part, data);
		syncDataFrom(data, SyncReason.FullSync);
	}
	
	@Override
	protected void onBlockAdded(ITileMultiblockPart newPart) {
		onPartAdded(newPart);
		logic.onBlockAdded(newPart);
	}
	
	@Override
	protected void onBlockRemoved(ITileMultiblockPart oldPart) {
		onPartRemoved(oldPart);
		logic.onBlockRemoved(oldPart);
	}
	
	@Override
	protected void onMachineAssembled() {
		logic.onMachineAssembled();
	}
	
	@Override
	protected void onMachineRestored() {
		logic.onMachineRestored();
	}
	
	@Override
	protected void onMachinePaused() {
		logic.onMachinePaused();
	}
	
	@Override
	protected void onMachineDisassembled() {
		logic.onMachineDisassembled();
	}
	
	@Override
	protected boolean isMachineWhole(Multiblock multiblock) {
		shouldRenderRotor = false;
		return setLogic(multiblock) && super.isMachineWhole(multiblock) && logic.isMachineWhole(multiblock);
	}
	
	public boolean setLogic(Multiblock multiblock) {
		if (getPartMap(ITurbineController.class).isEmpty()) {
			multiblock.setLastError(Global.MOD_ID + ".multiblock_validation.no_controller", null);
			return false;
		}
		if (getPartCount(ITurbineController.class) > 1) {
			multiblock.setLastError(Global.MOD_ID + ".multiblock_validation.too_many_controllers", null);
			return false;
		}
		
		for (ITurbineController contr : getParts(ITurbineController.class)) {
			controller = contr;
		}
		
		setLogic(controller.getLogicID());
		
		return true;
	}
	
	@Override
	public void checkIfMachineIsWhole() {
		super.checkIfMachineIsWhole();
		if (WORLD.isRemote && (!isTurbineOn || assemblyState != AssemblyState.Assembled)) {
			logic.stopSounds();
		}
	}
	
	@Override
	protected void onAssimilate(Multiblock assimilated) {
		logic.onAssimilate(assimilated);
	}
	
	@Override
	protected void onAssimilated(Multiblock assimilator) {
		logic.onAssimilated(assimilator);
	}
	
	public int getFlowLength() {
		return getInteriorLength(flowDir);
	}
	
	protected int getBladeArea() {
		return 4 * shaftWidth * bladeLength;
	}
	
	protected int getBladeVolume() {
		return getBladeArea() * noBladeSets;
	}
	
	public double getRotorRadius() {
		return bladeLength + shaftWidth / 2D;
	}
	
	public int getMinimumBladeArea() {
		return 4 * Math.max(1, getMinimumInteriorLength() - 2);
	}
	
	public int getMinimumBladeVolume() {
		return getBladeArea() * getMinimumInteriorLength();
	}
	
	// Modified Kurtchekov stuff!
	
	protected ITurbineRotorBlade getBlade(BlockPos pos) {
		long posLong = pos.toLong();
		TileTurbineRotorBlade blade = getPartMap(TileTurbineRotorBlade.class).get(posLong);
		return blade == null ? getPartMap(TileTurbineRotorStator.class).get(posLong) : blade;
	}
	
	public TurbinePartDir getShaftDir() {
		if (flowDir == null) {
			return TurbinePartDir.Y;
		}
		switch (flowDir.getAxis()) {
			case Y:
				return TurbinePartDir.Y;
			case Z:
				return TurbinePartDir.Z;
			case X:
				return TurbinePartDir.X;
			default:
				return TurbinePartDir.Y;
		}
	}
	
	public TurbinePartDir getBladeDir(PlaneDir planeDir) {
		if (flowDir == null) {
			return TurbinePartDir.Y;
		}
		switch (flowDir.getAxis()) {
			case Y:
				switch (planeDir) {
					case U:
						return TurbinePartDir.Z;
					case V:
						return TurbinePartDir.X;
				}
			case Z:
				switch (planeDir) {
					case U:
						return TurbinePartDir.X;
					case V:
						return TurbinePartDir.Y;
				}
			case X:
				switch (planeDir) {
					case U:
						return TurbinePartDir.Y;
					case V:
						return TurbinePartDir.Z;
				}
			default:
				return TurbinePartDir.Y;
		}
	}
	
	public enum PlaneDir {
		U,
		V;
	}
	
	// End of modified Kurtchekov stuff!
	
	// Server
	
	@Override
	protected boolean updateServer() {
		boolean flag = false;
		// setIsTurbineOn();
		if (logic.onUpdateServer()) {
			flag = true;
		}
		return flag;
	}
	
	// Client
	
	@Override
	protected void updateClient() {
		logic.onUpdateClient();
	}
	
	// NBT
	
	@Override
	public void syncDataTo(NBTTagCompound data, SyncReason syncReason) {
		writeEnergy(energyStorage, data, "energyStorage");
		writeTanks(tanks, data, "tanks");
		data.setBoolean("isTurbineOn", isTurbineOn);
		data.setBoolean("computerActivated", computerActivated);
		data.setDouble("power", power);
		data.setDouble("rawPower", rawPower);
		data.setDouble("rawLimitPower", rawLimitPower);
		data.setDouble("rawMaxPower", rawMaxPower);
		data.setDouble("conductivity", conductivity);
		data.setDouble("rotorEfficiency", rotorEfficiency);
		data.setDouble("powerBonus", powerBonus);
		data.setString("particleEffect", particleEffect);
		data.setDouble("particleSpeedMult", particleSpeedMult);
		data.setFloat("angVel", angVel);
		data.setInteger("flowDir", flowDir == null ? -1 : flowDir.getIndex());
		data.setInteger("shaftWidth", shaftWidth);
		data.setInteger("shaftVolume", inertia);
		data.setInteger("bladeLength", bladeLength);
		data.setInteger("noBladeSets", noBladeSets);
		data.setInteger("recipeInputRate", recipeInputRate);
		data.setInteger("dynamoCoilCount", dynamoCoilCount);
		data.setInteger("dynamoCoilCountOpposite", dynamoCoilCountOpposite);
		data.setDouble("totalExpansionLevel", totalExpansionLevel);
		data.setDouble("idealTotalExpansionLevel", idealTotalExpansionLevel);
		data.setDouble("basePowerPerMB", basePowerPerMB);
		data.setDouble("recipeInputRateFP", recipeInputRateFP);
		data.setDouble("minBladeExpansionCoefficient", minBladeExpansionCoefficient);
		data.setDouble("maxBladeExpansionCoefficient", maxBladeExpansionCoefficient);
		data.setDouble("minStatorExpansionCoefficient", minStatorExpansionCoefficient);
		data.setDouble("maxStatorExpansionCoefficient", maxStatorExpansionCoefficient);
		data.setInteger("effectiveMaxLength", effectiveMaxLength);
		data.setDouble("bearingTension", bearingTension);
		data.setInteger("expansionLevelsSize", expansionLevels.size());
		for (int i = 0; i < expansionLevels.size(); i++) {
			data.setDouble("expansionLevels" + i, expansionLevels.get(i));
		}
		data.setInteger("rawBladeEfficienciesSize", rawBladeEfficiencies.size());
		for (int i = 0; i < rawBladeEfficiencies.size(); i++) {
			data.setDouble("rawBladeEfficiencies" + i, rawBladeEfficiencies.get(i));
		}
		data.setBoolean("isProcessing", isProcessing);
		
		writeLogicNBT(data, syncReason);
	}
	
	@Override
	public void syncDataFrom(NBTTagCompound data, SyncReason syncReason) {
		readEnergy(energyStorage, data, "energyStorage");
		readTanks(tanks, data, "turbineTanks");
		isTurbineOn = data.getBoolean("isTurbineOn");
		computerActivated = data.getBoolean("computerActivated");
		power = data.getDouble("power");
		rawPower = data.getDouble("rawPower");
		rawLimitPower = data.getDouble("rawLimitPower");
		rawMaxPower = data.getDouble("rawMaxPower");
		conductivity = data.getDouble("conductivity");
		rotorEfficiency = data.getDouble("rotorEfficiency");
		powerBonus = data.getDouble("powerBonus");
		particleEffect = data.getString("particleEffect");
		particleSpeedMult = data.getDouble("particleSpeedMult");
		angVel = data.getFloat("angVel");
		flowDir = data.getInteger("flowDir") < 0 ? null : EnumFacing.VALUES[data.getInteger("flowDir")];
		shaftWidth = data.getInteger("shaftWidth");
		inertia = data.getInteger("shaftVolume");
		bladeLength = data.getInteger("bladeLength");
		noBladeSets = data.getInteger("noBladeSets");
		recipeInputRate = data.getInteger("recipeInputRate");
		dynamoCoilCount = data.getInteger("dynamoCoilCount");
		dynamoCoilCountOpposite = data.getInteger("dynamoCoilCountOpposite");
		totalExpansionLevel = data.getDouble("totalExpansionLevel");
		idealTotalExpansionLevel = data.getDouble("idealTotalExpansionLevel");
		basePowerPerMB = data.getDouble("basePowerPerMB");
		recipeInputRateFP = data.getDouble("recipeInputRateFP");
		minBladeExpansionCoefficient = data.getDouble("minBladeExpansionCoefficient");
		maxBladeExpansionCoefficient = data.getDouble("maxBladeExpansionCoefficient");
		minStatorExpansionCoefficient = data.getDouble("minStatorExpansionCoefficient");
		maxStatorExpansionCoefficient = data.getDouble("maxStatorExpansionCoefficient");
		effectiveMaxLength = data.getInteger("effectiveMaxLength");
		bearingTension = data.getDouble("bearingTension");
		expansionLevels = new DoubleArrayList();
		if (data.hasKey("expansionLevelsSize")) {
			for (int i = 0; i < data.getInteger("expansionLevelsSize"); i++) {
				expansionLevels.add(data.getDouble("expansionLevels" + i));
			}
		}
		rawBladeEfficiencies = new DoubleArrayList();
		if (data.hasKey("rawBladeEfficienciesSize")) {
			for (int i = 0; i < data.getInteger("rawBladeEfficienciesSize"); i++) {
				rawBladeEfficiencies.add(data.getDouble("rawBladeEfficiencies" + i));
			}
		}
		isProcessing = data.getBoolean("isProcessing");
		
		readLogicNBT(data, syncReason);
	}
	
	// Packets
	
	@Override
	protected TurbineUpdatePacket getUpdatePacket() {
		return logic.getUpdatePacket();
	}
	
	@Override
	public void onPacket(TurbineUpdatePacket message) {
		logic.onPacket(message);
	}
	
	protected TurbineRenderPacket getRenderPacket() {
		return logic.getRenderPacket();
	}
	
	public void onRenderPacket(TurbineRenderPacket message) {
		logic.onRenderPacket(message);
	}
	
	public TurbineFormPacket getFormPacket() {
		return logic.getFormPacket();
	}
	
	public void onFormPacket(TurbineFormPacket message) {
		logic.onFormPacket(message);
	}
	
	@Override
	public void clearAllMaterial() {
		logic.clearAllMaterial();
		
		super.clearAllMaterial();
	}
	
	// Multiblock Validators
	
	@Override
	protected boolean isBlockGoodForInterior(World world, int x, int y, int z, Multiblock multiblock) {
		return logic.isBlockGoodForInterior(world, x, y, z, multiblock);
	}
}
