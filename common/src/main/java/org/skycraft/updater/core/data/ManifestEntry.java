package org.skycraft.updater.core.data;

import java.nio.file.Path;
import java.util.List;

public final class ManifestEntry {
	private final String category;
	private final Path path;
	private final List<String> ignoreServer;
	private final List<String> ignoreClient;

	public ManifestEntry(String category, Path path, List<String> ignoreServer, List<String> ignoreClient) {
		this.category = category;
		this.path = path;
		this.ignoreServer = ignoreServer;
		this.ignoreClient = ignoreClient;
	}

	public String getCategory() {
		return category;
	}

	public Path getPath() {
		return path;
	}

	public List<String> getIgnoreServer() {
		return ignoreServer;
	}

	public List<String> getIgnoreClient() {
		return ignoreClient;
	}
}
