package me.binarii.ss.core;

import me.binarii.ss.model.ProxyServer;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.NoHttpResponseException;
import org.apache.http.client.HttpRequestRetryHandler;
import org.apache.http.client.config.CookieSpecs;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpHead;
import org.apache.http.config.Registry;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.ConnectTimeoutException;
import org.apache.http.conn.HttpClientConnectionManager;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.socket.PlainConnectionSocketFactory;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.BasicHttpClientConnectionManager;
import org.apache.http.protocol.HttpContext;
import org.apache.http.ssl.SSLContexts;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

import static java.util.concurrent.TimeUnit.*;

public class LatencyEstimator extends Thread {

    private static Log logger = LogFactory.getLog(LatencyEstimator.class);

    private static final List<String> DEFAULT_TEST_URIS =
            Arrays.asList(
                    "https://www.google.com",
                    "https://www.twitter.com",
                    "https://www.instagram.com"
            );

    private CloseableHttpClient httpClient;

    private ProxyServer proxyServer;

    private List<String> testUris;

    private int rounds;

    private volatile boolean running = true;

    private Random random = new SecureRandom();

    public LatencyEstimator(ProxyServer proxyServer) {
        this(proxyServer, DEFAULT_TEST_URIS);
    }

    public LatencyEstimator(ProxyServer proxyServer, List<String> testUris) {
        this(proxyServer, testUris, 5);
    }

    public LatencyEstimator(ProxyServer proxyServer, List<String> testUris, int rounds) {
        this.proxyServer = proxyServer;
        this.testUris = testUris;
        this.rounds = rounds;
        try {
            this.initHttpClient();
        } catch (Exception e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new RuntimeException(cause);
        }
    }

    @Override
    public void run() {
        while (running) {
            LockSupport.parkNanos(SECONDS.toNanos(random.nextInt(21) + 10));
            double total = 0;
            for (String uri : testUris) {
                total += testOneHost(uri);
            }
            double result = total / testUris.size() * 0.7 + proxyServer.getLatency() * 0.3;
            proxyServer.setLatency(Math.round(result));
        }
    }

    public void shutdown() {
        try {
            this.running = false;
            this.interrupt();
            this.httpClient.close();
        } catch (Exception e) {
            // ignore
        }
    }

    private double testOneHost(String uri) {
        List<Long> samples = new ArrayList<>(rounds);

        long timeTotal = 0;
        for (int i = 0; i < rounds; i++) {
            long t = testOnece(uri);
            samples.add(t);
            timeTotal += t;
            LockSupport.parkNanos(MILLISECONDS.toNanos(random.nextInt(901) + 600));
        }
        double average = timeTotal * 1.0 / rounds;

        long sum = 0;
        for (Long sample : samples) {
            sum += (sample - average) * (sample - average);
        }
        double sdev = Math.sqrt(sum * 1.0 / rounds);

        return average * 0.6 + sdev * 3 * 0.4;
    }

    private long testOnece(String uri) {
        HttpHead request = new HttpHead(uri);
        try {
            long tik = System.nanoTime();
            HttpResponse response = httpClient.execute(request);
            long tok = System.nanoTime();
            long elapsedMillis = NANOSECONDS.toMillis(tok - tik);

            int statusCode = response.getStatusLine().getStatusCode();
            if (statusCode == HttpStatus.SC_OK) {
                return elapsedMillis;
            }
        } catch (Exception e) {
            logger.trace(e.getMessage(), e);
        } finally {
            request.releaseConnection();
        }
        return 10000;
    }

    private void initHttpClient() throws Exception {
        RequestConfig requestConfig = RequestConfig
                .custom()
                .setConnectionRequestTimeout(2000)
                .setConnectTimeout(2000)
                .setSocketTimeout(10000)
                .setCookieSpec(CookieSpecs.IGNORE_COOKIES)
                .build();

        HttpRequestRetryHandler retryHandler = (ex, execCount, ctx) -> {
            if (execCount > 2) return false;
            // @formatter:off
			return ex instanceof ConnectTimeoutException ||
				   ex instanceof SocketTimeoutException ||
			       ex instanceof NoHttpResponseException;
			// @formatter:on
        };

        SSLContext sslContext = SSLContexts
                .custom()
                .loadTrustMaterial((chain, authType) -> true)
                .build();

        Supplier<Socket> socketSupplier = () -> {
            InetSocketAddress socks = new InetSocketAddress(
                    proxyServer.getHost(), proxyServer.getPort());
            Proxy proxy = new Proxy(Proxy.Type.SOCKS, socks);
            return new Socket(proxy);
        };

        Registry<ConnectionSocketFactory> socketFactoryRegistry = RegistryBuilder
                .<ConnectionSocketFactory>create()
                .register("http", new PlainConnectionSocketFactory() {
                    @Override
                    public Socket createSocket(HttpContext context) {
                        return socketSupplier.get();
                    }

                    @Override
                    public Socket connectSocket(int connectTimeout,
                                                Socket socket,
                                                HttpHost host,
                                                InetSocketAddress remoteAddress,
                                                InetSocketAddress localAddress,
                                                HttpContext context) throws IOException {

                        InetSocketAddress unresolved = InetSocketAddress
                                .createUnresolved(host.getHostName(), remoteAddress.getPort());

                        return super.connectSocket(connectTimeout,
                                socket, host, unresolved, localAddress, context);
                    }
                })
                .register("https", new SSLConnectionSocketFactory(sslContext,
                        NoopHostnameVerifier.INSTANCE) {
                    @Override
                    public Socket createSocket(HttpContext context) {
                        return socketSupplier.get();
                    }

                    @Override
                    public Socket connectSocket(int connectTimeout,
                                                Socket socket,
                                                HttpHost host,
                                                InetSocketAddress remoteAddress,
                                                InetSocketAddress localAddress,
                                                HttpContext context) throws IOException {

                        InetSocketAddress unresolved = InetSocketAddress
                                .createUnresolved(host.getHostName(), remoteAddress.getPort());

                        return super.connectSocket(connectTimeout,
                                socket, host, unresolved, localAddress, context);
                    }
                })
                .build();

        HttpClientConnectionManager connManager =
                new BasicHttpClientConnectionManager(socketFactoryRegistry);

        httpClient = HttpClients
                .custom()
                .setConnectionManager(connManager)
                .setDefaultRequestConfig(requestConfig)
                .setRetryHandler(retryHandler)
                .setUserAgent(
                        // @formatter:off
						"Mozilla/5.0 (Macintosh; Intel Mac OS X 11_0_1) " +
						"AppleWebKit/537.36 (KHTML, like Gecko) " +
						"Chrome/87.0.4280.88 Safari/537.36")
						// @formatter:on
                .useSystemProperties()
                .build();
    }

    public ProxyServer getProxyServer() {
        return proxyServer;
    }

    public void setTestUris(List<String> testUris) {
        this.testUris = testUris;
    }

    public void setRounds(int rounds) {
        this.rounds = rounds;
    }

}
