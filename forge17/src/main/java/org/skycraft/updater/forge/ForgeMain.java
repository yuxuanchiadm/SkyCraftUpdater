package org.skycraft.updater.forge;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.ModContainer;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.network.NetworkCheckHandler;
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.relauncher.Side;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerAdapter;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.logging.FileHandler;
import java.util.logging.SimpleFormatter;
import java.util.stream.Collectors;
import net.minecraftforge.common.config.Configuration;
import org.apache.logging.log4j.Logger;
import org.skycraft.updater.core.Updater;

@Mod(modid = "updater")
public final class ForgeMain {
	@NetworkCheckHandler
    public boolean checkModList(Map<String, String> versions, Side side) {
        return true;
    }

	@Mod.EventHandler
	public void preInit(FMLPreInitializationEvent event) {
		Configuration configuration = new Configuration(new File(event.getModConfigurationDirectory(), "updater.cfg"));
		configuration.load();
		Configs.enableUpdate = configuration.getBoolean("enableUpdate", "general", Configs.enableUpdate, "Enable update");
		Configs.contactServer = configuration.getBoolean("contactServer", "general", Configs.contactServer, "Contact server");
		Configs.ignores = configuration.getStringList("ignores", "general", Configs.ignores, "Ignores");
		Configs.serverIp = configuration.getString("serverIp", "general", Configs.serverIp, "Server ip");
		Configs.serverPort = configuration.getInt("serverPort", "general", Configs.serverPort, 0, 0xFFFF, "Server port");
		if (configuration.hasChanged()) configuration.save();

		Logger logger = event.getModLog();
		ModContainer modContainer = Loader.instance().activeModContainer();

		if (Configs.contactServer) NetworkRegistry.INSTANCE.newChannel("updater", new UpdaterChannelHandler());

		if (event.getSide() == Side.SERVER) return;
		if (!Configs.enableUpdate) return;
		if (modContainer == null) return;

		File minecraftDir;
		try {
			Field field = Loader.class.getDeclaredField("minecraftDir");
			field.setAccessible(true);
			minecraftDir = (File) field.get(Loader.instance());
		} catch (ReflectiveOperationException e) {
			throw new RuntimeException(e);
		}
		Path clientPath = minecraftDir.toPath();

		List<Path> ignores;
		try {
			ignores = Arrays.stream(Configs.ignores).map(Paths::get).collect(Collectors.toList());
		} catch (InvalidPathException e) {
			logger.warn("Invalid ignore path", e);
			ignores = Collections.emptyList();
		}

		java.util.logging.Logger updaterLogger = java.util.logging.Logger.getLogger("Updater");
		try {
			FileHandler fileHandler = new FileHandler("skycraft-updater.log");
			fileHandler.setFormatter(new SimpleFormatter());
			updaterLogger.addHandler(fileHandler);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		Updater updater = new Updater(
			updaterLogger,
			new InetSocketAddress(Configs.serverIp, Configs.serverPort),
			modContainer.getSource().toPath(),
			clientPath,
			ignores,
			() -> FMLCommonHandler.instance().exitJava(0, true)
		);
		updater.run();
	}

	public static class Configs {
		public static boolean enableUpdate = true;

		public static boolean contactServer = true;

		public static String[] ignores = {};

		public static String serverIp = "127.0.0.1";

		public static int serverPort = 80;
	}

	@ChannelHandler.Sharable
	public static class UpdaterChannelHandler extends ChannelHandlerAdapter {

	}
}
