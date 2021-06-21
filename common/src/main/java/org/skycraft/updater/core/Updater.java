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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.commons.codec.digest.DigestUtils;

public final class Updater implements Runnable {
	private final Logger logger;
	private final InetSocketAddress address;
	private final Path patcherPath;
	private final Path clientPath;
	private final List<Path> ignores;
	private final Runnable exitJava;

	public Updater(Logger logger, InetSocketAddress address, Path patcherPath, Path clientPath, List<Path> ignores, Runnable exitJava) {
		this.logger = logger;
		this.address = address;
		this.patcherPath = patcherPath;
		this.clientPath = clientPath;
		this.ignores = ignores;
		this.exitJava = exitJava;
	}

	@Override
	public void run() {
		Map<Path, String> globalServerHashes = new HashMap<>();
		Map<Path, String> globalClientHashes = new HashMap<>();
		downloadManifest().forEach((category, categoryPath) -> {
			logger.log(Level.INFO, "Checking update for category \"" + category + "\" at path \"" + categoryPath.toString() + "\"");
			downloadHashes(category, categoryPath).ifPresent(serverHashes -> {
				serverHashes.forEach((path, hash) -> logger.log(Level.INFO, "Server respond file \"" + path + "\" with hash \"" + hash + "\""));
				calcHashes(category, categoryPath).ifPresent(clientHashes -> {
					clientHashes.forEach((path, hash) -> logger.log(Level.INFO, "Client found file \"" + path + "\" with hash \"" + hash + "\""));
					globalServerHashes.putAll(serverHashes);
					globalClientHashes.putAll(clientHashes);
				});
			});
		});
		updateFiles(globalServerHashes, globalClientHashes);
	}

	private Map<String, Path> downloadManifest() {
		logger.log(Level.INFO, "Downloading manifest...");

		try {
			URLConnection connection = new URL("http", address.getHostString(), address.getPort(), "/manifest").openConnection();
			if (!(connection instanceof HttpURLConnection)) {
				logger.log(Level.WARNING, "Could not contact update server");
				return Collections.emptyMap();
			}
			HttpURLConnection http = (HttpURLConnection) connection;
			try {
				http.setRequestMethod("GET");
				http.connect();
				if (http.getResponseCode() != 200) {
					logger.log(Level.WARNING, "Could not contact update server, response code = " + http.getResponseCode());
					return Collections.emptyMap();
				}
				try (JsonReader json = new JsonReader(new InputStreamReader(http.getInputStream(), StandardCharsets.UTF_8))) {
					return parseManifest(json);
				} catch (IllegalStateException e) {
					logger.log(Level.WARNING, "Illegal hashes format", e);
					return Collections.emptyMap();
				}
			} finally {
				http.disconnect();
			}
		} catch (IOException e) {
			logger.log(Level.WARNING, "Could not download manifest", e);
			return Collections.emptyMap();
		}
	}

	private Map<String, Path> parseManifest(JsonReader json) throws IOException {
		Map<String, Path> manifest = new HashMap<>();
		json.beginArray();
		while (json.hasNext()) {
			json.beginObject();
			String category = null;
			Path path = null;
			while (json.hasNext()) {
				switch (json.nextName()) {
				case "category":
					category = json.nextString();
					break;
				case "path":
					try {
						path = Paths.get(json.nextString());
					} catch (InvalidPathException e) {
						logger.log(Level.WARNING, "Illegal hashes format", e);
						return Collections.emptyMap();
					}
					break;
				default:
					logger.log(Level.WARNING, "Illegal hashes format");
					return Collections.emptyMap();
				}
			}
			if (category == null || path == null) {
				logger.log(Level.WARNING, "Illegal hashes format");
				return Collections.emptyMap();
			}
			manifest.put(category, path);
			json.endObject();
		}
		json.endArray();
		return manifest;
	}

	private Optional<Map<Path, String>> downloadHashes(String category, Path categoryPath) {
		logger.log(Level.INFO, "Downloading category \"" + category + "\" hashes...");

		try {
			URLConnection connection = new URL("http", address.getHostString(), address.getPort(), "/hashes?category=" + URLEncoder.encode(category, "UTF-8")).openConnection();
			if (!(connection instanceof HttpURLConnection)) {
				logger.log(Level.WARNING, "Could not contact update server");
				return Optional.empty();
			}
			HttpURLConnection http = (HttpURLConnection) connection;
			try {
				http.setRequestMethod("GET");
				http.connect();
				if (http.getResponseCode() != 200) {
					logger.log(Level.WARNING, "Could not contact update server, response code = " + http.getResponseCode());
					return Optional.empty();
				}
				try (JsonReader json = new JsonReader(new InputStreamReader(http.getInputStream(), StandardCharsets.UTF_8))) {
					return parseHashes(json, categoryPath);
				} catch (IllegalStateException e) {
					logger.log(Level.WARNING, "Illegal hashes format", e);
					return Optional.empty();
				}
			} finally {
				http.disconnect();
			}
		} catch (IOException e) {
			logger.log(Level.WARNING, "Could not download category \"" + category + "\" hashes", e);
			return Optional.empty();
		}
	}

	private Optional<Map<Path, String>> parseHashes(JsonReader json, Path categoryPath) throws IOException {
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
						path = categoryPath.resolve(Paths.get(json.nextString()));
					} catch (InvalidPathException e) {
						logger.log(Level.WARNING, "Illegal hashes format", e);
						return Optional.empty();
					}
					break;
				case "hash":
					hash = json.nextString();
					break;
				default:
					logger.log(Level.WARNING, "Illegal hashes format");
					return Optional.empty();
				}
			}
			if (path == null || hash == null) {
				logger.log(Level.WARNING, "Illegal hashes format");
				return Optional.empty();
			}
			hashes.put(path, hash);
			json.endObject();
		}
		json.endArray();
		return Optional.of(hashes);
	}

	private Optional<Map<Path, String>> calcHashes(String category, Path categoryPath) {
		logger.log(Level.INFO, "Calculating category \"" + category + "\" file hashes...");

		Map<Path, String> hashes = new HashMap<>();
		try {
			if (Files.exists(categoryPath)) {
				for (Path path : (Iterable<? extends Path>) Files.walk(categoryPath)::iterator) {
					if (!Files.isRegularFile(path))
						continue;
					String hash;
					try (InputStream stream = Files.newInputStream(path)) {
						hash = DigestUtils.md5Hex(stream);
					} catch (IOException e) {
						logger.log(Level.WARNING, "Could not calculate file hash", e);
						continue;
					}
					hashes.put(path, hash);
				}
			}
		} catch (IOException e) {
			logger.log(Level.WARNING, "Error occurred while calculating category \"" + category + "\" file hashes", e);
			return Optional.empty();
		}
		return Optional.of(hashes);
	}

	private void updateFiles(Map<Path, String> serverHashes, Map<Path, String> clientHashes) {
		logger.log(Level.INFO, "Calculating file differences...");

		Map<Path, String> filesToRemove;
		Map<Path, String> filesToUpdate;

		filesToRemove = new HashMap<>(clientHashes);
		filesToRemove.keySet().removeAll(serverHashes.keySet());
		filesToRemove.keySet().removeIf(ignores::contains);

		filesToUpdate = new HashMap<>(serverHashes);
		filesToUpdate.entrySet().removeAll(clientHashes.entrySet());

		filesToRemove.keySet().forEach(path -> logger.log(Level.INFO, "Found file \"" + path + "\" to remove"));
		filesToUpdate.keySet().forEach(path -> logger.log(Level.INFO, "Found file \"" + path + "\" to update"));

		if (filesToRemove.isEmpty() && filesToUpdate.isEmpty()) {
			logger.log(Level.INFO, "No updates found");
			return;
		}

		if (!Files.isRegularFile(patcherPath)) {
			logger.log(Level.WARNING, "Could not find patcher jar, skipping update");
			return;
		}

		logger.log(Level.INFO, "Starting patcher process ...");

		Path patcherPath;
		try {
			patcherPath = Files.copy(this.patcherPath, Files.createTempFile("Patcher", ".jar"), StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			logger.log(Level.WARNING, "Could not copy patcher", e);
			return;
		}

		try {
			String javaHome = System.getProperty("java.home");
        	String javaBin = javaHome + File.separator + "bin" + File.separator + "java";
			Process process = Runtime.getRuntime().exec(new String[] { javaBin, "-cp" , patcherPath.toAbsolutePath().toString(), Patcher.class.getName() });
			try (DataOutputStream stream = new DataOutputStream(process.getOutputStream())) {
				stream.writeUTF(address.getHostString());
				stream.writeInt(address.getPort());
				stream.writeUTF(clientPath.toAbsolutePath().toString());
				stream.writeInt(filesToRemove.size());
				for (Map.Entry<Path, String> entry : filesToRemove.entrySet()) {
					stream.writeUTF(entry.getKey().toString());
					stream.writeUTF(entry.getValue());
				}
				stream.writeInt(filesToUpdate.size());
				for (Map.Entry<Path, String> entry : filesToUpdate.entrySet()) {
					stream.writeUTF(entry.getKey().toString());
					stream.writeUTF(entry.getValue());
				}
			}
		} catch (IOException e) {
			logger.log(Level.WARNING, "Could not start patcher process", e);
			return;
		}
		exitJava.run();
	}
}
