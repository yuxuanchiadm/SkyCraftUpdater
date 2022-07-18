package org.skycraft.updater.core.protocol;

import com.google.gson.stream.JsonReader;
import java.io.IOException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.skycraft.updater.core.Protocol;
import org.skycraft.updater.core.Provider;

public abstract class ProtocolHandler {
	private final Protocol protocol;
	private final String protocolURLPath;

	protected ProtocolHandler(Protocol protocol) {
		this(protocol, null);
	}

	protected ProtocolHandler(Protocol protocol, String protocolURLPath) {
		this.protocol = protocol;
		this.protocolURLPath = protocolURLPath;
	}

	public final Protocol getProtocol() {
		return protocol;
	}

	public final String getProtocolURL(String url) {
		if (protocolURLPath == null) {
			return "/" + url;
		} else {
			return "/" + protocolURLPath + "/" + url;
		}
	}

	public abstract boolean precacheManifest(Provider provider, JsonReader reader) throws IOException, IllegalStateException;

	public abstract boolean precacheHashes(Provider provider);

	public abstract void handleManifest(Provider provider, HttpServletRequest req, HttpServletResponse resp) throws IOException;

	public abstract void handleHashes(Provider provider, HttpServletRequest req, HttpServletResponse resp) throws IOException;

	public abstract void handleDownload(Provider provider, HttpServletRequest req, HttpServletResponse resp) throws IOException;
}
