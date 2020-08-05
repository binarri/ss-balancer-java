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

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.stream.Collectors.toList;

public class Boot {

	private static ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);

	private static Map<Svr, Integer> servers = new ConcurrentSkipListMap<>();

	static {
		servers.put(new Svr("tokyo.binarii.me", "127.0.0.1", 40001), 70);
		servers.put(new Svr("hongk.binarii.me", "127.0.0.1", 40002), 80);
		servers.put(new Svr("tiwan.binarii.me", "127.0.0.1", 40003), 90);
	}

	private static final Svr DEFAULT_SVR = new Svr("tokyo.binarii.me", "127.0.0.1", 40001);

	private static TransferQueue<List<Svr>> queue = new LinkedTransferQueue<>();

	private static volatile List<Svr> current = Collections.singletonList(DEFAULT_SVR);

	private static AtomicLong seq = new AtomicLong(1_0000_0001);

	public static void main(String[] args) throws Exception {
		scheduler.scheduleWithFixedDelay(ping(), 0, 11, SECONDS);

		InetSocketAddress address = new InetSocketAddress("127.0.0.1", 50001);
		HttpServer httpServer = HttpServer.create(address, 0);
		httpServer.createContext("/", httpExchange -> {
			List<Svr> hosts = pollSelectedHostsOrWait();
			byte[] responseData = JSON.toJSONString(hosts).getBytes(UTF_8);

			httpExchange.getResponseHeaders().set("Content-Type", "application/json;charset=utf-8");
			httpExchange.sendResponseHeaders(200, responseData.length);
			httpExchange.getResponseBody().write(responseData);
			httpExchange.close();

			long seqno = seq.getAndIncrement();
			System.out.printf("%s SVR >> %s%n", seqno, servers);
			System.out.printf("%s SLC << %s%n%n", seqno, hosts);
		});
		httpServer.start();
	}

	private static Runnable ping() {
		return () -> servers.keySet().forEach(Boot::ping);
	}

	private static void ping(Svr svr) {
		final int count = 10, packetSize = 16, timeout = 1000;
		int total = 0;
		for (int i = 0; i < count; i++) {
			IcmpPingResponse response = IcmpPingUtil
					.executePingRequest(svr.hostname, packetSize, timeout);

			if (response.getSuccessFlag()) {
				total += response.getDuration();
			} else {
				total += timeout;
				System.out.printf("error: %s %s%n",
						svr, response.getErrorMessage());
			}
		}
		servers.put(svr, /* average = */ total / count);
		selectHostsThenTransfer();
	}

	private static List<Svr> pollSelectedHostsOrWait() {
		List<Svr> v = null;
		try {
			v = queue.poll(1500, MILLISECONDS);
			if (v != null) current = v;
			else v = current;
		} catch (InterruptedException e) {
			System.out.printf("error: %s%n", e);
		}
		return v;
	}

	private static void selectHostsThenTransfer() {
		for (int x = 85; x <= 125; x += 10) {
			List<Svr> hosts = selectHosts(x);
			if (hosts.size() > 0) { queue.add(hosts); return; }
		}

		Svr host = servers.entrySet().stream()
				.min(Entry.comparingByValue())
				.map(Entry::getKey).orElse(DEFAULT_SVR);

		queue.add(Collections.singletonList(host));
	}

	private static List<Svr> selectHosts(int threshold) {
		return servers.entrySet()
				.stream()
				.filter(e -> e.getValue() < threshold)
				.map(Entry::getKey)
				.collect(toList());
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
