/*
 * Copyright 2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.cloud.ai.lynxe.config;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.env.Environment;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import io.netty.resolver.DefaultAddressResolverGroup;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.transport.ProxyProvider;

/**
 * DNS cache and network configuration to resolve DNS resolution timeout issues in VPN
 * environments
 */
@Configuration
public class DnsCacheConfig {

	private static final Logger log = LoggerFactory.getLogger(DnsCacheConfig.class);

	@Lazy
	@Autowired(required = false)
	private LynxeProperties lynxeProperties;

	@Autowired(required = false)
	private Environment environment;

	@Value("${lynxe.proxy.enabled:false}")
	private boolean proxyEnabled;

	@Value("${lynxe.proxy.httpProxyHost:}")
	private String httpProxyHost;

	@Value("${lynxe.proxy.httpProxyPort:}")
	private String httpProxyPort;

	@Value("${lynxe.proxy.httpsProxyHost:}")
	private String httpsProxyHost;

	@Value("${lynxe.proxy.httpsProxyPort:}")
	private String httpsProxyPort;

	@Value("${lynxe.proxy.proxyUsername:}")
	private String proxyUsername;

	@Value("${lynxe.proxy.proxyPassword:}")
	private String proxyPassword;

	/**
	 * Get configured LLM read timeout from LynxeProperties, defaulting to 120 seconds if
	 * not configured
	 */
	private int getLlmReadTimeoutSeconds() {
		if (lynxeProperties != null && lynxeProperties.getLlmReadTimeout() != null) {
			return lynxeProperties.getLlmReadTimeout();
		}
		return 120; // Default 120 seconds (2 minutes)
	}

	/**
	 * Configure WebClient with DNS cache
	 */
	@Bean
	public WebClient webClientWithDnsCache() {
		int readTimeoutSeconds = getLlmReadTimeoutSeconds();
		log.info("Configuring WebClient with DNS cache and extended timeouts (read timeout: {} seconds)",
				readTimeoutSeconds);

		// Create connection provider with increased connection pool size and timeout
		ConnectionProvider connectionProvider = ConnectionProvider.builder("dns-cache-pool")
			.maxConnections(100)
			.maxIdleTime(Duration.ofMinutes(5))
			.maxLifeTime(Duration.ofMinutes(10))
			.pendingAcquireTimeout(Duration.ofSeconds(30))
			.evictInBackground(Duration.ofSeconds(120))
			.build();

		// Configure HttpClient with DNS cache and timeout settings
		HttpClient httpClient = HttpClient.create(connectionProvider)
			// Use default address resolver group (includes DNS cache)
			.resolver(DefaultAddressResolverGroup.INSTANCE)
			// Set connection timeout
			.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 30000) // 30 seconds
			// Set read and write timeout from configuration
			.doOnConnected(conn -> conn.addHandlerLast(new ReadTimeoutHandler(readTimeoutSeconds, TimeUnit.SECONDS))
				.addHandlerLast(new WriteTimeoutHandler(readTimeoutSeconds, TimeUnit.SECONDS)))
			// Enable TCP keep-alive
			.option(ChannelOption.SO_KEEPALIVE, true)
			// Set TCP_NODELAY
			.option(ChannelOption.TCP_NODELAY, true);

		// Configure proxy if available
		httpClient = configureProxy(httpClient);

		return WebClient.builder()
			.clientConnector(new ReactorClientHttpConnector(httpClient))
			.codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(10 * 1024 * 1024)) // 10MB
			.build();
	}

	/**
	 * Configure proxy settings for HttpClient
	 * @param httpClient The HttpClient to configure
	 * @return HttpClient with proxy configuration if available
	 */
	private HttpClient configureProxy(HttpClient httpClient) {
		// Check if proxy is enabled
		if (!proxyEnabled) {
			log.debug("Proxy is disabled, skipping proxy configuration");
			return httpClient;
		}

		// Get proxy configuration from yml or environment variables
		String httpHost = getProxyConfig("lynxe.proxy.httpProxyHost", httpProxyHost, "HTTP_PROXY", true);
		String httpPort = getProxyConfig("lynxe.proxy.httpProxyPort", httpProxyPort, "HTTP_PROXY", false);
		String httpsHost = getProxyConfig("lynxe.proxy.httpsProxyHost", httpsProxyHost, "HTTPS_PROXY", true);
		String httpsPort = getProxyConfig("lynxe.proxy.httpsProxyPort", httpsProxyPort, "HTTPS_PROXY", false);
		String username = getProxyConfig("lynxe.proxy.proxyUsername", proxyUsername, null, true);
		String password = getProxyConfig("lynxe.proxy.proxyPassword", proxyPassword, null, true);

		// Use HTTPS proxy if available, otherwise use HTTP proxy
		String proxyHost = (httpsHost != null && !httpsHost.isEmpty()) ? httpsHost : httpHost;
		String proxyPort = (httpsHost != null && !httpsHost.isEmpty()) ? httpsPort : httpPort;

		if (proxyHost != null && !proxyHost.isEmpty() && proxyPort != null && !proxyPort.isEmpty()) {
			try {
				int port = Integer.parseInt(proxyPort);
				log.info("Configuring proxy: {}:{}", proxyHost, port);

				final String finalProxyHost = proxyHost;
				final int finalPort = port;
				final String finalUsername = username;
				final String finalPassword = password;

				InetSocketAddress proxyAddress = new InetSocketAddress(finalProxyHost, finalPort);

				// Configure proxy using Reactor Netty's proxy API
				if (finalUsername != null && !finalUsername.isEmpty() && finalPassword != null
						&& !finalPassword.isEmpty()) {
					// Proxy with authentication
					httpClient = httpClient.proxy(proxy -> proxy.type(ProxyProvider.Proxy.HTTP)
						.address(proxyAddress)
						.username(finalUsername)
						.password(pwd -> finalPassword));
				}
				else {
					// Proxy without authentication
					httpClient = httpClient.proxy(proxy -> proxy.type(ProxyProvider.Proxy.HTTP).address(proxyAddress));
				}

				if (finalUsername != null && !finalUsername.isEmpty()) {
					log.info("Proxy authentication configured for user: {}", finalUsername);
				}
				log.info("Proxy configuration applied successfully");
			}
			catch (NumberFormatException e) {
				log.warn("Invalid proxy port: {}, proxy configuration will be skipped", proxyPort);
			}
			catch (Exception e) {
				log.warn("Failed to configure proxy: {}", e.getMessage());
			}
		}
		else {
			log.debug("No proxy configuration found, using direct connection");
		}

		return httpClient;
	}

	/**
	 * Get proxy configuration from yml, system property, or environment variable
	 * @param ymlKey YAML configuration key
	 * @param ymlValue Value from @Value annotation
	 * @param envVar Environment variable name (e.g., "HTTP_PROXY")
	 * @param isHost Whether this is a host (true) or port (false) value
	 * @return Configuration value
	 */
	private String getProxyConfig(String ymlKey, String ymlValue, String envVar, boolean isHost) {
		// First check yml configuration
		if (ymlValue != null && !ymlValue.trim().isEmpty()) {
			return ymlValue.trim();
		}

		// Then check environment (Spring Environment)
		if (environment != null) {
			String envValue = environment.getProperty(ymlKey);
			if (envValue != null && !envValue.trim().isEmpty()) {
				return envValue.trim();
			}
		}

		// Finally check system environment variables
		if (envVar != null) {
			String envProxy = System.getenv(envVar);
			if (envProxy != null && !envProxy.trim().isEmpty()) {
				return isHost ? parseProxyHost(envProxy) : parseProxyPort(envProxy);
			}
		}

		return null;
	}

	/**
	 * Parse proxy host from proxy URL (e.g., "http://proxy.example.com:8080" ->
	 * "proxy.example.com")
	 */
	private String parseProxyHost(String proxyUrl) {
		if (proxyUrl == null || proxyUrl.trim().isEmpty()) {
			return null;
		}
		try {
			// Remove protocol prefix if present
			String url = proxyUrl.trim();
			if (url.startsWith("http://")) {
				url = url.substring(7);
			}
			else if (url.startsWith("https://")) {
				url = url.substring(8);
			}
			// Extract host (before colon if port is present)
			int colonIndex = url.indexOf(':');
			if (colonIndex > 0) {
				return url.substring(0, colonIndex);
			}
			return url;
		}
		catch (Exception e) {
			return null;
		}
	}

	/**
	 * Parse proxy port from proxy URL (e.g., "http://proxy.example.com:8080" -> "8080")
	 */
	private String parseProxyPort(String proxyUrl) {
		if (proxyUrl == null || proxyUrl.trim().isEmpty()) {
			return null;
		}
		try {
			// Remove protocol prefix if present
			String url = proxyUrl.trim();
			if (url.startsWith("http://")) {
				url = url.substring(7);
			}
			else if (url.startsWith("https://")) {
				url = url.substring(8);
			}
			// Extract port (after colon)
			int colonIndex = url.indexOf(':');
			if (colonIndex > 0 && colonIndex < url.length() - 1) {
				return url.substring(colonIndex + 1);
			}
			// Default ports
			if (proxyUrl.startsWith("https://")) {
				return "443";
			}
			return "80";
		}
		catch (Exception e) {
			return null;
		}
	}

	/**
	 * Configure system properties to enable DNS cache
	 */
	@Bean
	public DnsCacheInitializer dnsCacheInitializer() {
		return new DnsCacheInitializer();
	}

	/**
	 * DNS cache initializer
	 */
	public static class DnsCacheInitializer {

		static {
			log.info("Initializing DNS cache settings");

			// Enable DNS cache
			System.setProperty("java.net.useSystemProxies", "true");
			System.setProperty("networkaddress.cache.ttl", "300"); // 5 minutes cache
			System.setProperty("networkaddress.cache.negative.ttl", "60"); // 1 minute
																			// negative
																			// cache

			// Netty DNS settings
			System.setProperty("io.netty.resolver.dns.cache.ttl", "300"); // 5 minutes
			System.setProperty("io.netty.resolver.dns.cache.negative.ttl", "60"); // 1
																					// minute
			System.setProperty("io.netty.resolver.dns.queryTimeoutMillis", "10000"); // 10
																						// seconds
																						// timeout

			// Enable Netty DNS cache
			System.setProperty("io.netty.resolver.dns.cache.enabled", "true");
			System.setProperty("io.netty.resolver.dns.cache.maxTtl", "300");
			System.setProperty("io.netty.resolver.dns.cache.minTtl", "60");

			log.info("DNS cache settings initialized successfully");
		}

	}

}
