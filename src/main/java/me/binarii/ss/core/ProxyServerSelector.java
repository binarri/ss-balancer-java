package me.binarii.ss.core;

import me.binarii.ss.model.ProxyServer;

import java.util.List;

import static java.util.stream.Collectors.toList;

public class ProxyServerSelector {

    private final List<ProxyServer> proxyServers;

    public ProxyServerSelector(List<ProxyServer> proxyServers) {
        this.proxyServers = proxyServers;
    }

    public List<ProxyServer> selectProxyServers() {
        synchronized (ProxyServer.LOCK) {
            Long minValue = proxyServers.stream()
                    .min(ProxyServer::compareTo)
                    .map(ProxyServer::getLatency)
                    .orElse(0L);

            return proxyServers.stream()
                    .filter(proxy -> proxy.getLatency() - minValue <= 200)
                    .collect(toList());
        }
    }

}
