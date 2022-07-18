package org.skycraft.updater.core.data;

import java.nio.file.Path;
import java.util.Map;

public final class ManifestPrecached {
	private final ManifestEntry entry;
	private final Map<String, Path> files;
	private final String precachedHashes;

	public ManifestPrecached(ManifestEntry entry, Map<String, Path> files, String precachedHashes) {
		this.entry = entry;
		this.files = files;
		this.precachedHashes = precachedHashes;
	}

	public ManifestEntry getEntry() {
		return entry;
	}

	public Map<String, Path> getFiles() {
		return files;
	}

	public String getPrecachedHashes() {
		return precachedHashes;
	}
}
