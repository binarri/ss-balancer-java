package me.binarii.ss;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpServer;
import org.icmp4j.IcmpPingResponse;
import org.icmp4j.IcmpPingUtil;

import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import static java.lang.String.format;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.stream.Collectors.toList;

public class Boot {

	private static ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(3);

	private static Map<Svr, Integer> servers = new ConcurrentSkipListMap<>();

	static {
		servers.put(new Svr("tokyo.binarii.me", "127.0.0.1", 40001), 70);
		servers.put(new Svr("hongk.binarii.me", "127.0.0.1", 40002), 80);
		servers.put(new Svr("sgapo.binarii.me", "127.0.0.1", 40003), 90);
	}

	private static final Svr DEFAULT_SVR = new Svr("tokyo.binarii.me", "127.0.0.1", 40001);

	private static TransferQueue<List<Svr>> queue = new LinkedTransferQueue<>();

	private static volatile List<Svr> current = Collections.singletonList(DEFAULT_SVR);

	private static AtomicLong seq = new AtomicLong(1_0000_0001);

	public static void main(String[] args) throws Exception {
		scheduler.scheduleWithFixedDelay(ping(), 0, 15, SECONDS);

		InetSocketAddress address = new InetSocketAddress("127.0.0.1", 50001);
		HttpServer httpServer = HttpServer.create(address, 0);
		httpServer.createContext("/", httpExchange -> {
			List<Svr> hosts = pollNewHostsOrWait();
			byte[] responseData = JSON.toJSONString(hosts).getBytes(UTF_8);

			httpExchange.getResponseHeaders().set("Content-Type", "application/json;charset=utf-8");
			httpExchange.sendResponseHeaders(200, responseData.length);
			httpExchange.getResponseBody().write(responseData);
			httpExchange.close();

			long seqno = seq.getAndIncrement();
			System.out.println(format("%s SVR ==> %s", seqno, servers));
			System.out.println(format("%s SLC <== %s\n", seqno, hosts));
		});
		httpServer.start();
	}

	private static Runnable ping() {
		return () -> servers.keySet().forEach(Boot::ping);
	}

	private static void ping(Svr svr) {
		final int count = 10, packetSize = 32, timeout = 1000;
		int total = 0;
		for (int i = 0; i < count; i++) {
			IcmpPingResponse response = IcmpPingUtil
					.executePingRequest(svr.hostname, packetSize, timeout);

			if (response.getSuccessFlag()) {
				total += response.getDuration();
			} else {
				total += timeout;
				System.out.println(format("error: %s %s", svr,
						response.getErrorMessage()));
			}
		}
		servers.put(svr, /* average = */ total / count);
		compareAndUpdateSelectedHosts();
	}

	private static List<Svr> pollNewHostsOrWait() {
		List<Svr> v = null;
		try {
			v = queue.poll(1500, MILLISECONDS);
			if (v != null) current = v;
			else v = current;
		} catch (InterruptedException e) {
			System.out.println(format("Error: %s", e));
		}
		return v;
	}

	private static void compareAndUpdateSelectedHosts() {
		List<Svr> hosts = servers.entrySet().stream()
				.filter(e -> e.getValue() < 85).map(Entry::getKey)
				.collect(toList());

		if (hosts.isEmpty()) {
			Svr host = servers.entrySet().stream()
					.min(Entry.comparingByValue())
					.map(Entry::getKey).orElse(DEFAULT_SVR);
			hosts = Collections.singletonList(host);
		}

		queue.add(hosts);
	}

	private static class Svr implements Comparable<Svr> {

		String hostname;

		String ip;

		int port;

		public Svr(String hostname, String ip, int port) {
			this.hostname = hostname;
			this.ip = ip;
			this.port = port;
		}

		public String getHostname() {
			return hostname;
		}

		public void setHostname(String hostname) {
			this.hostname = hostname;
		}

		public String getIp() {
			return ip;
		}

		public void setIp(String ip) {
			this.ip = ip;
		}

		public int getPort() {
			return port;
		}

		public void setPort(int port) {
			this.port = port;
		}

		@Override
		public int compareTo(Svr that) {
			if (that == null) return -1;
//			if (this.hostname != null) {
//				return that.hostname != null
//						? this.hostname.compareTo(that.hostname)
//						: -1;
//			} else {
//				return that.hostname == null ? 0 : 1;
//			}
			return this.port - that.port;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj) return true;
			if (obj instanceof Svr) {
				Svr that = (Svr) obj;
				// @formatter:off
				return this.hostname != null &&
					   this.hostname.equals(that.hostname) &&
					   this.ip != null &&
					   this.ip.equals(that.ip) &&
					   this.port == that.port;
				// @formatter:on
			}
			return false;
		}

		@Override
		public int hashCode() {
			int v = 0;
			v = 31 * v + (hostname != null ? hostname.hashCode() : 0);
			v = 31 * v + (ip != null ? ip.hashCode() : 0);
			v = 31 * v + port;
			return v;
		}

		@Override
		public String toString() {
			return "{host=" + hostname + ", ip=" + ip + ", port=" + port + "}";
		}
	}

	private static class JSON {

		static ThreadLocal<Gson> gson = ThreadLocal.withInitial(Gson::new);

		static String toJSONString(Object obj) {
			return gson.get().toJson(obj);
		}

	}

}