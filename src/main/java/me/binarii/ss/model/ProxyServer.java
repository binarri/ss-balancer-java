package me.binarii.ss.model;

public class ProxyServer implements Comparable<ProxyServer> {

	public static final Object LOCK = new Object();

	private String name;

	private String host;

	private int port;

	private volatile long latency;

	public ProxyServer(String name, String host, int port) {
		this.name = name;
		this.host = host;
		this.port = port;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getHost() {
		return host;
	}

	public void setHost(String host) {
		this.host = host;
	}

	public int getPort() {
		return port;
	}

	public void setPort(int port) {
		this.port = port;
	}

	public long getLatency() {
		return latency;
	}

	public void setLatency(long latency) {
		synchronized (LOCK) {
			this.latency = latency;
		}
	}

	@Override
	public int compareTo(ProxyServer that) {
		return that != null ? (int) (this.latency - that.latency) : -1;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj) return true;
		if (obj instanceof ProxyServer) {
			ProxyServer that = (ProxyServer) obj;
			// @formatter:off
			return this.host != null &&
				   this.host.equals(that.host) &&
				   this.port == that.port;
			// @formatter:on
		}
		return false;
	}

	@Override
	public int hashCode() {
		int v = 1;
		v = 31 * v + (host != null ? host.hashCode() : 0);
		v = 31 * v + port;
		return v;
	}

	@Override
	public String toString() {
		return "(" + name + ", " + host + ":" + port + ", " + latency + ")";
	}

}
