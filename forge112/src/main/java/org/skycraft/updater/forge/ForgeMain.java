package org.skycraft.updater.forge;

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
import java.util.logging.FileHandler;
import java.util.logging.SimpleFormatter;
import java.util.stream.Collectors;
import net.minecraftforge.common.config.Config;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.ModContainer;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.relauncher.Side;
import org.apache.logging.log4j.Logger;
import org.skycraft.updater.core.Updater;

@Mod(modid = "updater", clientSideOnly = true)
public final class ForgeMain {
	@Mod.EventHandler
	public void preInit(FMLPreInitializationEvent event) {
		Logger logger = event.getModLog();
		ModContainer modContainer = Loader.instance().activeModContainer();

		if (Configs.contactServer) NetworkRegistry.INSTANCE.newChannel("updater");

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

	@Config(modid = "updater")
	public static class Configs {
		public static boolean enableUpdate = true;

		public static boolean contactServer = true;

		public static String[] ignores = {};

		public static String serverIp = "127.0.0.1";

		@Config.RangeInt(min = 0, max = 0xFFFF)
		public static int serverPort = 80;
	}
}
