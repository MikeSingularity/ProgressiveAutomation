package com.vanhal.progressiveautomation.entities.miner;

import com.vanhal.progressiveautomation.ProgressiveAutomation;
import com.vanhal.progressiveautomation.entities.UpgradeableTileEntity;
import com.vanhal.progressiveautomation.items.PAItems;
import com.vanhal.progressiveautomation.ref.ToolHelper;
import com.vanhal.progressiveautomation.upgrades.UpgradeType;
import com.vanhal.progressiveautomation.util.Point2I;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
//import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.init.Blocks;
import net.minecraft.init.Enchantments;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.common.ForgeHooks;

public class TileMiner extends UpgradeableTileEntity {
	protected int totalMineBlocks = -1;
	protected int currentMineBlocks = 0;

	//mining vars
	protected int currentRange = 0;
	protected int currentY     = 0;
	protected int ceilingY     = -1;
	protected int miningTicks  = 0;
	protected int elapsedTicks = 0;

	protected BlockPos currentPos = null;

	protected int[] min_max_pos = null;

    protected Block fillerBlock = Blocks.COBBLESTONE;
    protected boolean quarryFillPass = false;

    protected int[] lastTool = new int[4];
	private int previousUpgrades;
    
    protected enum State { STARTING, CALCULATING, FILLING, MINING, DONE };
    protected State status = State.STARTING;
    
	public TileMiner() {
		super(13);
		setUpgradeLevel(ToolHelper.LEVEL_WOOD);
		setAllowedUpgrades(UpgradeType.WOODEN, UpgradeType.WITHER, UpgradeType.COBBLE_GEN, UpgradeType.FILLER, UpgradeType.QUARRY);
		
		//set the slots
		SLOT_PICKAXE = 2;
		SLOT_SHOVEL = 3;
		SLOT_UPGRADE = 4;

		// Initialize
		lastTool[SLOT_PICKAXE] = lastTool[SLOT_SHOVEL] = -1;
	}

	public void writeCommonNBT(NBTTagCompound nbt) {
		super.writeCommonNBT(nbt);
		nbt.setInteger("MineBlocks", totalMineBlocks);
		nbt.setInteger("MinedBlocks", currentMineBlocks);
	}

	public void readCommonNBT(NBTTagCompound nbt) {
		super.readCommonNBT(nbt);
		if (nbt.hasKey("MineBlocks")) totalMineBlocks = nbt.getInteger("MineBlocks");
		if (nbt.hasKey("MinedBlocks")) currentMineBlocks = nbt.getInteger("MinedBlocks");
	}

	public void update() {
		super.update();
		if (worldObj.isRemote) return; 

		if (status != State.DONE) {
			checkForChanges();
			checkInventory();
		}
		
		// If we're full, don't do anything else
		if (isFull() || !isBurning()) return;
		
		switch (status) {
			case STARTING:    // Starting up
				scanBlocks();
				resetMining();
				// Yes, fall through to calculating
				
			case CALCULATING: // Get next block to break
				//ProgressiveAutomation.logger.info("Calculating called");
				int tool = 0;
				IBlockState state = null;
				Block currBlock = null;

				// Reset the counter
				elapsedTicks = 0;
				
				while (tool < 1) {
					currentPos  = nextBlock();
					if (currentPos == null) {
						//ProgressiveAutomation.logger.info("Calculated next block as null.");
						scanBlocks();
						resetMining();
						if (isDone()) {
							status = State.DONE;
						}
						return;
					}
					//ProgressiveAutomation.logger.info("Calculated next block: "+currentPos.getX()+", "+currentPos.getY()+", "+currentPos.getZ());
					state     = worldObj.getBlockState( currentPos );
					currBlock = state.getBlock();
				
					// get the tool to use
					tool = miningTool( currentPos, state, currBlock );
				
					if (quarryFillPass && tool != 4)
						tool = 0;
				}
				
				// How long will it take
				miningTicks = breakTicks( currentPos, tool, state, currBlock );
				
				status = !quarryFillPass ? State.MINING : State.FILLING;
				break;
			
			case FILLING:
			case MINING:      // Mining the block
				// Have we waited long enough?
				if (elapsedTicks < miningTicks) {
					elapsedTicks++;
					break;
				}

				// If we couldn't acquire the block, try again
				if (status == State.MINING && !acquireBlock( currentPos ))
					break;
				
				// if we couldn't place a filler block, start over
				if (status == State.FILLING && !placeFillerBlock(currentPos, Blocks.COBBLESTONE)) {
					status = State.STARTING;
					break;
				}
				
				// Get the next block to mine/place
				status = State.CALCULATING;
				break;
			
			case DONE:        // All done
				elapsedTicks++;
				if (elapsedTicks % 10 == 0) {
					checkForChanges();
					checkInventory();
				}
				if (elapsedTicks >= 20) {
					elapsedTicks = 0;
					scanBlocks();
				}
				return;
		}
	}
	
	/* Look over every element in the range
	 * 
	 * This method examines each block in the range to determine the total
	 * number of mine-able blocks in the area.
	 * 
	 */
	public void scanBlocks() {
		
		//ProgressiveAutomation.logger.info("scanBlocks called");
		int total      = 0;
		int current    = 0;
		int range      = getRange();
		
		int minerX     = this.pos.getX();
		int minerZ     = this.pos.getZ();

		// Set ceilingY so we don't have to reference this all the time
		ceilingY = this.pos.getY() - 1;

		for (int i = range; i > 0; i--) {
			
			Point2I currentPoint = spiral(i, minerX, minerZ);
			
			for (int newY = ceilingY; newY >= 0; newY--) {

				switch (miningTool(currentPoint.getX(), newY, currentPoint.getY())) {
					case -1: // Filler block
						total++;
						current++;
						break;
					case 0:  // Unbreakable
						break;
					case 4:  // Liquid or Air
						total++;
					default: // Mine-able
						total++;
						break;
				}
			}
		}
		
		boolean reset = false;
		if (total != totalMineBlocks) {
			totalMineBlocks = total;
			addPartialUpdate("MineBlocks", totalMineBlocks);
			reset = true;
		}
		if (current != currentMineBlocks) {
			currentMineBlocks = current;
			addPartialUpdate("MinedBlocks", currentMineBlocks);
			reset = true;
		}
		notifyUpdate();
		
		if (reset) resetMining();
	}

	/* Turn a spiral range and Y coordinate into a BlockPos object
	 * 
	 * Uses the tileentity's current X & Z coordinate as a center
	 */
	private BlockPos spiralPos( int range, int Y ) {
		Point2I spiralXY = spiral(range, pos.getX(), pos.getZ());
		return new BlockPos( spiralXY.getX(), Y, spiralXY.getY() );
	}
	
	public int miningTool(int x, int y, int z) {
		BlockPos minePos = new BlockPos(x, y, z);
		return miningTool( minePos );
	}
	
	public int miningTool( BlockPos minePos ) {
		IBlockState tryState = worldObj.getBlockState(minePos);
		Block tryBlock = tryState.getBlock();

		return miningTool( minePos, tryState, tryBlock );
	}
	
	/* Test a block to determine its mine-ability
	 * 
	 * Returns -1 if it is the filler type
	 *          0 if it isn't mine-able,
	 *          1 if its hand mine-able (e.g. a chest) 
	 *          2 if its mine-able with a pick
	 *          3 if its mine-able with a shovel
	 *          4 if it should just be filled
	 */
	public int miningTool( BlockPos minePos, IBlockState tryState, Block tryBlock ) {
		//ProgressiveAutomation.logger.info("miningTool called");
		boolean quarry = hasUpgrade(UpgradeType.QUARRY);
		boolean filler = !quarry && hasUpgrade(UpgradeType.FILLER);
		
		
		if (tryBlock == null)
			return 0;

		// If this is our filler block, just bail now
		if (tryBlock == fillerBlock)
			return -1;
		
		// If its an air block and we have the filler upgrade, return 4 otherwise 0
		if (tryBlock.isAir(tryState, worldObj, minePos))
			return (filler ? 4 : 0);

		// Liquids
		if (tryBlock.getMaterial(tryState).isLiquid())
			return filler || quarry ? 4 : 0;

		// If we're filling and it isn't a liquid, move on
		if (quarryFillPass) return 0;
				
		// Unbreakable blocks
		if (tryBlock.getBlockHardness(tryState, worldObj, minePos) < 0)
			return 0;

		String toolName = tryBlock.getHarvestTool(tryState); 
		// this is compatibility for chisel 1
		if (toolName == "chisel")  
			return 2;
		if (toolName == "pickaxe")
			return ForgeHooks.canToolHarvestBlock(worldObj, minePos, getStackInSlot(2)) ? 2 : 0;
		if (toolName == "shovel")
			return ForgeHooks.canToolHarvestBlock(worldObj, minePos, getStackInSlot(3)) ? 3 : 0;

		return 1;
	}

	// Try to get the block at blockPos if it matches the expected block, otherwise return false
	private boolean acquireBlock( BlockPos blockPos ) {

		//ProgressiveAutomation.logger.info("acquireBlock called");
		IBlockState blockState = worldObj.getBlockState(blockPos);
		Block       currBlock  = blockState.getBlock();
		int tool  = miningTool( blockPos, blockState, currBlock );
		
		// Did we have to fill in any blocks to remove surrounding liquid?
		if (hasUpgrade(UpgradeType.QUARRY) && fixLiquid( blockPos ))
			return false;
		
		// If this block isn't harvestable, move to the next
		if (tool == 0) {
			status = State.CALCULATING;
			return false;
		}

		// Have we mined long enough?
		int ticks = breakTicks( blockPos, tool, blockState, currBlock );
		if (elapsedTicks < ticks) {
			miningTicks = ticks;  // mine some more!
			return false;
		}

		switch (tool) {
			// Hands can't have silk touch or fortune
			case 1:
				harvestBlock( blockPos, currBlock, blockState, false, 0 );
				break;
			// Check pick-axes and shovels, though
			case 2:
			case 3:
				boolean silk    = EnchantmentHelper.getEnchantmentLevel(Enchantments.SILK_TOUCH, slots[tool]) > 0;
				int     fortune = EnchantmentHelper.getEnchantmentLevel(Enchantments.FORTUNE,    slots[tool]);
				
				if (harvestBlock( blockPos, currBlock, blockState, silk, fortune ))
					// Don't forget to damage the tool if appropriate
					if (ToolHelper.damageTool(slots[tool], worldObj, blockPos.getX(), blockPos.getY(), blockPos.getZ())) {
						destroyTool(tool);
					}
				break;
		}

		//remove the block and entity if there is one
		placeFillerBlock( blockPos, fillerBlock );
		
		return true;
	}

	/* Actually get the item for the block.
	 * 
	 * Takes into account fortune and silk touch, if provided
	 */
	
	private boolean harvestBlock(BlockPos pos, Block block, IBlockState state,  boolean silkTouch, int fortune ) {
		//ProgressiveAutomation.logger.info("harvestBlock called");
		if (block == Blocks.AIR)
			return false;
		
		// Get the inventory of anything under it
		if (worldObj.getTileEntity(pos) instanceof IInventory) {
			
			IInventory inv = (IInventory) worldObj.getTileEntity(pos);
			
			for (int i = 0; i < inv.getSizeInventory(); i++) {
				if (inv.getStackInSlot(i) != null) {
					addToInventory(inv.getStackInSlot(i));
					inv.setInventorySlotContents(i, null);
				}
			}
		}

		// Silk touch the block if we have it
		if (silkTouch) {
	        Item item = Item.getItemFromBlock(block);

	        int subtype = item != null && item.getHasSubtypes()
	        			    ? block.getMetaFromState(state)
	        		        : 0;

			ItemStack addItem = new ItemStack(block, 1, subtype);
			addToInventory(addItem);
			return true;
		} 

		// Get the drops
		for (ItemStack item : block.getDrops(worldObj, pos, state, fortune))
			addToInventory(item);
		
		return true;
	}
	

	/*
	 * Replaces the block at BlockPos with the filler block provided
	 * or the default (fillerBlock) if filler == null
	 */
	private boolean placeFillerBlock(BlockPos blockPos, Block filler) {
		//ProgressiveAutomation.logger.info("Placing Filler block");
		
		if (filler == null) filler = fillerBlock;
		boolean cobble = (filler == Blocks.COBBLESTONE);
		
		// If we're filling with cobblestone, reduce the stack
		if (cobble && !haveCobble())
			return false;
		
		//remove the block and entity if there is one
		worldObj.removeTileEntity( blockPos );
		worldObj.setBlockState( blockPos, filler.getDefaultState());

		// Don't advance if we're not using our "normal" filler
		if (filler == fillerBlock) {
			currentMineBlocks++;
			addPartialUpdate("MinedBlocks", currentMineBlocks);
		}

		if (cobble) {
			slots[1].stackSize--;
			if (slots[1].stackSize == 0)
				slots[1] = null;
		}
	
		return true;
	}
	
	/*
	 * Helper function to acquire block and state data if needed
	 */
	public int breakTicks(BlockPos pos, int tool) {
		IBlockState state = worldObj.getBlockState(pos);
		Block block = state.getBlock();

		return breakTicks( pos, tool, state, block );
	}
	
	
	// Determine how long it will take to mine the block at pos with the tool specified.
	public int breakTicks(BlockPos blockPos, int tool, IBlockState state, Block block ) {
		int duration = 0;
		
		//ProgressiveAutomation.logger.info("breakTicks called for "+blockPos.getX()+", "+blockPos.getY()+", "+blockPos.getZ());
		int normal = (int)Math.ceil( block.getBlockHardness(state, worldObj, blockPos) * 1.5 * 20 ) ;
		
		switch (tool) {
			case 1: // Hands
				duration = normal;
				break;
				
			case 2:  // Pickaxe
			case 3:  // Shovel
				float miningSpeed = ToolHelper.getDigSpeed( slots[tool], state );

				int eff = EnchantmentHelper.getEnchantmentLevel(Enchantments.EFFICIENCY, slots[tool]);

				// Calculate the efficiency increase
				if (eff > 0)
					miningSpeed *= Math.pow(1.3f, (eff + 1));

				duration = (int) Math.ceil(normal / miningSpeed);
				break;
				
			case 4: // Liquid
				duration = 1;
				break;
				
			default:
				break;
		}
		
		//ProgressiveAutomation.logger.info("Block type: "+block.getUnlocalizedName()+" tool: "+tool+" duration: "+duration);
		
		return duration;
	}

	/* 
	 * Return the next BlockPos from our current position
	 */
	public BlockPos nextBlock() {
		// ProgressiveAutomation.logger.info("nextBlock called");
		
		// If we've already mined all the blocks, return null
		if (currentY <= 0 && currentRange < 1)
			return null;

		BlockPos newPos = spiralPos( currentRange, currentY );
		// String log = "Range: "+currentRange+", currentY: "+currentY;
		
		// The quarry uses different logic
		if (hasUpgrade(UpgradeType.QUARRY)) {
			// If we're just starting out, swap the quarryFill 
			if (currentRange == getRange())
				quarryFillPass = !quarryFillPass;
			//ProgressiveAutomation.logger.info("Selecting next block from "+currentPos.getX()+", "+currentPos.getY()+", "+currentPos.getZ());
			currentRange--;
			if (currentRange <= 0) {
				if (!quarryFillPass) currentY--;
				currentRange = getRange();
			}
		}
		else {
			//ProgressiveAutomation.logger.info("Starting with workingY: "+workingY+" and currentRange: "+currentRange);
			currentY--;
			if (currentY < 0) {
				currentRange--;
				currentY = ceilingY;
			}
		}
		
		//ProgressiveAutomation.logger.info(log+(quarryFillPass?" (quarry)":""));
		return newPos;
	}

	protected int getCurrentUpgrades() {
		if (SLOT_UPGRADE == -1) 
			return 0;
		
		if (this.getStackInSlot(SLOT_UPGRADE)==null)
			return 0;
		else
			return this.getStackInSlot(SLOT_UPGRADE).stackSize;
	}
	
	public int getMinedBlocks() {
		return currentMineBlocks;
	}

	public void setMinedBlocks(int value) {
		currentMineBlocks = value;
	}

	public int getMineBlocks() {
		return totalMineBlocks;
	}

	public void setMineBlocks(int value) {
		totalMineBlocks = value;
	}

	public boolean isDone() {
		return (totalMineBlocks==currentMineBlocks) && (totalMineBlocks>0) && (slots[SLOT_PICKAXE]!=null) && (slots[SLOT_SHOVEL]!=null);
	}
	

	/* 
	 * Checks if we have cobble or can generate it from the cobbleGen Upgrade
	 */
	public boolean haveCobble() {
		return slots[1] != null && slots[1].stackSize > 0 ? true : useCobbleGen();
	}
	
	public boolean useCobbleGen() {
		//ProgressiveAutomation.logger.info("useCobbleGen called");
		
		// If we don't have a pickaxe, skip
		if (slots[SLOT_PICKAXE] == null)
			return false;
		
		// If we don't have a cobblegen upgrade, skip
		if (!hasUpgrade(UpgradeType.COBBLE_GEN))
			return false;

		if (ToolHelper.damageTool(slots[SLOT_PICKAXE], worldObj, this.pos.getX(), this.pos.getY(), this.pos.getZ())) {
			destroyTool(SLOT_PICKAXE);
		}
		slots[1] = new ItemStack(Blocks.COBBLESTONE);
		
		return true;
	}

	// Check current tool status against last recorded status
	private boolean toolChanged( int toolSlot ) {
		int newLevel = slots[toolSlot] == null ? -1 : ToolHelper.getLevel(slots[toolSlot]);
		
		if (newLevel == lastTool[toolSlot])
			return false;
		
		lastTool[toolSlot] = newLevel;
		return true;
	}
	
	/* Check for changes to tools and upgrades */
	public void checkForChanges() {
		boolean update = false;
		
		//ProgressiveAutomation.logger.info("checkForChanges called");
		if (toolChanged( SLOT_PICKAXE )) update = true;
		if (toolChanged( SLOT_SHOVEL  )) update = true;

		//check upgrades
		if (previousUpgrades != getUpgrades()) {
			previousUpgrades = getUpgrades();
			update = true;
		}
		
		if (hasUpgrade(UpgradeType.QUARRY)) {
			if (fillerBlock != Blocks.AIR) {
				fillerBlock = Blocks.AIR;
				// Eject the filler upgrade if it exists and the quarry upgrade has been installed
				if (hasUpgrade(UpgradeType.FILLER)) {
					dropItem(new ItemStack(PAItems.fillerUpgrade, 1));
					removeUpgradeCompletely(UpgradeType.FILLER);
				}
				update = true;
			}
			// Already have the quarry upgrade, did somebody drop in a filler?
			else if (hasUpgrade(UpgradeType.FILLER)) {
				// Eject the quarry upgrade if it exists and the filler upgrade has been installed
				dropItem(new ItemStack(PAItems.quarryUpgrade, 1));
				removeUpgradeCompletely(UpgradeType.QUARRY);
				fillerBlock = Blocks.COBBLESTONE;
				update = true;
			}
		}
		else {
			if (fillerBlock != Blocks.COBBLESTONE) {
				fillerBlock = Blocks.COBBLESTONE;
				update = true;
			}
		}

		//update
		if (update) {
			//ProgressiveAutomation.logger.info("Inventory Changed Update");
			scanBlocks();
			resetMining();
		}
	}

	// Start over mining
	public void resetMining() {
		//ProgressiveAutomation.logger.info("resetMining called");
		currentRange   = getRange();
		ceilingY       = currentY = this.pos.getY() - 1;
		status         = State.CALCULATING;
		currentPos     = null;
		quarryFillPass = false;
		
		// If we have a quarry upgrade, initialize the min_max X & Z positions
		if (!hasUpgrade(UpgradeType.QUARRY))
			return;

		int range      = getRange();
		int minerX     = this.pos.getX();
		int minerZ     = this.pos.getZ();
		
		min_max_pos = new int[4];
		for (int pos = 0; pos < 2; pos++) min_max_pos[pos] = Integer.MAX_VALUE; // minX, minZ
		for (int pos = 2; pos < 4; pos++) min_max_pos[pos] = Integer.MIN_VALUE; // maxX, maxZ
			
		// Rough calculation of the interior blocks (not orthogonally exposed)
		int sqrt   = (int) Math.sqrt(range);
		int div    = (int) (range / sqrt);
		int inside = (int) range - Math.max((sqrt + div - 2) * 2 + (range % div) == 0 ? 0 : 1, 0);
		
		for (int i = range; i > inside; i--) {
			Point2I currentPoint = spiral(i, minerX, minerZ);

			// Check the outside blocks to get the min & max X & Z coordinates
			int currX = currentPoint.getX();
			int currZ = currentPoint.getY(); // spiral.Y = blockPos.Z
			
			if (currX < min_max_pos[0] ) min_max_pos[0] = currX; // minX
			if (currZ < min_max_pos[1] ) min_max_pos[1] = currZ; // minZ
			if (currX > min_max_pos[2] ) min_max_pos[2] = currX; // maxX
			if (currZ > min_max_pos[3] ) min_max_pos[3] = currZ; // maxZ
		}

	}
	
	// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
	// Quarry code

	public boolean isLiquid( BlockPos currPos ) {
		IBlockState state = worldObj.getBlockState(currPos);
		Block block = state.getBlock();
		
		return isLiquid( currPos, state, block );
	}
	
	public boolean isLiquid( BlockPos pos, IBlockState state, Block block ) {
		return block.getMaterial(state).isLiquid();
	}
	
	public boolean fixLiquid( BlockPos blockPos ) {
		//ProgressiveAutomation.logger.info("fixLiquid called");
		
		if (!hasUpgrade(UpgradeType.FILLER))
			return false;

		if (blockPos == null || min_max_pos == null)
			return false;
		
		int filled = 0;
		if (blockPos.getX() <= min_max_pos[0] && isLiquid( blockPos.west() )) {
			placeFillerBlock( blockPos.west(), Blocks.COBBLESTONE );
			filled++;
		}
		
		if (blockPos.getX() >= min_max_pos[2] && isLiquid( blockPos.east() )) {
			placeFillerBlock( blockPos.east(), Blocks.COBBLESTONE );
			filled++;
		}
		
		if (blockPos.getZ() <= min_max_pos[1] && isLiquid( blockPos.north() )) {
			placeFillerBlock( blockPos.north(), Blocks.COBBLESTONE );
			filled++;
		}
		
		if (blockPos.getZ() >= min_max_pos[3] && isLiquid( blockPos.south() )) {
			placeFillerBlock( blockPos.south(), Blocks.COBBLESTONE );
			filled++;
		}
		
		// Make this take 1 tick longer for each placed block
		miningTicks += filled;
		
		return filled > 0;
	}
	/* Check if we are ready to go */
	public boolean readyToBurn() {
		
		// Are we done?
		if (status == State.DONE)
			return false;
		
		// Do we have cobblestone?
		if (slots[1] == null && !hasUpgrade(UpgradeType.COBBLE_GEN))
			return false;
		
		// Are we missing a pick or shovel?
		if (slots[SLOT_PICKAXE] == null || slots[SLOT_SHOVEL] == null)
			return false;

		// Do we have unmined blocks?
		if (totalMineBlocks < 1 || currentMineBlocks >= totalMineBlocks)
			return false;
		
		return true;
	}

	public int extraSlotCheck(ItemStack item) {
		if (item.isItemEqual(new ItemStack(Blocks.COBBLESTONE))) {
			return 1;
		}
		return super.extraSlotCheck(item);
	}

	/* ISided Stuff */
	public boolean isItemValidForSlot(int slot, ItemStack stack) {
		if ( slot == 1 && stack.isItemEqual( new ItemStack(Blocks.COBBLESTONE)) ) {
    		return true;
    	}
		return super.isItemValidForSlot(slot, stack);
	}

}
