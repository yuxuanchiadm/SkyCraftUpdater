package org.skycraft.updater.forge;

import io.netty.buffer.Unpooled;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.FileHandler;
import java.util.logging.SimpleFormatter;
import java.util.stream.Collectors;
import net.minecraft.network.IPacket;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.fml.loading.moddiscovery.ModFileInfo;
import net.minecraftforge.fml.network.ICustomPacket;
import net.minecraftforge.fml.network.NetworkDirection;
import net.minecraftforge.forgespi.language.IModFileInfo;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.skycraft.updater.core.Updater;

@Mod("updater")
public final class ForgeMain {
	public static final Logger LOGGER = LogManager.getLogger();

	public static final ForgeConfigSpec CONFIG;
	public static final ForgeConfigSpec.BooleanValue CONFIG_ENABLE_UPDATE;
	public static final ForgeConfigSpec.BooleanValue CONFIG_CONTACT_SERVER;
	public static final ForgeConfigSpec.ConfigValue<List<? extends String>> CONFIG_IGNORES;
	public static final ForgeConfigSpec.ConfigValue<String> CONFIG_SERVER_IP;
	public static final ForgeConfigSpec.IntValue CONFIG_SERVER_PORT;

	static {
		ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

		builder.push("general");
		CONFIG_ENABLE_UPDATE = builder.define("enableUpdate", true);
		CONFIG_CONTACT_SERVER = builder.define("contactServer", true);
		CONFIG_IGNORES = builder.defineListAllowEmpty(Collections.singletonList("ignores"), ArrayList::new, String.class::isInstance);
		CONFIG_SERVER_IP = builder.define("serverIp", "127.0.0.1");
		CONFIG_SERVER_PORT = builder.defineInRange("serverPort", 80, 0, 0xFFFF);
		builder.pop();

		CONFIG = builder.build();
	}

	public ForgeMain() {
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::clientSetup);
		ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, CONFIG);
		MinecraftForge.EVENT_BUS.addListener(this::playerLogin);
	}

	private void playerLogin(ClientPlayerNetworkEvent.LoggedInEvent event) {
		if (!CONFIG_CONTACT_SERVER.get()) return;
        PacketBuffer pb = new PacketBuffer(Unpooled.buffer());
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		try {
			bos.write("updater".getBytes(StandardCharsets.UTF_8));
			bos.write(0);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
        pb.writeBytes(bos.toByteArray());
        ICustomPacket<IPacket<?>> iPacketICustomPacket = NetworkDirection.PLAY_TO_SERVER.buildPacket(Pair.of(pb, 0), new ResourceLocation("minecraft:register"));
        event.getNetworkManager().send(iPacketICustomPacket.getThis());
	}

	public void clientSetup(FMLClientSetupEvent event) {
		if (!CONFIG_ENABLE_UPDATE.get()) return;
		IModFileInfo imodFileInfo = ModLoadingContext.get().getActiveContainer().getModInfo().getOwningFile();
		if (!(imodFileInfo instanceof ModFileInfo)) return;
		ModFileInfo modFileInfo = (ModFileInfo) imodFileInfo;

		List<Path> ignores;
		try {
			ignores = CONFIG_IGNORES.get().stream().map(Paths::get).collect(Collectors.toList());
		} catch (InvalidPathException e) {
			LOGGER.warn("Invalid ignore path", e);
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
			new InetSocketAddress(CONFIG_SERVER_IP.get(), CONFIG_SERVER_PORT.get()),
			modFileInfo.getFile().getFilePath(),
			FMLPaths.GAMEDIR.get(),
			ignores,
			() -> System.exit(0)
		);
		updater.run();
	}
}
