package me.binarii.ss.core;

import me.binarii.ss.model.ProxyServer;

import java.util.Collections;
import java.util.List;

import static java.util.stream.Collectors.toList;

public class ProxyServerSelector {

	private static final int DEFAULT_THRESHOLD = 1000;

	private volatile int threshold = DEFAULT_THRESHOLD;

	private List<ProxyServer> proxyServers;

	private List<ProxyServer> defaultProxy;

	public ProxyServerSelector(List<ProxyServer> proxyServers) {
		this.proxyServers = proxyServers;
		this.defaultProxy = Collections.singletonList(proxyServers.get(0));
	}

	public List<ProxyServer> selectProxyServers() {
		List<ProxyServer> hosts = proxyServers.stream()
				.filter(proxy -> proxy.getLatency() < threshold)
				.collect(toList());

		if (hosts.isEmpty()) {
			hosts = proxyServers.stream()
					.min(ProxyServer::compareTo)
					.map(Collections::singletonList)
					.orElse(defaultProxy);
		}

		return hosts;
	}

}