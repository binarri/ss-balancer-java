package me.binarii.ss;

import com.sun.net.httpserver.HttpServer;
import me.binarii.ss.core.LatencyEstimator;
import me.binarii.ss.core.ProxyServerSelector;
import me.binarii.ss.model.ProxyServer;
import me.binarii.ss.util.JSON;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static java.nio.charset.StandardCharsets.UTF_8;

public class Boot {

	private static List<LatencyEstimator> latencyEstimators = new ArrayList<>();

	private static List<ProxyServer> proxyServers = new ArrayList<>();

	static {
		proxyServers.add(new ProxyServer("hongk.binarii.me", "127.0.0.1", 40001));
		proxyServers.add(new ProxyServer("tokyo.binarii.me", "127.0.0.1", 40002));
		proxyServers.stream().map(LatencyEstimator::new).forEach(latencyEstimators::add);
	}

	private static ProxyServerSelector selector = new ProxyServerSelector(proxyServers);

	private static AtomicLong seq = new AtomicLong(1_0000_0001);

	public static void main(String[] args) throws Exception {
		latencyEstimators.forEach(LatencyEstimator::start);

		InetSocketAddress address = new InetSocketAddress("127.0.0.1", 50001);
		HttpServer httpServer = HttpServer.create(address, 0);
		httpServer.createContext("/", httpExchange -> {
			List<ProxyServer> selections = selector.selectProxyServers();
			byte[] responseData = JSON.toJSONString(selections).getBytes(UTF_8);

			httpExchange.getResponseHeaders().set("Content-Type", "application/json;charset=utf-8");
			httpExchange.sendResponseHeaders(200, responseData.length);
			httpExchange.getResponseBody().write(responseData);
			httpExchange.close();

			long seqno = seq.getAndIncrement();
			System.out.printf("%s SVR >> %s%n", seqno, proxyServers);
			System.out.printf("%s SLC << %s%n%n", seqno, selections);
		});
		httpServer.start();

		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			latencyEstimators.forEach(LatencyEstimator::shutdown);
			httpServer.stop(Integer.MAX_VALUE);
		}));
	}

}
