package com.flansmod.common.guns;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import com.flansmod.client.FlansModClient;
import com.flansmod.client.debug.EntityDebugDot;
import com.flansmod.client.debug.EntityDebugVector;
import com.flansmod.client.model.GunAnimations;
import com.flansmod.common.FlansMod;
import com.flansmod.common.PlayerData;
import com.flansmod.common.PlayerHandler;
import com.flansmod.common.RotatedAxes;
import com.flansmod.common.driveables.EntitySeat;
import com.flansmod.common.guns.ShotData.InstantShotData;
import com.flansmod.common.guns.ShotData.SpawnEntityShotData;
import com.flansmod.common.guns.raytracing.BulletHit;
import com.flansmod.common.guns.raytracing.EntityHit;
import com.flansmod.common.guns.raytracing.EnumHitboxType;
import com.flansmod.common.guns.raytracing.PlayerBulletHit;
import com.flansmod.common.guns.raytracing.PlayerHitbox;
import com.flansmod.common.guns.raytracing.PlayerSnapshot;
import com.flansmod.common.network.PacketGunFire;
import com.flansmod.common.network.PacketPlaySound;
import com.flansmod.common.network.PacketReload;
import com.flansmod.common.network.PacketSelectOffHandGun;
import com.flansmod.common.network.PacketShotData;
import com.flansmod.common.teams.EntityFlag;
import com.flansmod.common.teams.EntityFlagpole;
import com.flansmod.common.teams.EntityGunItem;
import com.flansmod.common.teams.Team;
import com.flansmod.common.types.IFlanItem;
import com.flansmod.common.types.InfoType;
import com.flansmod.common.vector.Vector3f;
import com.google.common.collect.Multimap;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.attributes.AttributeModifier;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.EnumAction;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.BlockPos;
import net.minecraft.util.DamageSource;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.util.MovingObjectPosition.MovingObjectType;
import net.minecraft.world.World;
import net.minecraftforge.common.util.Constants;
import net.minecraftforge.fml.client.FMLClientHandler;
import net.minecraftforge.fml.common.registry.GameRegistry;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

public class ItemGun extends Item implements IFlanItem
{
	private static final int CLIENT_TO_SERVER_UPDATE_INTERVAL = 1;
	
	private GunType type;
	
	public GunType GetType() { return type; }
	
	private int soundDelay = 0;
	
	private static boolean rightMouseHeld;
	private static boolean lastRightMouseHeld;
	private static boolean leftMouseHeld;
	private static boolean lastLeftMouseHeld;
	
	private static boolean GetMouseHeld(boolean isOffHand) { return isOffHand ? leftMouseHeld : rightMouseHeld; }
	private static boolean GetLastMouseHeld(boolean isOffHand) { return isOffHand ? lastLeftMouseHeld : lastRightMouseHeld; }
	
	private static List<ShotData> shotsFired = new ArrayList<ShotData>();
	
	public ItemGun(GunType type)
	{
		maxStackSize = 1;
		this.type = type;
		type.item = this;
		setMaxDamage(0);
		setCreativeTab(FlansMod.tabFlanGuns);
		GameRegistry.registerItem(this, type.shortName, FlansMod.MODID);
	}
	
	/** Get the bullet item stack stored in the gun's NBT data (the loaded magazine / bullets) */
	public ItemStack getBulletItemStack(ItemStack gun, int id)
	{
		//If the gun has no tags, give it some
		if(!gun.hasTagCompound())
		{
			gun.setTagCompound(new NBTTagCompound());
			return null;
		}
		//If the gun has no ammo tags, give it some
		if(!gun.getTagCompound().hasKey("ammo"))
		{
			NBTTagList ammoTagsList = new NBTTagList();
			for(int i = 0; i < type.numAmmoItemsInGun; i++)
			{
				ammoTagsList.appendTag(new NBTTagCompound());
			}
			gun.getTagCompound().setTag("ammo", ammoTagsList);
			return null;
		}
		//Take the list of ammo tags
		NBTTagList ammoTagsList = gun.getTagCompound().getTagList("ammo", Constants.NBT.TAG_COMPOUND);
		//Get the specific ammo tags required
		NBTTagCompound ammoTags = ammoTagsList.getCompoundTagAt(id);
		return ItemStack.loadItemStackFromNBT(ammoTags);
	}
	
	/** Set the bullet item stack stored in the gun's NBT data (the loaded magazine / bullets) */
	public void setBulletItemStack(ItemStack gun, ItemStack bullet, int id)
	{
		//If the gun has no tags, give it some
		if(!gun.hasTagCompound())
		{
			gun.setTagCompound(new NBTTagCompound());
		}
		//If the gun has no ammo tags, give it some
		if(!gun.getTagCompound().hasKey("ammo"))
		{
			NBTTagList ammoTagsList = new NBTTagList();
			for(int i = 0; i < type.numAmmoItemsInGun; i++)
			{
				ammoTagsList.appendTag(new NBTTagCompound());
			}
			gun.getTagCompound().setTag("ammo", ammoTagsList);
		}
		//Take the list of ammo tags
		NBTTagList ammoTagsList = gun.getTagCompound().getTagList("ammo", Constants.NBT.TAG_COMPOUND);
		//Get the specific ammo tags required
		NBTTagCompound ammoTags = ammoTagsList.getCompoundTagAt(id);
		//Represent empty slots by nulltypes
		if(bullet == null)
		{
			ammoTags = new NBTTagCompound();
		}
		//Set the tags to match the bullet stack
		bullet.writeToNBT(ammoTags);
	}
	
	/** Method for dropping items on reload and on shoot */
	public static void dropItem(World world, Entity entity, String itemName)
	{
		if (itemName != null)
		{
			int damage = 0;
			if (itemName.contains("."))
			{
				damage = Integer.parseInt(itemName.split("\\.")[1]);
				itemName = itemName.split("\\.")[0];
			}
			ItemStack dropStack = InfoType.getRecipeElement(itemName, damage);
			entity.entityDropItem(dropStack, 0.5F);
		}
	}
	
	/** Deployable guns only */
	@Override
	public ItemStack onItemRightClick(ItemStack itemstack, World world, EntityPlayer entityplayer)
	{
		if (type.deployable)
		{
	    	//Raytracing
	        float cosYaw = MathHelper.cos(-entityplayer.rotationYaw * 0.01745329F - 3.141593F);
	        float sinYaw = MathHelper.sin(-entityplayer.rotationYaw * 0.01745329F - 3.141593F);
	        float cosPitch = -MathHelper.cos(-entityplayer.rotationPitch * 0.01745329F);
	        float sinPitch = MathHelper.sin(-entityplayer.rotationPitch * 0.01745329F);
	        double length = 5D;
	        Vec3 posVec = new Vec3(entityplayer.posX, entityplayer.posY + 1.62D - entityplayer.getYOffset(), entityplayer.posZ);        
	        Vec3 lookVec = posVec.addVector(sinYaw * cosPitch * length, sinPitch * length, cosYaw * cosPitch * length);
	        MovingObjectPosition look = world.rayTraceBlocks(posVec, lookVec, true);
	        
	        //Result check
			if (look != null && look.typeOfHit == MovingObjectType.BLOCK)
			{
				if (look.sideHit == EnumFacing.UP)
				{
					int playerDir = MathHelper.floor_double(((entityplayer.rotationYaw * 4F) / 360F) + 0.5D) & 3;
					int i = look.getBlockPos().getX();
					int j = look.getBlockPos().getY();
					int k = look.getBlockPos().getZ();
					if (!world.isRemote)
					{
						if (world.getBlockState(new BlockPos(i, j, k)).getBlock() == Blocks.snow)
						{
							j--;
						}
						if (isSolid(world, i, j, k) && (world.getBlockState(new BlockPos(i, j + 1, k)).getBlock() == Blocks.air || world.getBlockState(new BlockPos(i, j + 1, k)).getBlock() == Blocks.snow) && (world.getBlockState(new BlockPos(i + (playerDir == 1 ? 1 : 0) - (playerDir == 3 ? 1 : 0), j + 1, k - (playerDir == 0 ? 1 : 0) + (playerDir == 2 ? 1 : 0))).getBlock() == Blocks.air) && (world.getBlockState(new BlockPos(i + (playerDir == 1 ? 1 : 0) - (playerDir == 3 ? 1 : 0), j, k - (playerDir == 0 ? 1 : 0) + (playerDir == 2 ? 1 : 0))).getBlock() == Blocks.air || world.getBlockState(new BlockPos(i + (playerDir == 1 ? 1 : 0) - (playerDir == 3 ? 1 : 0), j, k - (playerDir == 0 ? 1 : 0) + (playerDir == 2 ? 1 : 0))).getBlock() == Blocks.snow))
						{
							for (EntityMG mg : EntityMG.mgs)
							{
								if (mg.blockX == i && mg.blockY == j + 1 && mg.blockZ == k && !mg.isDead)
									return itemstack;
							}
							if(!world.isRemote)
							{
								EntityMG mg = new EntityMG(world, i, j + 1, k, playerDir, type);
								if(getBulletItemStack(itemstack, 0) != null)
								{
									mg.ammo = getBulletItemStack(itemstack, 0);
								}
								world.spawnEntityInWorld(mg);
								
							}
							if (!entityplayer.capabilities.isCreativeMode)
								itemstack.stackSize = 0;
						}
					}
				}
			}
		}
		//Stop the gun bobbing up and down when holding shoot and looking at a block
		if(world.isRemote)
		{
			for(int i = 0; i < 3; i++)
				Minecraft.getMinecraft().entityRenderer.itemRenderer.updateEquippedItem();
		}
		return itemstack;
	}
	
	// _____________________________________________________________________________
	//
	// Shooting code
	// _____________________________________________________________________________
	
	@SideOnly(Side.CLIENT)
	public void onUpdateClient(ItemStack gunstack, int gunSlot, World world, Entity entity, boolean isOffHand, boolean hasOffHand)
	{
		if(!(entity instanceof EntityPlayer))
		{			
			return;
		}
		// Get useful objects
		Minecraft mc = Minecraft.getMinecraft();
		EntityPlayer player = (EntityPlayer)entity;
		PlayerData data = PlayerHandler.getPlayerData(player, Side.CLIENT);
		
		// Play idle sounds
		if (soundDelay <= 0 && type.idleSound != null)
		{
			PacketPlaySound.sendSoundPacket(entity.posX, entity.posY, entity.posZ, FlansMod.soundRange, entity.dimension, type.idleSound, false);
			soundDelay = type.idleSoundLength;
		}
		
		// This code is not for deployables
		if (type.deployable)
			return;
		
		// Do not shoot ammo bags, flags or dropped gun items
		if(mc.objectMouseOver != null && (mc.objectMouseOver.entityHit instanceof EntityFlagpole || mc.objectMouseOver.entityHit instanceof EntityFlag || mc.objectMouseOver.entityHit instanceof EntityGunItem || (mc.objectMouseOver.entityHit instanceof EntityGrenade && ((EntityGrenade)mc.objectMouseOver.entityHit).type.isDeployableBag)))
			return;
		
		// If we have an off hand item, then disable our secondary functions
		boolean secondaryFunctionsEnabled = true;
		
		// Update off hand cycling. Controlled by the main gun, since it is always around.
		if(!isOffHand && type.oneHanded) 
		{				
			//Cycle selection
			int dWheel = Mouse.getDWheel();
			if(Keyboard.isKeyDown(mc.gameSettings.keyBindSneak.getKeyCode()) && dWheel != 0)
			{
				data.cycleOffHandItem(player, dWheel);
			}
		}
		
		if(type.usableByPlayers)
		{
			boolean needsToReload = needsToReload(gunstack);
			boolean shouldShootThisTick = false;
			switch(type.getFireMode(gunstack))
			{
				case BURST:
				{
					if(data.GetBurstRoundsRemaining(isOffHand) > 0)
					{
						shouldShootThisTick = true;
					}
					// Fallthrough to semi auto
				}
				case SEMIAUTO:
				{
					if(GetMouseHeld(isOffHand) && !GetLastMouseHeld(isOffHand))
					{
						shouldShootThisTick = true;
					}
					break;
				}
				case MINIGUN:
				{
					if(needsToReload)
						break;
					data.minigunSpeed += 2.0f;
					data.minigunSpeed *= 0.9f;
					// TODO : Re-add looping sounds
					if(data.minigunSpeed < type.minigunStartSpeed)
						break;
					//else fallthrough to full auto
				}
				case FULLAUTO:
				{
					shouldShootThisTick = GetMouseHeld(isOffHand);
					break;
				}
				default:
					break;
			}
			
			// Do reload if we pressed fire.
			if(needsToReload && shouldShootThisTick)
			{
				if(Reload(gunstack, gunSlot, world, player, player.inventory, isOffHand, hasOffHand, false, player.capabilities.isCreativeMode))
				{
					//Set player shoot delay to be the reload delay
					//Set both gun delays to avoid reloading two guns at once
					data.shootTimeRight = data.shootTimeLeft = (int)type.getReloadTime(gunstack);
					
					GunAnimations animations = FlansModClient.getGunAnimations(player, isOffHand);

					int pumpDelay = type.model == null ? 0 : type.model.pumpDelayAfterReload;
					int pumpTime = type.model == null ? 1 : type.model.pumpTime;
					animations.doReload(type.reloadTime, pumpDelay, pumpTime);
					
					if(isOffHand)
					{
						data.reloadingLeft = true;
						data.burstRoundsRemainingLeft = 0;
					}
					else
					{
						data.reloadingRight = true;
						data.burstRoundsRemainingRight = 0;
					}
					//Send reload packet to server
					FlansMod.getPacketHandler().sendToServer(new PacketReload(isOffHand, false));
				}
			}
			// Fire!
			else if(shouldShootThisTick)
			{
				float shootTime = data.GetShootTime(isOffHand);
									
				// For each 
				while(shootTime <= 0.0f)
				{
					// Add the delay for this shot and shoot it!
					shootTime += type.shootDelay;
					
					ItemStack shootableStack = getBestNonEmptyShootableStack(gunstack);
					ItemShootable shootableItem = (ItemShootable)shootableStack.getItem();
					ShootableType shootableType = shootableItem.type;
					// Instant bullets. Do a raytrace
					if(type.bulletSpeed == 0.0f)
					{
						
					}
					// Else, spawn an entity
					else
					{
						ShotData shotData = new ShotData.SpawnEntityShotData(gunSlot, type, shootableType, player, new Vector3f(player.getLookVec()));
						shotsFired.add(shotData);
					}
					
					// Now do client side things
					GunAnimations animations = FlansModClient.getGunAnimations(player, isOffHand);
					
					int pumpDelay = type.model == null ? 0 : type.model.pumpDelay;
					int pumpTime = type.model == null ? 1 : type.model.pumpTime;
					animations.doShoot(pumpDelay, pumpTime);
					FlansModClient.playerRecoil += type.getRecoil(gunstack);
					if(type.consumeGunUponUse)
						player.inventory.setInventorySlotContents(gunSlot, null);
					
					// Update burst fire
					if(type.getFireMode(gunstack) == EnumFireMode.BURST)
					{
						int burstRoundsRemaining = data.GetBurstRoundsRemaining(isOffHand);

						if(burstRoundsRemaining > 0)
							burstRoundsRemaining--;
						else burstRoundsRemaining = type.numBurstRounds;
						
						data.SetBurstRoundsRemaining(isOffHand, burstRoundsRemaining);
					}
				}
				
				data.SetShootTime(isOffHand, shootTime);
			}
			
			// Now send shooting data to the server
			if(player.ticksExisted % CLIENT_TO_SERVER_UPDATE_INTERVAL == 0)
			{
				FlansMod.getPacketHandler().sendToServer(new PacketShotData(shotsFired));
				shotsFired.clear();
			}
			
			// Check for scoping in / out
			IScope currentScope = type.getCurrentScope(gunstack);
			if(!isOffHand && !hasOffHand && leftMouseHeld && !lastLeftMouseHeld
					&& (type.secondaryFunction == EnumSecondaryFunction.ADS_ZOOM || type.secondaryFunction == EnumSecondaryFunction.ZOOM) )
			{
				FlansModClient.SetScope(currentScope);
			}
		}
		
		// And finally do sounds
		if (soundDelay > 0)
		{
			soundDelay--;
		}
	}
	
	public void ServerHandleShotData(ItemStack gunstack, int gunSlot, World world, Entity entity, boolean isOffHand, ShotData shotData)
	{
		// Get useful things
		if(!(entity instanceof EntityPlayerMP))
		{
			return;
		}
		EntityPlayerMP player = (EntityPlayerMP)entity;
		PlayerData data = PlayerHandler.getPlayerData(player, Side.SERVER);
		if(data == null)
		{
			return;
		}
		
		// Spawn an entity, classic style
		if(shotData instanceof SpawnEntityShotData)
		{
			//Go through the bullet stacks in the gun and see if any of them are not null
			int bulletID = 0;
			ItemStack bulletStack = null;
			for(; bulletID < type.numAmmoItemsInGun; bulletID++)
			{
				ItemStack checkingStack = getBulletItemStack(gunstack, bulletID);
				if(checkingStack != null && checkingStack.getItem() != null && checkingStack.getItemDamage() < checkingStack.getMaxDamage())
				{
					bulletStack = checkingStack;
					break;
				}
			}
			
			// We have no bullet stack. So we need to reload. The player will send us a message requesting we do a reload
			if(bulletStack == null)
			{
				return;
			}
			
			/*
			//If no bullet stack was found, reload
			if(bulletStack == null)
			{
				if(reload(gunStack, gunType, world, entityplayer, false, left))
				{
					//Set player shoot delay to be the reload delay
					//Set both gun delays to avoid reloading two guns at once
					data.shootTimeRight = data.shootTimeLeft = (int)gunType.getReloadTime(gunStack);
					
					if(left)
					{
						data.reloadingLeft = true;
						data.burstRoundsRemainingLeft = 0;
					}
					else
					{
						data.reloadingRight = true;
						data.burstRoundsRemainingRight = 0;
					}
					//Send reload packet to induce reload effects client side
					FlansMod.getPacketHandler().sendTo(new PacketReload(left), entityplayer);
					//Play reload sound
					if(gunType.reloadSound != null)
						PacketPlaySound.sendSoundPacket(entityplayer.posX, entityplayer.posY, entityplayer.posZ, FlansMod.soundRange, entityplayer.dimension, gunType.reloadSound, true);
				}
			}
			*/
			
			if(bulletStack.getItem() instanceof ItemShootable)
			{
				ShootableType bullet = ((ItemShootable)bulletStack.getItem()).type;
				//Shoot
				
				// Play a sound if the previous sound has finished
				if (soundDelay <= 0 && type.shootSound != null)
				{
					AttachmentType barrel = type.getBarrel(gunstack);
					boolean silenced = barrel != null && barrel.silencer;
					//world.playSoundAtEntity(entityplayer, type.shootSound, 10F, type.distortSound ? 1.0F / (world.rand.nextFloat() * 0.4F + 0.8F) : 1.0F);
					PacketPlaySound.sendSoundPacket(player.posX, player.posY, player.posZ, FlansMod.soundRange, player.dimension, type.shootSound, type.distortSound, silenced);
					soundDelay = type.shootSoundLength;
				}
				if (!world.isRemote)
				{
					// Spawn the bullet entities
					for (int k = 0; k < type.numBullets * bullet.numBullets; k++)
					{
						// Actually shoot the bullet
						((ItemShootable)bulletStack.getItem()).Shoot(world, 
								new Vector3f(player.getPositionEyes(1.0f)), 
								new Vector3f(player.getLookVec()), 
								type.getDamage(gunstack), 
								(player.isSneaking() ? 0.7F : 1F) * type.getSpread(gunstack) * bullet.bulletSpread,
								type.getBulletSpeed(gunstack), 
								type, 
								player);
					}
					// Drop item on shooting if bullet requires it
					if(bullet.dropItemOnShoot != null && !player.capabilities.isCreativeMode)
						dropItem(world, player, bullet.dropItemOnShoot);
					// Drop item on shooting if gun requires it
					if(type.dropItemOnShoot != null)// && !entityplayer.capabilities.isCreativeMode)
						dropItem(world, player, type.dropItemOnShoot);
				}
				
				// Not needed server side?
				//data.SetShootTime(isOffHand, type.shootDelay);
				
				if(type.knockback > 0)
				{
					//TODO : Apply knockback		
				}	
				
				//Damage the bullet item
				bulletStack.setItemDamage(bulletStack.getItemDamage() + 1);
				
				//Update the stack in the gun
				setBulletItemStack(gunstack, bulletStack, bulletID);
				
				if(type.consumeGunUponUse)
					player.inventory.setInventorySlotContents(gunSlot, null);
			}
			
		}
		
		// Do a raytrace check on what they've sent us.
		else if(shotData instanceof InstantShotData)
		{
			
		}
	}
	
	public void onUpdateServer(ItemStack itemstack, int gunSlot, World world, Entity entity, boolean isOffHand, boolean hasOffHand)
	{
		if(!(entity instanceof EntityPlayerMP))
		{
			return;
		}
		EntityPlayerMP player = (EntityPlayerMP)entity;
		PlayerData data = PlayerHandler.getPlayerData(player);
		if(data == null)
			return;
		
		if(player.inventory.getCurrentItem() != itemstack)
		{
			//If the player is no longer holding a gun, emulate a release of the shoot button
			if(player.inventory.getCurrentItem() == null || player.inventory.getCurrentItem().getItem() == null || !(player.inventory.getCurrentItem().getItem() instanceof ItemGun))
			{
				data.isShootingRight = data.isShootingLeft = false;
				data.offHandGunSlot = 0;
				(new PacketSelectOffHandGun(0)).handleServerSide(player);
			}
			return;
		}
	}
	
	/** Generic update method. If we have an off hand weapon, it will also make calls for that 
	 *  Passes on to onUpdateEach */
	@Override
	public void onUpdate(ItemStack itemstack, World world, Entity entity, int i, boolean flag)
	{
		if(entity instanceof EntityPlayer && ((EntityPlayer)entity).inventory.getCurrentItem() == itemstack)
		{
			if(world.isRemote)
			{
				// Get button presses. Do this before splitting into each hand. Prevents second pass wiping the data
				lastRightMouseHeld = rightMouseHeld;
				lastLeftMouseHeld = leftMouseHeld;
				rightMouseHeld = Mouse.isButtonDown(1);
				leftMouseHeld = Mouse.isButtonDown(0);
			}
			
			boolean hasOffHand = false;
			EntityPlayer player = (EntityPlayer)entity;
			PlayerData data = PlayerHandler.getPlayerData(player, Side.CLIENT);
			
			if(type.oneHanded) 
			{
				// If the offhand item is this item, select none
				if(data.offHandGunSlot == player.inventory.currentItem + 1)
					data.offHandGunSlot = 0;
				
				if(data.offHandGunSlot != 0)
				{
					hasOffHand = true;
					ItemStack offHandGunStack = player.inventory.getStackInSlot(data.offHandGunSlot - 1);
					if(offHandGunStack != null && offHandGunStack.getItem() instanceof ItemGun)
					{
						GunType offHandGunType = ((ItemGun)offHandGunStack.getItem()).type;
						((ItemGun)offHandGunStack.getItem()).onUpdateEach(offHandGunStack, data.offHandGunSlot - 1, world, entity, true, hasOffHand);
					}
				}
			}
			
			onUpdateEach(itemstack, player.inventory.currentItem, world, entity, false, hasOffHand);
		}
	}
	
	/** Called once for each weapon we are weilding */
	private void onUpdateEach(ItemStack itemstack, int gunSlot, World world, Entity entity, boolean isOffHand, boolean hasOffHand)
	{
		if(world.isRemote)
			onUpdateClient(itemstack, gunSlot, world, entity, isOffHand, hasOffHand);
		else onUpdateServer(itemstack, gunSlot, world, entity, isOffHand, hasOffHand);		
	}
	
	public boolean Reload(ItemStack gunstack, int gunSlot, World world, Entity entity, IInventory inventory, boolean isOffHand, boolean hasOffHand, boolean forceReload, boolean isCreative)
	{
		//Deployable guns cannot be reloaded in the inventory
		if(type.deployable)
			return false;
		//If you cannot reload half way through a clip, reject the player for trying to do so
		if(forceReload && !type.canForceReload)
			return false;
		
		//For playing sounds afterwards
		boolean reloadedSomething = false;
		//Check each ammo slot, one at a time
		for(int i = 0; i < type.numAmmoItemsInGun; i++)
		{
			//Get the stack in the slot
			ItemStack bulletStack = getBulletItemStack(gunstack, i);
			
			//If there is no magazine, if the magazine is empty or if this is a forced reload
			if(bulletStack == null || bulletStack.getItemDamage() == bulletStack.getMaxDamage() || forceReload)
			{		
				//Iterate over all inventory slots and find the magazine / bullet item with the most bullets
				int bestSlot = -1;
				int bulletsInBestSlot = 0;
				for (int j = 0; j < inventory.getSizeInventory(); j++)
				{
					ItemStack item = inventory.getStackInSlot(j);
					if (item != null && item.getItem() instanceof ItemShootable && type.isAmmo(((ItemShootable)(item.getItem())).type))
					{
						int bulletsInThisSlot = item.getMaxDamage() - item.getItemDamage();
						if(bulletsInThisSlot > bulletsInBestSlot)
						{
							bestSlot = j;
							bulletsInBestSlot = bulletsInThisSlot;
						}
					}
				}
				//If there was a valid non-empty magazine / bullet item somewhere in the inventory, load it
				if(bestSlot != -1)
				{
					ItemStack newBulletStack = inventory.getStackInSlot(bestSlot);
					ShootableType newBulletType = ((ItemShootable)newBulletStack.getItem()).type;
					
					//Unload the old magazine (Drop an item if it is required and the player is not in creative mode)
					if(bulletStack != null && bulletStack.getItem() instanceof ItemShootable && ((ItemShootable)bulletStack.getItem()).type.dropItemOnReload != null && !isCreative && bulletStack.getItemDamage() == bulletStack.getMaxDamage())
					{
						if(!world.isRemote)
							dropItem(world, entity, ((ItemShootable)bulletStack.getItem()).type.dropItemOnReload);
					}
						
					//The magazine was not finished, pull it out and give it back to the player or, failing that, drop it
					if(bulletStack != null && bulletStack.getItemDamage() < bulletStack.getMaxDamage())
					{
						if(!InventoryHelper.addItemStackToInventory(inventory, bulletStack, isCreative))
						{
							if(!world.isRemote)
								entity.entityDropItem(bulletStack, 0.5F);
						}
					}
							
					//Load the new magazine
					ItemStack stackToLoad = newBulletStack.copy();
					stackToLoad.stackSize = 1;
					setBulletItemStack(gunstack, stackToLoad, i);					
					
					//Remove the magazine from the inventory
					if(!isCreative)
						newBulletStack.stackSize--;
					if(newBulletStack.stackSize <= 0)
						newBulletStack = null;
					inventory.setInventorySlotContents(bestSlot, newBulletStack);
								
					
					//Tell the sound player that we reloaded something
					reloadedSomething = true;
				}
			}
		}
		return reloadedSomething;
	}
	
	// TODO : All this bunk
	
	/*
	// NO IDEA
	public void onMouseHeld(ItemStack stack, World world, EntityPlayerMP player, boolean left, boolean isShooting)
	{
		PlayerData data = PlayerHandler.getPlayerData(player);
		if(data != null && data.shootClickDelay == 0)
		{
			//Drivers can't shoot
			if(player.ridingEntity instanceof EntitySeat && ((EntitySeat)player.ridingEntity).seatInfo.id == 0)
				return;
			if(left && data.offHandGunSlot != 0)
			{
				ItemStack offHandGunStack = player.inventory.getStackInSlot(data.offHandGunSlot - 1);
				GunType gunType = ((ItemGun)offHandGunStack.getItem()).type;
				data.isShootingLeft = isShooting;
				if(gunType.getFireMode(offHandGunStack) == EnumFireMode.SEMIAUTO && isShooting)
				{
					data.isShootingLeft = false;
					player.inventory.setInventorySlotContents(data.offHandGunSlot - 1, tryToShoot(offHandGunStack, gunType, world, player, true));
				}
				if(gunType.getFireMode(offHandGunStack) == EnumFireMode.BURST && isShooting && data.burstRoundsRemainingLeft == 0)
				{
					data.isShootingLeft = false;
					data.burstRoundsRemainingLeft = gunType.numBurstRounds;
					player.inventory.setInventorySlotContents(data.offHandGunSlot - 1, tryToShoot(offHandGunStack, gunType, world, player, true));
				}
			}
			else
			{
				data.isShootingRight = isShooting;
				if(type.getFireMode(stack) == EnumFireMode.SEMIAUTO && isShooting)
				{
					data.isShootingRight = false;
					player.inventory.setInventorySlotContents(player.inventory.currentItem, tryToShoot(stack, type, world, player, false));
				}
				if(type.getFireMode(stack) == EnumFireMode.BURST && isShooting && data.burstRoundsRemainingRight == 0)
				{
					data.isShootingRight = false;
					data.burstRoundsRemainingRight = type.numBurstRounds;
					player.inventory.setInventorySlotContents(player.inventory.currentItem, tryToShoot(stack, type, world, player, false));
				}
			}
			//Play the warmup sound for miniguns immediately
			if(type.useLoopingSounds && isShooting)
			{
				data.shouldPlayWarmupSound = true;
			}
		}
	}
	*/
	
	/* Melee MESS
	 * 	@Override
	public void onUpdate(ItemStack itemstack, World world, Entity pEnt, int i, boolean flag)
	{
		if(world.isRemote)
			onUpdateClient(itemstack, world, pEnt, i, flag);
		else onUpdateServer(itemstack, world, pEnt, i, flag);
		
		if(pEnt instanceof EntityPlayer)
		{
			EntityPlayer player = (EntityPlayer)pEnt;
			PlayerData data = PlayerHandler.getPlayerData(player);
			if(data == null)
				return;
			//if(data.lastMeleePositions == null || data.lastMeleePositions.length != type.meleeDamagePoints.size())
			//{
			//	data.lastMeleePositions = new Vector3f[type.meleeDamagePoints.size()];
			//	for(int j = 0; j < type.meleeDamagePoints.size(); j++)
			//		data.lastMeleePositions[j] = new Vector3f(player.posX, player.posY, player.posZ);
			//}
			//Melee weapon
			if(data.meleeLength > 0 && type.meleePath.size() > 0 && player.inventory.getCurrentItem() == itemstack)
			{
				for(int k = 0; k < type.meleeDamagePoints.size(); k++)
				{
					Vector3f meleeDamagePoint = type.meleeDamagePoints.get(k);
					//Do a raytrace from the prev pos to the current pos and attack anything in the way
					Vector3f nextPos = type.meleePath.get((data.meleeProgress + 1) % type.meleePath.size());
					Vector3f nextAngles = type.meleePathAngles.get((data.meleeProgress + 1) % type.meleePathAngles.size());
					RotatedAxes nextAxes = new RotatedAxes().rotateGlobalRoll(-nextAngles.x).rotateGlobalPitch(-nextAngles.z).rotateGlobalYaw(-nextAngles.y);
					
					Vector3f nextPosInGunCoords = nextAxes.findLocalVectorGlobally(meleeDamagePoint);
					Vector3f.add(nextPos, nextPosInGunCoords, nextPosInGunCoords);
					Vector3f.add(new Vector3f(0F, 0F, 0F), nextPosInGunCoords, nextPosInGunCoords);
					Vector3f nextPosInPlayerCoords = new RotatedAxes(player.rotationYaw + 90F, player.rotationPitch, 0F).findLocalVectorGlobally(nextPosInGunCoords);
					
					
					if(!FlansMod.proxy.isThePlayer(player))
						nextPosInPlayerCoords.y += 1.6F;
					
					Vector3f nextPosInWorldCoords = new Vector3f(player.posX + nextPosInPlayerCoords.x, player.posY + nextPosInPlayerCoords.y, player.posZ + nextPosInPlayerCoords.z);
					
					Vector3f dPos = data.lastMeleePositions[k] == null ? new Vector3f() : Vector3f.sub(nextPosInWorldCoords, data.lastMeleePositions[k], null);
					
					if(player.worldObj.isRemote && FlansMod.DEBUG)
						player.worldObj.spawnEntityInWorld(new EntityDebugVector(player.worldObj, data.lastMeleePositions[k], dPos, 200, 1F, 0F, 0F));
					
					//Do the raytrace
					{
						//Create a list for all bullet hits
						ArrayList<BulletHit> hits = new ArrayList<BulletHit>();
										
						//Iterate over all entities
						for(int j = 0; j < world.loadedEntityList.size(); j++)
						{
							Object obj = world.loadedEntityList.get(j);
							//Get players
							if(obj instanceof EntityPlayer)
							{
								EntityPlayer otherPlayer = (EntityPlayer)obj;
								PlayerData otherData = PlayerHandler.getPlayerData(otherPlayer);
								boolean shouldDoNormalHitDetect = false;
								if(otherPlayer == player)
									continue;
								if(otherData != null)
								{
									if(otherPlayer.isDead || otherData.team == Team.spectators)
									{
										continue;
									}
									int snapshotToTry = player instanceof EntityPlayerMP ? ((EntityPlayerMP)player).ping / 50 : 0;
									if(snapshotToTry >= otherData.snapshots.length)
										snapshotToTry = otherData.snapshots.length - 1;
									
									PlayerSnapshot snapshot = otherData.snapshots[snapshotToTry];
									if(snapshot == null)
										snapshot = otherData.snapshots[0];
									
									//DEBUG
									//snapshot = new PlayerSnapshot(player);
									
									//Check one last time for a null snapshot. If this is the case, fall back to normal hit detection
									if(snapshot == null)
										shouldDoNormalHitDetect = true;
									else
									{
										//Raytrace
										ArrayList<BulletHit> playerHits = snapshot.raytrace(data.lastMeleePositions[k] == null ? nextPosInWorldCoords : data.lastMeleePositions[k], dPos);
										hits.addAll(playerHits);
									}
								}
								
								//If we couldn't get a snapshot, use normal entity hitbox calculations
								if(otherData == null || shouldDoNormalHitDetect)
								{
									MovingObjectPosition mop = data.lastMeleePositions[k] == null ? player.getEntityBoundingBox().calculateIntercept(nextPosInWorldCoords.toVec3(), new Vec3(0F, 0F, 0F)) : player.getBoundingBox().calculateIntercept(data.lastMeleePositions[k].toVec3(), nextPosInWorldCoords.toVec3());
									if(mop != null)
									{
										Vector3f hitPoint = new Vector3f(mop.hitVec.xCoord - data.lastMeleePositions[k].x, mop.hitVec.yCoord - data.lastMeleePositions[k].y, mop.hitVec.zCoord - data.lastMeleePositions[k].z);
										float hitLambda = 1F;
										if(dPos.x != 0F)
											hitLambda = hitPoint.x / dPos.x;
										else if(dPos.y != 0F)
											hitLambda = hitPoint.y / dPos.y;
										else if(dPos.z != 0F)
											hitLambda = hitPoint.z / dPos.z;
										if(hitLambda < 0)
											hitLambda = -hitLambda;
										
										hits.add(new PlayerBulletHit(new PlayerHitbox(otherPlayer, new RotatedAxes(), new Vector3f(), new Vector3f(), new Vector3f(), EnumHitboxType.BODY), hitLambda));
									}
								}
							}
							else
							{
								Entity entity = (Entity)obj;
								if(entity != player && !entity.isDead && (entity instanceof EntityLivingBase || entity instanceof EntityAAGun))
								{
									MovingObjectPosition mop = entity.getEntityBoundingBox().calculateIntercept(data.lastMeleePositions[k].toVec3(), nextPosInWorldCoords.toVec3());
									if(mop != null)
									{
										Vector3f hitPoint = new Vector3f(mop.hitVec.xCoord - data.lastMeleePositions[k].x, mop.hitVec.yCoord - data.lastMeleePositions[k].y, mop.hitVec.zCoord - data.lastMeleePositions[k].z);
										float hitLambda = 1F;
										if(dPos.x != 0F)
											hitLambda = hitPoint.x / dPos.x;
										else if(dPos.y != 0F)
											hitLambda = hitPoint.y / dPos.y;
										else if(dPos.z != 0F)
											hitLambda = hitPoint.z / dPos.z;
										if(hitLambda < 0)
											hitLambda = -hitLambda;
										
										hits.add(new EntityHit(entity, hitLambda));
									}
								}
							}
						}
						
						//We hit something
						if(!hits.isEmpty())
						{
							//Sort the hits according to the intercept position
							Collections.sort(hits);
							
							float swingDistance = dPos.length();
							
							for(BulletHit bulletHit : hits)
							{
								if(bulletHit instanceof PlayerBulletHit)
								{
									PlayerBulletHit playerHit = (PlayerBulletHit)bulletHit;
									float damageMultiplier = 1F;
									switch(playerHit.hitbox.type)
									{
									case LEFTITEM : case RIGHTITEM : //Hit a shield. Stop the swing. 
									{
										data.meleeProgress = data.meleeLength = 0;
										return;
									}
									case HEAD : damageMultiplier = 2F; break;
									case RIGHTARM : case LEFTARM : damageMultiplier = 0.6F; break;
									default :
									}
									
									if(playerHit.hitbox.player.attackEntityFrom(getMeleeDamage(player), swingDistance * type.meleeDamage))
									{
										//If the attack was allowed, we should remove their immortality cooldown so we can shoot them again. Without this, any rapid fire gun become useless
										playerHit.hitbox.player.arrowHitTimer++;
										playerHit.hitbox.player.hurtResistantTime = playerHit.hitbox.player.maxHurtResistantTime / 2;
									}
									
									if(FlansMod.DEBUG)
										world.spawnEntityInWorld(new EntityDebugDot(world, new Vector3f(data.lastMeleePositions[k].x + dPos.x * playerHit.intersectTime, data.lastMeleePositions[k].y + dPos.y * playerHit.intersectTime, data.lastMeleePositions[k].z + dPos.z * playerHit.intersectTime), 1000, 1F, 0F, 0F));
								}
								else if(bulletHit instanceof EntityHit)
								{
									EntityHit entityHit = (EntityHit)bulletHit;
									if(entityHit.entity.attackEntityFrom(DamageSource.causePlayerDamage(player), swingDistance * type.meleeDamage) && entityHit.entity instanceof EntityLivingBase)
									{
										EntityLivingBase living = (EntityLivingBase)entityHit.entity;
										//If the attack was allowed, we should remove their immortality cooldown so we can shoot them again. Without this, any rapid fire gun become useless
										living.arrowHitTimer++;
										living.hurtResistantTime = living.maxHurtResistantTime / 2;
									}
									
									if(FlansMod.DEBUG)
										world.spawnEntityInWorld(new EntityDebugDot(world, new Vector3f(data.lastMeleePositions[k].x + dPos.x * entityHit.intersectTime, data.lastMeleePositions[k].y + dPos.y * entityHit.intersectTime, data.lastMeleePositions[k].z + dPos.z * entityHit.intersectTime), 1000, 1F, 0F, 0F));
								}
							}	
						}
					}
					//End raytrace
					
					data.lastMeleePositions[k] = nextPosInWorldCoords;
				}
				
				//Increment the progress meter
				data.meleeProgress++;
				//If we are done, reset the counters
				if(data.meleeProgress == data.meleeLength)
					data.meleeProgress = data.meleeLength = 0;
			}
		}
	}
	 
	 * 
	 */
	
	private boolean needsToReload(ItemStack stack)
	{
		for(int i = 0; i < type.numAmmoItemsInGun; i++)
		{
			ItemStack bulletStack = getBulletItemStack(stack, i);
			if(bulletStack != null && bulletStack.getItem() != null && bulletStack.getItemDamage() < bulletStack.getMaxDamage())
			{
				return false;
			}
		}
		return true;
	}
	
	public boolean CanReload(ItemStack gunstack, IInventory inventory)
	{
		for(int i = 0; i < inventory.getSizeInventory(); i++)
		{
			ItemStack stack = inventory.getStackInSlot(i);
			if(type.isAmmo(stack))
			{
				return true;
			}
		}
		return false;
	}
	
	private ItemStack getBestNonEmptyShootableStack(ItemStack stack)
	{
		for(int i = 0; i < type.numAmmoItemsInGun; i++)
		{
			ItemStack shootableStack = getBulletItemStack(stack, i);
			if(shootableStack != null && shootableStack.getItem() != null && shootableStack.getItemDamage() < shootableStack.getMaxDamage())
			{
				return shootableStack;
			}
		}
		return null;
	}
		
	
	// _____________________________________________________________________________
	//
	// Minecraft base item overrides
	// _____________________________________________________________________________
	
	@Override
	public void addInformation(ItemStack stack, EntityPlayer player, List lines, boolean advancedTooltips)
	{
		if(type.description != null)
		{
			Collections.addAll(lines, type.description.split("_"));
		}
		if(type.showDamage)
			lines.add("\u00a79Damage" + "\u00a77: " + type.getDamage(stack));
		if(type.showRecoil)
			lines.add("\u00a79Recoil" + "\u00a77: " + type.getRecoil(stack));
		if(type.showSpread)
			lines.add("\u00a79Accuracy" + "\u00a77: " + type.getSpread(stack));
		if(type.showReloadTime)
			lines.add("\u00a79Reload Time" + "\u00a77: " + type.getReloadTime(stack) / 20 + "s");
		for(AttachmentType attachment : type.getCurrentAttachments(stack))
		{
			if(type.showAttachments)
			{
				String line = attachment.name;
				lines.add(line);
			}
		}
		for(int i = 0; i < type.numAmmoItemsInGun; i++)
		{
			ItemStack bulletStack = getBulletItemStack(stack, i);
			if(bulletStack != null && bulletStack.getItem() instanceof ItemBullet)
			{
				BulletType bulletType = ((ItemBullet)bulletStack.getItem()).type;					
				//String line = bulletType.name + (bulletStack.getMaxDamage() == 1 ? "" : " " + (bulletStack.getMaxDamage() - bulletStack.getItemDamage()) + "/" + bulletStack.getMaxDamage());
				String line = bulletType.name + " " + (bulletStack.getMaxDamage() - bulletStack.getItemDamage()) + "/" + bulletStack.getMaxDamage();
				lines.add(line);
			}
		}
	}
	
	@Override
	/** Make sure client and server side NBTtags update */
	public boolean getShareTag()
	{
		return true;
	}
	
	public DamageSource getMeleeDamage(EntityPlayer attacker)
	{
		return new EntityDamageSourceGun(type.shortName, attacker, attacker, type, false);
	}
	
	private boolean isSolid(World world, int i, int j, int k)
	{
		Block block = world.getBlockState(new BlockPos(i, j, k)).getBlock();
		if (block == null)
			return false;
		return block.getMaterial().isSolid() && block.isOpaqueCube();
	}
	
	//Stop damage being done to entities when scoping etc.
	@Override
	public boolean onLeftClickEntity(ItemStack stack, EntityPlayer player, Entity entity)
	{
		return type.secondaryFunction != EnumSecondaryFunction.MELEE;
	}

	@Override
	public boolean isFull3D()
	{
		return true;
	}
	
	@Override
	public boolean onEntitySwing(EntityLivingBase entityLiving, ItemStack stack)
	{
		if (type.meleeSound != null)
			PacketPlaySound.sendSoundPacket(entityLiving.posX, entityLiving.posY, entityLiving.posZ, FlansMod.soundRange, entityLiving.dimension, type.meleeSound, true);
		//Do custom melee code here
		if(type.secondaryFunction == EnumSecondaryFunction.CUSTOM_MELEE)
		{
			//Do animation
			if(entityLiving.worldObj.isRemote)
			{
				GunAnimations animations = FlansModClient.getGunAnimations(entityLiving, false);
				animations.doMelee(type.meleeTime);
			}
			//Do custom melee hit detection
			if(entityLiving instanceof EntityPlayer)
			{
				PlayerData data = PlayerHandler.getPlayerData((EntityPlayer)entityLiving);
				data.doMelee((EntityPlayer)entityLiving, type.meleeTime, type);
			}
		}
		return type.secondaryFunction != EnumSecondaryFunction.MELEE;
	}
	
	@Override
    public boolean onBlockStartBreak(ItemStack itemstack, BlockPos pos, EntityPlayer player)
    {
        return true;
    }

	@Override
    public boolean canHarvestBlock(Block p_150897_1_)
    {
        return false;
    }
    
	@Override
	@SideOnly(Side.CLIENT)
	public int getColorFromItemStack(ItemStack par1ItemStack, int par2)
	{
		return type.colour;
	}

	public boolean isItemStackDamageable()
	{
		return true;
	}
	
    @Override
    public void getSubItems(Item item, CreativeTabs tabs, List list)
    {
    	GunType type = ((ItemGun)item).type;
    	if(FlansMod.addAllPaintjobsToCreative)
    	{
    		for(Paintjob paintjob : type.paintjobs)
    			addPaintjobToList(item, type, paintjob, list);
    	}
        else addPaintjobToList(item, type, type.defaultPaintjob, list);
    }
    
    private void addPaintjobToList(Item item, GunType type, Paintjob paintjob, List list)
    {
    	ItemStack gunStack = new ItemStack(item, 1, paintjob.ID);
    	NBTTagCompound tags = new NBTTagCompound();
    	gunStack.setTagCompound(tags);
        list.add(gunStack);
    }
	
    @Override
    public int getMaxItemUseDuration(ItemStack par1ItemStack)
    {
        return 100;
    }
    
    @Override
    public EnumAction getItemUseAction(ItemStack par1ItemStack)
    {
        return EnumAction.BOW;
    }
	
    @Override
    public Multimap getAttributeModifiers(ItemStack stack)
    {
       	Multimap map = super.getAttributeModifiers(stack);
       	if(type.knockbackModifier != 0F)
       		map.put(SharedMonsterAttributes.knockbackResistance.getAttributeUnlocalizedName(), new AttributeModifier(itemModifierUUID, "KnockbackResist", type.knockbackModifier, 0));
       	if(type.moveSpeedModifier != 1F)
       		map.put(SharedMonsterAttributes.movementSpeed.getAttributeUnlocalizedName(), new AttributeModifier(itemModifierUUID, "MovementSpeed", type.moveSpeedModifier - 1F, 2));
        if(type.secondaryFunction == EnumSecondaryFunction.MELEE)
        	map.put(SharedMonsterAttributes.attackDamage.getAttributeUnlocalizedName(), new AttributeModifier(itemModifierUUID, "Weapon modifier", type.meleeDamage, 0));
       	return map;
    }

	@Override
	public InfoType getInfoType() 
	{
		return type;
	}
	
	@Override
    public boolean shouldCauseReequipAnimation(ItemStack oldStack, ItemStack newStack, boolean slotChanged)
    {
        return slotChanged;
    }
	
	// For when we have custom paintjob names
	//@Override
    //public String getUnlocalizedName(ItemStack stack)
    //{
    //    return getUnlocalizedName();//stack.getTagCompound().getString("Paint");
    //}
    
}
