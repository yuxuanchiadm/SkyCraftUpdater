package org.skycraft.updater.forge;

import com.google.gson.stream.JsonReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraftforge.common.config.Config;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.ModContainer;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.relauncher.Side;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Logger;
import org.skycraft.updater.patcher.Patcher;

@Mod(modid = "updater", clientSideOnly = true)
public class Updater {
	private Logger logger;
	private ModContainer modContainer;
	private Path modsPath;

	@Mod.EventHandler
	public void preInit(FMLPreInitializationEvent event) {
		logger = event.getModLog();
		modContainer = Loader.instance().activeModContainer();

		NetworkRegistry.INSTANCE.newChannel("updater");

		if (event.getSide() == Side.SERVER) return;
		if (!Configs.enableUpdate) return;

		File canonicalModsDir;
		try {
			Field field = Loader.class.getDeclaredField("canonicalModsDir");
			field.setAccessible(true);
			canonicalModsDir = (File) field.get(Loader.instance());
		} catch (ReflectiveOperationException e) {
			throw new RuntimeException(e);
		}
		modsPath = canonicalModsDir.toPath();

		checkUpdate().ifPresent(serverHashes -> {
			serverHashes.forEach((path, hash) -> logger.log(Level.INFO, "Server respond mod \"" + path + "\" with hash \"" + hash + "\""));
			calcHashes().ifPresent(clientHashes -> {
				clientHashes.forEach((path, hash) -> logger.log(Level.INFO, "Client found mod \"" + path + "\" with hash \"" + hash + "\""));
				updateMods(serverHashes, clientHashes);
			});
		});
	}

	private Optional<Map<Path, String>> checkUpdate() {
		logger.log(Level.INFO, "Contacting update server...");

		try {
			URLConnection connection = new URL("http", Configs.serverIp, Configs.serverPort, "/hashes").openConnection();
			if (!(connection instanceof HttpURLConnection)) {
				logger.log(Level.WARN, "Could not contact update server");
				return Optional.empty();
			}
			HttpURLConnection http = (HttpURLConnection) connection;
			try {
				http.setRequestMethod("GET");
				http.connect();
				if (http.getResponseCode() != 200) {
					logger.log(Level.WARN, "Could not contact update server, response code = " + http.getResponseCode());
					return Optional.empty();
				}
				try (JsonReader json = new JsonReader(new InputStreamReader(http.getInputStream(), StandardCharsets.UTF_8))) {
					return parseHashes(json);
				} catch (IllegalStateException e) {
					logger.log(Level.WARN, "Illegal hashes format", e);
					return Optional.empty();
				}
			} finally {
				http.disconnect();
			}
		} catch (IOException e) {
			logger.log(Level.WARN, "Could not contact update server", e);
			return Optional.empty();
		}
	}

	private Optional<Map<Path, String>> parseHashes(JsonReader json) throws IOException {
		Map<Path, String> hashes = new HashMap<>();
		json.beginArray();
		while (json.hasNext()) {
			json.beginObject();
			Path path = null;
			String hash = null;
			while (json.hasNext()) {
				switch (json.nextName()) {
				case "path":
					try {
						path = Paths.get(json.nextString());
					} catch (InvalidPathException e) {
						logger.log(Level.WARN, "Illegal hashes format", e);
						return Optional.empty();
					}
					break;
				case "hash":
					hash = json.nextString();
					break;
				default:
					logger.log(Level.WARN, "Illegal hashes format");
					return Optional.empty();
				}
			}
			if (path == null || hash == null) {
				logger.log(Level.WARN, "Illegal hashes format");
				return Optional.empty();
			}
			hashes.put(path, hash);
			json.endObject();
		}
		json.endArray();
		return Optional.of(hashes);
	}

	private Optional<Map<Path, String>> calcHashes() {
		logger.log(Level.INFO, "Calculating mod hashes...");

		Map<Path, String> hashes = new HashMap<>();
		try {
			for (Path path : (Iterable<? extends Path>) Files.walk(modsPath)::iterator) {
				if (!path.getFileName().toString().endsWith(".jar")) continue;
				if (!Files.isRegularFile(path)) continue;
				String hash;
				try (InputStream stream = Files.newInputStream(path)) {
					hash = DigestUtils.md5Hex(stream);
				} catch (IOException e) {
					logger.log(Level.WARN, "Could not calculate mod hash", e);
					continue;
				}
				hashes.put(modsPath.relativize(path), hash);
			}
		} catch (IOException e) {
			logger.log(Level.WARN, "Error occurred while calculating mod hashes", e);
			return Optional.empty();
		}
		return Optional.of(hashes);
	}

	private void updateMods(Map<Path, String> serverHashes, Map<Path, String> clientHashes) {
		logger.log(Level.INFO, "Calculating mod differences...");

		Map<Path, String> modsToRemove;
		Map<Path, String> modsToUpdate;

		modsToRemove = new HashMap<>(clientHashes);
		modsToRemove.keySet().removeAll(serverHashes.keySet());
		modsToRemove.keySet().removeIf(path -> Arrays.asList(Configs.ignores).contains(path.toString()));

		modsToUpdate = new HashMap<>(serverHashes);
		modsToUpdate.entrySet().removeAll(clientHashes.entrySet());

		modsToRemove.keySet().forEach(path -> logger.log(Level.INFO, "Found mod \"" + path + "\" to remove"));
		modsToUpdate.keySet().forEach(path -> logger.log(Level.INFO, "Found mod \"" + path + "\" to update"));

		if (modsToRemove.isEmpty() && modsToUpdate.isEmpty()) {
			logger.log(Level.INFO, "No updates found");
			return;
		}

		if (!modContainer.getSource().isFile()) {
			logger.log(Level.WARN, "Could not find patcher jar, skipping update");
			return;
		}

		logger.log(Level.INFO, "Starting patcher process ...");

		Path sourcePath = modContainer.getSource().toPath();
		Path patcherPath;
		try {
			patcherPath = Files.copy(sourcePath, Files.createTempFile("Patcher", ".jar"), StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			logger.log(Level.WARN, "Could not copy patcher", e);
			return;
		}

		try {
			String javaHome = System.getProperty("java.home");
        	String javaBin = javaHome + File.separator + "bin" + File.separator + "java";
			Process process = Runtime.getRuntime().exec(new String[] { javaBin, "-cp" , patcherPath.toAbsolutePath().toString(), Patcher.class.getName() });
			try (DataOutputStream stream = new DataOutputStream(process.getOutputStream())) {
				stream.writeUTF(Configs.serverIp);
				stream.writeInt(Configs.serverPort);
				stream.writeUTF(modsPath.toAbsolutePath().toString());
				stream.writeInt(modsToRemove.size());
				for (Map.Entry<Path, String> entry : modsToRemove.entrySet()) {
					stream.writeUTF(entry.getKey().toString());
					stream.writeUTF(entry.getValue());
				}
				stream.writeInt(modsToUpdate.size());
				for (Map.Entry<Path, String> entry : modsToUpdate.entrySet()) {
					stream.writeUTF(entry.getKey().toString());
					stream.writeUTF(entry.getValue());
				}
			}
		} catch (IOException e) {
			logger.log(Level.WARN, "Could not start patcher process", e);
			return;
		}
		FMLCommonHandler.instance().exitJava(0, true);
	}

	@Config(modid = "updater")
	public static class Configs {
		public static boolean enableUpdate = true;

		public static String[] ignores = {};

		public static String serverIp = "127.0.0.1";

		@Config.RangeInt(min = 0, max = 0xFFFF)
		public static int serverPort = 80;
	}
}
