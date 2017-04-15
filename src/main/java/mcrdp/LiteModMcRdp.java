package mcrdp;

import static net.propero.rdp.Rdesktop.*;  // Unfortunate use of global variables
import static org.lwjgl.opengl.GL11.*;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.BufferUtils;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.INetHandler;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.SPacketChunkData;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.propero.rdp.Common;
import net.propero.rdp.ConnectionException;
import net.propero.rdp.Constants;
import net.propero.rdp.KeyCode_FileBased_Localised;
import net.propero.rdp.Options;
import net.propero.rdp.Rdesktop;
import net.propero.rdp.RdesktopCanvas;
import net.propero.rdp.RdesktopException;
import net.propero.rdp.RdesktopFrame;
import net.propero.rdp.RdesktopFrame_Localised;
import net.propero.rdp.Rdp;
import net.propero.rdp.Version;
import net.propero.rdp.keymapping.KeyCode_FileBased;
import net.propero.rdp.rdp5.Rdp5;
import net.propero.rdp.rdp5.VChannels;
import net.propero.rdp.tools.SendEvent;

import com.google.gson.annotations.Expose;
import com.mumfrey.liteloader.LiteMod;
import com.mumfrey.liteloader.PacketHandler;
import com.mumfrey.liteloader.PlayerClickListener;
import com.mumfrey.liteloader.PlayerInteractionListener.MouseButton;
import com.mumfrey.liteloader.PreRenderListener;
import com.mumfrey.liteloader.gl.GL;
import com.mumfrey.liteloader.modconfig.ExposableOptions;
import com.mumfrey.liteloader.RenderListener;

public class LiteModMcRdp implements LiteMod, PlayerClickListener, PacketHandler, PreRenderListener {
	// TODO: These things shouldn't be constant
	private String server = "pi";
	private String username = "pi";
	private int width = 800, height = 600;

	private Thread rdpThread;

	private Logger logger = LogManager.getLogger();

	private int textureID = -1;

	@Override
	public String getName() {
		return "mcrdp";
	}

	@Override
	public String getVersion() {
		return "0.1";
	}

	@Override
	public void init(File configPath) {
		initRDP();
		textureID = glGenTextures();
	}

	private RdesktopCanvas canvas;

	/** from {@link Rdesktop#main(String[])} */
	private void initRDP() {
		Runnable rdpRunnable = new Runnable() { @Override public void run() {

		keep_running = true;
		loggedon = false;
		readytosend = false;
		showTools = false;
		mapFile = "en-us";
		keyMapLocation = "";
		toolFrame = null;

		Options.username = LiteModMcRdp.this.username;
		Options.width = LiteModMcRdp.this.width;
		Options.height = LiteModMcRdp.this.height;

		// ... skip option parsing ...

		// Now do the startup...

		VChannels channels = new VChannels();

		// Initialise all RDP5 channels
		if (Options.use_rdp5) {
			// TODO: implement all relevant channels
			//if (Options.map_clipboard)
			//	channels.register(clipChannel);
		}

		// Now do the startup...

		logger.info("properJavaRDP version " + Version.version);

		//if (args.length == 0)
		//	usage();

		String java = System.getProperty("java.specification.version");
		logger.info("Java version is " + java);

		String os = System.getProperty("os.name");
		String osvers = System.getProperty("os.version");

		if (os.equals("Windows 2000") || os.equals("Windows XP"))
			Options.built_in_licence = true;

		logger.info("Operating System is " + os + " version " + osvers);

		if (os.startsWith("Linux"))
			Constants.OS = Constants.LINUX;
		else if (os.startsWith("Windows"))
			Constants.OS = Constants.WINDOWS;
		else if (os.startsWith("Mac"))
			Constants.OS = Constants.MAC;

		if (Constants.OS == Constants.MAC)
			Options.caps_sends_up_and_down = false;

		Rdp5 RdpLayer = null;
		Common.rdp = RdpLayer;
		RdesktopFrame window = new RdesktopFrame_Localised();
		window.setClip(null/*clipChannel*/);

		// Configure a keyboard layout
		KeyCode_FileBased keyMap = null;
		try {
			// logger.info("looking for: " + "/" + keyMapPath + mapFile);
			InputStream istr = Rdesktop.class.getResourceAsStream("/"
					+ keyMapPath + mapFile);
			// logger.info("istr = " + istr);
			if (istr == null) {
				logger.debug("Loading keymap from filename");
				keyMap = new KeyCode_FileBased_Localised(keyMapPath + mapFile);
			} else {
				logger.debug("Loading keymap from InputStream");
				keyMap = new KeyCode_FileBased_Localised(istr);
			}
			if (istr != null)
				istr.close();
			Options.keylayout = keyMap.getMapCode();
		} catch (Exception kmEx) {
			String[] msg = { (kmEx.getClass() + ": " + kmEx.getMessage()) };
			window.showErrorDialog(msg);
			kmEx.printStackTrace();
			Rdesktop.exit(0, null, null, true);
		}

		logger.debug("Registering keyboard...");
		if (keyMap != null)
			window.registerKeyboard(keyMap);

		boolean[] deactivated = new boolean[1];
		int[] ext_disc_reason = new int[1];

		logger.debug("keep_running = " + keep_running);
		while (keep_running) {
			logger.debug("Initialising RDP layer...");
			RdpLayer = new Rdp5(channels);
			Common.rdp = RdpLayer;
			logger.debug("Registering drawing surface...");
			RdpLayer.registerDrawingSurface(window);
			logger.debug("Registering comms layer...");
			window.registerCommLayer(RdpLayer);
			LiteModMcRdp.this.canvas = window.getCanvas();
			loggedon = false;
			readytosend = false;
			logger
					.info("Connecting to " + server + ":" + Options.port
							+ " ...");

			if (server.equalsIgnoreCase("localhost"))
				server = "127.0.0.1";

			if (RdpLayer != null) {
				// Attempt to connect to server on port Options.port
				try {
					RdpLayer.connect(Options.username, InetAddress
							.getByName(server), Rdp.RDP_LOGON_NORMAL, Options.domain,
							Options.password, Options.command,
							Options.directory);

					// Remove to get rid of sendEvent tool
					if (showTools) {
						toolFrame = new SendEvent(RdpLayer);
						toolFrame.show();
					}
					// End

					if (keep_running) {

						/*
						 * By setting encryption to False here, we have an
						 * encrypted login packet but unencrypted transfer of
						 * other packets
						 */
						if (!Options.packet_encryption)
							Options.encryption = false;

						logger.info("Connection successful");
						// now show window after licence negotiation
						RdpLayer.mainLoop(deactivated, ext_disc_reason);

						if (deactivated[0]) {
							/* clean disconnect */
							Rdesktop.exit(0, RdpLayer, window, true);
							// return 0;
						} else {
							if (ext_disc_reason[0] == exDiscReasonAPIInitiatedDisconnect
									|| ext_disc_reason[0] == exDiscReasonAPIInitiatedLogoff) {
								/*
								 * not so clean disconnect, but nothing to worry
								 * about
								 */
								Rdesktop.exit(0, RdpLayer, window, true);
								// return 0;
							}

							if (ext_disc_reason[0] >= 2) {
								String reason = textDisconnectReason(ext_disc_reason[0]);
								String msg[] = { "Connection terminated",
										reason };
								window.showErrorDialog(msg);
								logger.warn("Connection terminated: " + reason);
								Rdesktop.exit(0, RdpLayer, window, true);
							}

						}

						keep_running = false; // exited main loop
						if (!readytosend) {
							// maybe the licence server was having a comms
							// problem, retry?
							String msg1 = "The terminal server disconnected before licence negotiation completed.";
							String msg2 = "Possible cause: terminal server could not issue a licence.";
							String[] msg = { msg1, msg2 };
							logger.warn(msg1);
							logger.warn(msg2);
							window.showErrorDialog(msg);
						}
					} // closing bracket to if(running)

					// Remove to get rid of tool window
					if (showTools)
						toolFrame.dispose();
					// End

				} catch (ConnectionException e) {
					String msg[] = { "Connection Exception", e.getMessage() };
					window.showErrorDialog(msg);
					Rdesktop.exit(0, RdpLayer, window, true);
				} catch (UnknownHostException e) {
					error(e, RdpLayer, window, true);
				} catch (SocketException s) {
					if (RdpLayer.isConnected()) {
						logger.fatal(s.getClass().getName() + " "
								+ s.getMessage());
						// s.printStackTrace();
						error(s, RdpLayer, window, true);
						Rdesktop.exit(0, RdpLayer, window, true);
					}
				} catch (RdesktopException e) {
					String msg1 = e.getClass().getName();
					String msg2 = e.getMessage();
					logger.fatal(msg1 + ": " + msg2);

					e.printStackTrace(System.err);

					if (!readytosend) {
						// maybe the licence server was having a comms
						// problem, retry?
						String msg[] = {
								"The terminal server reset connection before licence negotiation completed.",
								"Possible cause: terminal server could not connect to licence server.",
								"Retry?" };
						boolean retry = window.showYesNoErrorDialog(msg);
						if (!retry) {
							logger.info("Selected not to retry.");
							Rdesktop.exit(0, RdpLayer, window, true);
						} else {
							if (RdpLayer != null && RdpLayer.isConnected()) {
								logger.info("Disconnecting ...");
								RdpLayer.disconnect();
								logger.info("Disconnected");
							}
							logger.info("Retrying connection...");
							keep_running = true; // retry
							continue;
						}
					} else {
						String msg[] = { e.getMessage() };
						window.showErrorDialog(msg);
						Rdesktop.exit(0, RdpLayer, window, true);
					}
				} catch (Exception e) {
					logger.warn(e.getClass().getName() + " " + e.getMessage());
					e.printStackTrace();
					error(e, RdpLayer, window, true);
				}
			} else { // closing bracket to if(!rdp==null)
				logger
						.fatal("The communications layer could not be initiated!");
			}
		}
		Rdesktop.exit(0, RdpLayer, window, true);
		}};
		rdpThread = new Thread(rdpRunnable, "RDP thread");
		rdpThread.start();
	}

	@Override
	public void upgradeSettings(String version, File configPath,
			File oldConfigPath) {

	}

	@Override
	public List<Class<? extends Packet<?>>> getHandledPackets() {
		List<Class<? extends Packet<?>>> packets = new ArrayList<Class<? extends Packet<?>>>();
		packets.add(SPacketChunkData.class);
		packets.add(SPacketUpdateTileEntity.class);
		return packets;
	}

	@Override
	public boolean handlePacket(INetHandler netHandler, Packet<?> packet) {
		if (packet instanceof SPacketChunkData) {
			SPacketChunkData cpacket = (SPacketChunkData) packet;
			for (NBTTagCompound tag : cpacket.getTileEntityTags()) {
				if (tag.getString("id").toLowerCase().contains("sign")) {
					handleNewTE(new BlockPos(tag.getInteger("x"), tag.getInteger("y"),
							tag.getInteger("z")), tag);
				}
			}
		} else if (packet instanceof SPacketUpdateTileEntity) {
			SPacketUpdateTileEntity cpacket = (SPacketUpdateTileEntity) packet;
			if (cpacket.getTileEntityType() == 9) {
				handleNewTE(cpacket.getPos(), cpacket.getNbtCompound());
			}
		}
		return true;
	}

	private Map<BlockPos, RDPInfo> infos = new HashMap<BlockPos, RDPInfo>();

	private void handleNewTE(BlockPos pos, NBTTagCompound tag) {
		String[] lines = new String[4];
		for (int i = 0; i < 4; i++) {
			lines[i] = tag.getString("Text" + (i + 1));
		}
		if (!lines[0].contains("mcrdp")) {
			// Nothing at all that can be wrong.
			return;
		}
		try {
			infos.put(pos, new RDPInfo(pos, lines));
			Minecraft.getMinecraft().ingameGUI.getChatGUI().printChatMessage(new TextComponentString("Test"));
		} catch (InvalidRDPException ex) {
			ITextComponent component = new TextComponentString(ex.getMessage());
			component.getStyle().setColor(TextFormatting.RED);
			Minecraft.getMinecraft().ingameGUI.getChatGUI().printChatMessage(component);
		}
	}

	@Override
	public boolean onMouseClicked(EntityPlayerSP player, MouseButton button) {
		return true;
	}

	@Override
	public boolean onMouseHeld(EntityPlayerSP player, MouseButton button) {
		return true;
	}

	@Override
	public void onSetupCameraTransform(float partialTicks, int pass,
			long timeSlice) {
		Minecraft minecraft = Minecraft.getMinecraft();

		if (this.canvas == null || minecraft.world == null) {
			if (!this.infos.isEmpty()) {
				Minecraft.getMinecraft().ingameGUI.getChatGUI().printChatMessage(new TextComponentString("Freeing " + this.infos.size() + " entries"));
				this.infos.clear();
			}
			return;
		}
		bindImage(canvas);

		EntityPlayerSP player = Minecraft.getMinecraft().player;
		double x = player.lastTickPosX + (player.posX - player.lastTickPosX) * (double)partialTicks;
		double y = player.lastTickPosY + (player.posY - player.lastTickPosY) * (double)partialTicks;
		double z = player.lastTickPosZ + (player.posZ - player.lastTickPosZ) * (double)partialTicks;

		for (Iterator<RDPInfo> itr = this.infos.values().iterator(); itr.hasNext();) {
			RDPInfo info = itr.next();
			// Check if unloaded, and delete as needed
			if (minecraft.world.getBlockState(info.pos).getBlock() != Blocks.WALL_SIGN) {
				Minecraft.getMinecraft().ingameGUI.getChatGUI().printChatMessage(new TextComponentString("Rem"));
				itr.remove();
				continue;
			}
			// Render as such
			try {
				glPushMatrix();
				glTranslated(-x, -y, -z);
				glTranslatef(info.pos.getX(), info.pos.getY(), info.pos.getZ());
				drawImage(8, 6); // TODO: auto-width
				glPopMatrix();
			} catch (RuntimeException ex) {
				ex.printStackTrace();
				throw ex;
			}
		}
	}

	private void bindImage(RdesktopCanvas image) {
		// http://www.java-gaming.org/index.php?topic=25516.0
		int[] pixels = image.getImage(0, 0, image.getWidth(), image.getHeight());

		ByteBuffer buffer = BufferUtils.createByteBuffer(image.getWidth() * image.getHeight() * 4); //4 for RGBA, 3 for RGB

		for(int y = 0; y < image.getHeight(); y++){
			for(int x = 0; x < image.getWidth(); x++){
				int pixel = pixels[y * image.getWidth() + x];
				buffer.put((byte) ((pixel >> 16) & 0xFF));     // Red component
				buffer.put((byte) ((pixel >> 8) & 0xFF));      // Green component
				buffer.put((byte) (pixel & 0xFF));               // Blue component
				buffer.put((byte) ((pixel >> 24) & 0xFF));    // Alpha component. Only for RGBA
			}
		}

		buffer.flip(); //FOR THE LOVE OF GOD DO NOT FORGET THIS

		// You now have a ByteBuffer filled with the color data of each pixel.
		// Now just create a texture ID and bind it. Then you can load it using 
		// whatever OpenGL method you want, for example:

		// Do the drawing
		glEnable(GL_TEXTURE_2D);

		glBindTexture(GL_TEXTURE_2D, textureID); //Bind texture ID
		glTexParameteri (GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
		glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, image.getWidth(), image.getHeight(), 0, GL_RGBA, GL_UNSIGNED_BYTE, buffer);
	}

	/**
	 * Draws the given image at the current location, using the given width and height values.
	 * @param width The width in blocks (NOT the width of the image)
	 * @param height The height in blocks (NOT the height of the image)
	 */
	private void drawImage(int width, int height) {
		glBegin(GL_QUADS);
		{
			// This needs to be flipped vertically for some reason...
			final float Z_PUSH = 2/16f; // To avoid z-fighting: slightly larger than a sign
			glTexCoord2f(0, 1);
			glVertex3f(0, 0, Z_PUSH);

			glTexCoord2f(1, 1);
			glVertex3f(width, 0, Z_PUSH);

			glTexCoord2f(1, 0);
			glVertex3f(width, height, Z_PUSH);

			glTexCoord2f(0, 0);
			glVertex3f(0, height, Z_PUSH);
		}
		glEnd();
	}

	@Override
	public void onRenderWorld(float partialTicks) {
	}

	@Override
	public void onRenderSky(float partialTicks, int pass) {
	}

	@Override
	public void onRenderClouds(float partialTicks, int pass,
			RenderGlobal renderGlobal) {
	}

	@Override
	public void onRenderTerrain(float partialTicks, int pass) {

	}
}