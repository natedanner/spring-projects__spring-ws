/*
 * Copyright 2005-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.ws.transport.http;

import java.io.IOException;
import java.net.URI;
import java.util.Map;

import org.apache.hc.client5.http.HttpRoute;
import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.Credentials;
import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.impl.auth.CredentialsProviderBuilder;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.EntityDetails;
import org.apache.hc.core5.http.HttpEntityContainer;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.HttpRequestInterceptor;
import org.apache.hc.core5.http.URIScheme;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.util.Assert;
import org.springframework.ws.transport.WebServiceConnection;

/**
 * {@code WebServiceMessageSender} implementation that uses Apache HttpClient 5 to execute POST requests.
 * <p>
 * Allows to use a pre-configured HttpClient instance, potentially with authentication, HTTP connection pooling, etc.
 * Authentication can also be set by injecting a {@link Credentials} instance (such as the
 * {@link org.apache.hc.client5.http.auth.UsernamePasswordCredentials}).
 *
 * @author Greg Turnquist
 * @see HttpClient
 * @see HttpComponents5Connection
 * @since 4.0
 */
public class HttpComponents5MessageSender extends AbstractHttpWebServiceMessageSender
		implements InitializingBean, DisposableBean {

	private static final int DEFAULT_CONNECTION_TIMEOUT_MILLISECONDS = (60 * 1000);

	private static final int DEFAULT_READ_TIMEOUT_MILLISECONDS = (60 * 1000);

	private HttpClientBuilder httpClientBuilder;
	private ConnectionConfig.Builder connectionConfigBuilder;
	private PoolingHttpClientConnectionManagerBuilder poolingHttpClientConnectionManagerBuilder;

	private HttpClient httpClient;

	private Credentials credentials;

	private AuthScope authScope = new AuthScope(null, null, -1, null, null);
	private Map<String, String> maxConnectionsPerHost;

	/**
	 * Create a new instance of the {@code HttpClientMessageSender} with a default {@link HttpClient} that uses a default
	 * {@link PoolingHttpClientConnectionManager}.
	 */
	public HttpComponents5MessageSender() {

		this.httpClientBuilder = HttpClientBuilder.create() //
				.addRequestInterceptorFirst(new HttpComponents5MessageSender.RemoveSoapHeadersInterceptor());
		this.connectionConfigBuilder = ConnectionConfig.custom();
		this.poolingHttpClientConnectionManagerBuilder = PoolingHttpClientConnectionManagerBuilder.create();

		setConnectionTimeout(DEFAULT_CONNECTION_TIMEOUT_MILLISECONDS);
		setReadTimeout(DEFAULT_READ_TIMEOUT_MILLISECONDS);
	}

	/**
	 * Create a new instance of the {@code HttpClientMessageSender} with the given {@link HttpClient} instance.
	 * <p>
	 * This constructor does not change the given {@code HttpClient} in any way. As such, it does not set timeouts, nor
	 * does it {@linkplain HttpClientBuilder#addRequestInterceptorFirst(HttpRequestInterceptor) add} the
	 * {@link HttpComponents5MessageSender.RemoveSoapHeadersInterceptor}.
	 *
	 * @param httpClient the HttpClient instance to use for this sender
	 */
	public HttpComponents5MessageSender(HttpClient httpClient) {

		Assert.notNull(httpClient, "httpClient must not be null");
		this.httpClient = httpClient;
	}

	/**
	 * Sets the credentials to be used. If not set, no authentication is done.
	 *
	 * @see org.apache.hc.client5.http.auth.UsernamePasswordCredentials
	 * @see org.apache.hc.client5.http.auth.NTCredentials
	 */
	public void setCredentials(Credentials credentials) {
		this.credentials = credentials;
	}

	/**
	 * Returns the {@code HttpClient} used by this message sender.
	 */
	public HttpClient getHttpClient() {
		return httpClient;
	}

	/**
	 * Set the {@code HttpClient} used by this message sender.
	 */
	public void setHttpClient(HttpClient httpClient) {
		this.httpClient = httpClient;
	}

	/**
	 * Sets the timeout until a connection is established. A value of 0 means <em>never</em> timeout.
	 *
	 * @param timeout the timeout value in milliseconds
	 * @see ConnectionConfig.Builder#setConnectTimeout(Timeout)
	 */
	public void setConnectionTimeout(int timeout) {

		if (timeout < 0) {
			throw new IllegalArgumentException("timeout must be a non-negative value");
		}
		this.connectionConfigBuilder.setConnectTimeout(Timeout.ofMilliseconds(timeout));
	}

	/**
	 * Set the socket read timeout for the underlying HttpClient. A value of 0 means <em>never</em> timeout.
	 *
	 * @param timeout the timeout value in milliseconds
	 * @see ConnectionConfig.Builder#setSocketTimeout(Timeout)
	 */
	public void setReadTimeout(int timeout) {

		if (timeout < 0) {
			throw new IllegalArgumentException("timeout must be a non-negative value");
		}
		this.connectionConfigBuilder.setSocketTimeout(Timeout.ofMilliseconds(timeout));
	}

	/**
	 * Sets the maximum number of connections allowed for the underlying HttpClient.
	 *
	 * @param maxTotalConnections the maximum number of connections allowed
	 * @see PoolingHttpClientConnectionManagerBuilder#setMaxConnTotal(int)
	 */
	public void setMaxTotalConnections(int maxTotalConnections) {

		if (maxTotalConnections <= 0) {
			throw new IllegalArgumentException("maxTotalConnections must be a positive value");
		}
		this.poolingHttpClientConnectionManagerBuilder.setMaxConnTotal(maxTotalConnections);
	}

	/**
	 * Sets the maximum number of connections per host for the underlying HttpClient. The maximum number of connections
	 * per host can be set in a form accepted by the {@code java.util.Properties} class, like as follows:
	 *
	 * <pre>
	 * https://www.example.com=1
	 * http://www.example.com:8080=7
	 * http://www.springframework.org=10
	 * </pre>
	 * <p>
	 * The host can be specified as a URI (with scheme and port).
	 *
	 * @param maxConnectionsPerHost a properties object specifying the maximum number of connection
	 * @see PoolingHttpClientConnectionManager#setMaxPerRoute(HttpRoute, int)
	 */
	public void setMaxConnectionsPerHost(Map<String, String> maxConnectionsPerHost) {
		this.maxConnectionsPerHost = maxConnectionsPerHost;
	}

	/**
	 * Sets the authentication scope to be used. Only used when the {@code credentials} property has been set.
	 *
	 * @see #setCredentials(Credentials)
	 */
	public void setAuthScope(AuthScope authScope) {
		this.authScope = authScope;
	}

	@Override
	public void afterPropertiesSet() throws Exception {

		if (credentials != null) {
			this.httpClientBuilder.setDefaultCredentialsProvider(CredentialsProviderBuilder.create() //
					.add(authScope, credentials) //
					.build());
		}

		PoolingHttpClientConnectionManager poolingHttpClientConnectionManager = this.poolingHttpClientConnectionManagerBuilder
				.build();

		if (this.maxConnectionsPerHost != null) {
			this.maxConnectionsPerHost.forEach((String key, String value) -> {

				URI uri = URI.create(key);
				HttpHost host = new HttpHost(uri.getScheme(), uri.getHost(), uri.getPort());
				final HttpRoute route;

				if (URIScheme.HTTPS.same(uri.getScheme())) {
					route = new HttpRoute(host, null, true);
				} else {
					route = new HttpRoute(host);
				}

				int max = Integer.parseInt(value);

				poolingHttpClientConnectionManager.setMaxPerRoute(route, max);
			});
		}

		this.httpClient = this.httpClientBuilder //
				.setConnectionManager(poolingHttpClientConnectionManager) //
				.build();
	}

	@Override
	public WebServiceConnection createConnection(URI uri) throws IOException {

		HttpPost httpPost = new HttpPost(uri);
		if (isAcceptGzipEncoding()) {
			httpPost.addHeader(HttpTransportConstants.HEADER_ACCEPT_ENCODING, HttpTransportConstants.CONTENT_ENCODING_GZIP);
		}
		HttpContext httpContext = createContext(uri);
		return new HttpComponents5Connection(getHttpClient(), httpPost, httpContext);
	}

	/**
	 * Template method that allows for creation of a {@link HttpContext} for the given uri. Default implementation returns
	 * {@code null}.
	 *
	 * @param uri the URI to create the context for
	 * @return the context, or {@code null}
	 */
	protected HttpContext createContext(URI uri) {
		return null;
	}

	@Override
	public void destroy() throws Exception {

		if (getHttpClient()instanceof CloseableHttpClient closeableHttpClient) {
			closeableHttpClient.close();
		}
	}

	/**
	 * HttpClient {@link org.apache.http.HttpRequestInterceptor} implementation that removes {@code Content-Length} and
	 * {@code Transfer-Encoding} headers from the request. Necessary, because some SAAJ and other SOAP implementations set
	 * these headers themselves, and HttpClient throws an exception if they have been set.
	 */
	public static class RemoveSoapHeadersInterceptor implements HttpRequestInterceptor {

		@Override
		public void process(HttpRequest request, EntityDetails entity, HttpContext context)
				throws HttpException, IOException {

			if (request instanceof HttpEntityContainer) {
				if (request.containsHeader(HttpHeaders.TRANSFER_ENCODING)) {
					request.removeHeaders(HttpHeaders.TRANSFER_ENCODING);
				}
				if (request.containsHeader(HttpHeaders.CONTENT_LENGTH)) {
					request.removeHeaders(HttpHeaders.CONTENT_LENGTH);
				}
			}
		}
	}
}
