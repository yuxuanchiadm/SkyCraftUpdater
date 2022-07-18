package org.skycraft.updater.core;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Supplier;
import org.skycraft.updater.core.protocol.ProtocolHandler;
import org.skycraft.updater.core.protocol.v1.ProtocolHandlerV1;
import org.skycraft.updater.core.protocol.v2.ProtocolHandlerV2;

public enum Protocol {
	V1(ProtocolHandlerV1::new), V2(ProtocolHandlerV2::new);

	public static final Protocol CURRENT_PROTOCOL = V2;

	private static final Map<Protocol, ProtocolHandler> HANDLERS = new EnumMap<>(Protocol.class);
	static {
		for (Protocol protocol : values()) {
			HANDLERS.put(protocol, protocol.handlerConstructor.get());
		}
	}

	private final Supplier<ProtocolHandler> handlerConstructor;

	Protocol(Supplier<ProtocolHandler> handlerConstructor) {
		this.handlerConstructor = handlerConstructor;
	}

	public ProtocolHandler getHandler() {
		return HANDLERS.get(this);
	}
}
