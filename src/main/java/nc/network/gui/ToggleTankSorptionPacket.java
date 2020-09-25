package nc.network.gui;

import io.netty.buffer.ByteBuf;
import nc.tile.fluid.ITileFluid;
import nc.tile.internal.fluid.TankSorption;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.network.simpleimpl.*;
import net.minecraftforge.fml.relauncher.Side;

public class ToggleTankSorptionPacket implements IMessage {
	
	boolean messageValid;
	
	BlockPos pos;
	int side;
	int tank;
	int sorption;
	
	public ToggleTankSorptionPacket() {
		messageValid = false;
	}
	
	public ToggleTankSorptionPacket(ITileFluid machine, EnumFacing side, int tank, TankSorption sorption) {
		pos = machine.getTilePos();
		this.side = side.getIndex();
		this.tank = tank;
		this.sorption = sorption.ordinal();
		messageValid = true;
	}
	
	@Override
	public void fromBytes(ByteBuf buf) {
		try {
			pos = new BlockPos(buf.readInt(), buf.readInt(), buf.readInt());
			side = buf.readInt();
			tank = buf.readInt();
			sorption = buf.readInt();
		}
		catch (IndexOutOfBoundsException e) {
			e.printStackTrace();
			return;
		}
		messageValid = true;
	}
	
	@Override
	public void toBytes(ByteBuf buf) {
		if (!messageValid) {
			return;
		}
		buf.writeInt(pos.getX());
		buf.writeInt(pos.getY());
		buf.writeInt(pos.getZ());
		buf.writeInt(side);
		buf.writeInt(tank);
		buf.writeInt(sorption);
	}
	
	public static class Handler implements IMessageHandler<ToggleTankSorptionPacket, IMessage> {
		
		@Override
		public IMessage onMessage(ToggleTankSorptionPacket message, MessageContext ctx) {
			if (!message.messageValid && ctx.side != Side.SERVER) {
				return null;
			}
			FMLCommonHandler.instance().getWorldThread(ctx.netHandler).addScheduledTask(() -> processMessage(message, ctx));
			return null;
		}
		
		void processMessage(ToggleTankSorptionPacket message, MessageContext ctx) {
			TileEntity tile = ctx.getServerHandler().player.getServerWorld().getTileEntity(message.pos);
			if (tile instanceof ITileFluid) {
				ITileFluid machine = (ITileFluid) tile;
				machine.setTankSorption(EnumFacing.byIndex(message.side), message.tank, TankSorption.values()[message.sorption]);
				machine.markDirtyAndNotify(true);
			}
		}
	}
}
