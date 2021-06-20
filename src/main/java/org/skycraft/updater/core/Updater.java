package org.skycraft.updater.core;

import com.google.gson.stream.JsonReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Logger;

public final class Updater implements Runnable {
	private final Logger logger;
	private final InetSocketAddress address;
	private final Path patcherPath;
	private final Path modsPath;
	private final List<Path> ignores;
	private final Runnable exitJava;

	public Updater(Logger logger, InetSocketAddress address, Path patcherPath, Path modsPath, List<Path> ignores, Runnable exitJava) {
		this.logger = logger;
		this.address = address;
		this.patcherPath = patcherPath;
		this.modsPath = modsPath;
		this.ignores = ignores;
		this.exitJava = exitJava;
	}

	@Override
	public void run() {
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
			URLConnection connection = new URL("http", address.getHostString(), address.getPort(), "/hashes").openConnection();
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
		modsToRemove.keySet().removeIf(ignores::contains);

		modsToUpdate = new HashMap<>(serverHashes);
		modsToUpdate.entrySet().removeAll(clientHashes.entrySet());

		modsToRemove.keySet().forEach(path -> logger.log(Level.INFO, "Found mod \"" + path + "\" to remove"));
		modsToUpdate.keySet().forEach(path -> logger.log(Level.INFO, "Found mod \"" + path + "\" to update"));

		if (modsToRemove.isEmpty() && modsToUpdate.isEmpty()) {
			logger.log(Level.INFO, "No updates found");
			return;
		}

		if (!Files.isRegularFile(patcherPath)) {
			logger.log(Level.WARN, "Could not find patcher jar, skipping update");
			return;
		}

		logger.log(Level.INFO, "Starting patcher process ...");

		Path patcherPath;
		try {
			patcherPath = Files.copy(this.patcherPath, Files.createTempFile("Patcher", ".jar"), StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			logger.log(Level.WARN, "Could not copy patcher", e);
			return;
		}

		try {
			String javaHome = System.getProperty("java.home");
        	String javaBin = javaHome + File.separator + "bin" + File.separator + "java";
			Process process = Runtime.getRuntime().exec(new String[] { javaBin, "-cp" , patcherPath.toAbsolutePath().toString(), Patcher.class.getName() });
			try (DataOutputStream stream = new DataOutputStream(process.getOutputStream())) {
				stream.writeUTF(address.getHostString());
				stream.writeInt(address.getPort());
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
		exitJava.run();
	}
}
