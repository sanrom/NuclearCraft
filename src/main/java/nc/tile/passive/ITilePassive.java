package nc.tile.passive;

import nc.tile.energy.ITileEnergy;
import nc.tile.fluid.ITileFluid;
import nc.tile.inventory.ITileInventory;
import net.minecraft.util.ITickable;

public interface ITilePassive extends ITickable, ITileEnergy, ITileInventory, ITileFluid {
	
	public double getEnergyRate();
	
	public double getItemRate();
	
	public double getFluidRate();
	
	public boolean canPushEnergyTo();
	
	public boolean canPushItemsTo();
	
	public boolean canPushFluidsTo();
}
