package org.skycraft.updater.core.protocol.v1;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringEscapeUtils;
import org.skycraft.updater.core.Protocol;
import org.skycraft.updater.core.Provider;
import org.skycraft.updater.core.data.ManifestEntry;
import org.skycraft.updater.core.data.ManifestPrecached;
import org.skycraft.updater.core.protocol.ProtocolHandler;
import org.skycraft.updater.utils.PathUtils;

public final class ProtocolHandlerV1 extends ProtocolHandler {
	private Map<String, ManifestEntry> entryMap;
	private Map<String, ManifestPrecached> precachedMap;
	private String precachedManifestJSON;
	private Map<String, Path> globalFiles;

	public ProtocolHandlerV1() {
		super(Protocol.V1);
	}

	@Override
	public boolean precacheManifest(Provider provider, JsonReader reader) throws IOException, IllegalStateException {
		Map<String, ManifestEntry> entryMap = new HashMap<>();
		StringWriter out = new StringWriter();
		try (JsonWriter writer = new JsonWriter(out)) {
			writer.setIndent("    ");
			reader.beginArray();
			writer.beginArray();
			while (reader.hasNext()) {
				reader.beginObject();
				writer.beginObject();
				String category = null;
				Path path = null;
				List<String> ignoreServer = new ArrayList<>();
				List<String> ignoreClient = new ArrayList<>();
				while (reader.hasNext()) {
					switch (reader.nextName()) {
					case "category":
						category = reader.nextString();
						writer.name("category").value(category);
						break;
					case "path":
						try {
							path = Paths.get(reader.nextString());
						} catch (InvalidPathException e) {
							provider.getLogger().log(Level.WARNING, "Illegal manifest format", e);
							return false;
						}
						writer.name("path").value(path.toString().replace('\\', '/'));
						break;
					case "ignore-server":
						reader.beginArray();
						while (reader.hasNext()) {
							String ignore = reader.nextString();
							ignoreServer.add(ignore);
						}
						reader.endArray();
						break;
					case "ignore-client":
						reader.beginArray();
						while (reader.hasNext()) {
							String ignore = reader.nextString();
							ignoreClient.add(ignore);
						}
						reader.endArray();
						break;
					default:
						reader.skipValue();
					}
				}
				if (category == null || path == null) {
					provider.getLogger().log(Level.WARNING, "Illegal manifest format");
					return false;
				}
				ManifestEntry entry = new ManifestEntry(category, path, ignoreServer, ignoreClient);
				entryMap.put(category, entry);
				writer.endObject();
				reader.endObject();
			}
			writer.endArray();
			reader.endArray();
		}
		this.entryMap = entryMap;
		this.precachedManifestJSON = out.toString();
		return true;
	}

	@Override
	public boolean precacheHashes(Provider provider) {
		Map<String, ManifestPrecached> precachedMap = new HashMap<>();
		Map<String, Path> globalFiles = new HashMap<>();
		for (Map.Entry<String, ManifestEntry> mapEntry : entryMap.entrySet()) {
			String category = mapEntry.getKey();
			ManifestEntry entry = mapEntry.getValue();
			HashMap<String, Path> files = new HashMap<>();
			StringWriter out = new StringWriter();
			try (JsonWriter writer = new JsonWriter(out)) {
				writer.setIndent("    ");
				writer.beginArray();
				Path categoryPath = provider.getClientPath().resolve(entry.getPath());
				if (Files.exists(categoryPath)) {
					for (Path path : (Iterable<? extends Path>) Files.walk(categoryPath)::iterator) {
						if (!Files.isRegularFile(path)) continue;
						String relativePath = categoryPath.relativize(path).toString().replace('\\', '/');
						if (entry.getIgnoreServer().stream().anyMatch(ignore -> PathUtils.matchWildcard(relativePath, ignore.replace('\\', '/')))) continue;
						String hash;
						try (InputStream stream = Files.newInputStream(path)) {
							hash = DigestUtils.md5Hex(stream);
						} catch (IOException e) {
							provider.getLogger().log(Level.WARNING, "Could not calculate file hash", e);
							continue;
						}
						files.put(hash, path);
						globalFiles.put(hash, path);
						writer.beginObject();
						writer.name("path").value(relativePath);
						writer.name("hash").value(hash);
						writer.endObject();
						provider.getLogger().log(Level.INFO, "Found category \"" + category + "\" file \"" + relativePath + "\" with hash \"" + hash + "\"");
					}
				}
				writer.endArray();
			} catch (IOException e) {
				provider.getLogger().log(Level.SEVERE, "Error occurred while precaching file hashes", e);
				return false;
			}
			ManifestPrecached precached = new ManifestPrecached(entry, files, out.toString());
			precachedMap.put(category, precached);
		}
		this.precachedMap = precachedMap;
		this.globalFiles = globalFiles;
		return true;
	}

	@Override
	public void handleManifest(Provider provider, HttpServletRequest req, HttpServletResponse resp) throws IOException {
		if (precachedManifestJSON == null) {
			resp.sendError(HttpServletResponse.SC_NOT_FOUND);
			return;
		}
		provider.getLogger().log(Level.INFO, "Sending manifest to client " + req.getRemoteAddr());
		resp.setContentType("application/json; charset=utf-8");
		try (PrintWriter out = resp.getWriter()) {
			out.write(precachedManifestJSON);
		}
	}

	@Override
	public void handleHashes(Provider provider, HttpServletRequest req, HttpServletResponse resp) throws IOException {
		if (precachedMap == null) {
			resp.sendError(HttpServletResponse.SC_NOT_FOUND);
			return;
		}
		String categoryName = req.getParameter("category");
		if (categoryName == null) {
			resp.sendError(HttpServletResponse.SC_NOT_FOUND);
			return;
		}
		ManifestPrecached category = precachedMap.get(categoryName);
		if (category == null) {
			resp.sendError(HttpServletResponse.SC_NOT_FOUND);
			return;
		}
		provider.getLogger().log(Level.INFO, "Sending category \"" + category.getEntry().getCategory() + "\" hashes to client " + req.getRemoteAddr());
		resp.setContentType("application/json; charset=utf-8");
		try (PrintWriter out = resp.getWriter()) {
			out.write(category.getPrecachedHashes());
		}
	}

	@Override
	public void handleDownload(Provider provider, HttpServletRequest req, HttpServletResponse resp) throws IOException {
		if (globalFiles == null) {
			resp.sendError(HttpServletResponse.SC_NOT_FOUND);
			return;
		}
		String hash = req.getParameter("hash");
		if (hash == null) {
			resp.sendError(HttpServletResponse.SC_NOT_FOUND);
			return;
		}
		Path path = globalFiles.get(hash);
		if (path == null) {
			resp.sendError(HttpServletResponse.SC_NOT_FOUND);
			return;
		}
		provider.getLogger().log(Level.INFO, "Sending category file \"" + path + "\" with hash \"" + hash + "\" to client " + req.getRemoteAddr());
		resp.setContentType("application/octet-stream");
		resp.setHeader("Content-Disposition", "attachment; filename=" + StringEscapeUtils.escapeJava(path.getFileName().toString()));
		try (InputStream in = Files.newInputStream(path); OutputStream out = resp.getOutputStream()) {
			IOUtils.copy(in, out);
		}
	}
}
