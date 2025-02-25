package org.whispersystems.signalservice.internal.websocket;

import org.signal.libsignal.protocol.logging.Log;
import org.signal.libsignal.protocol.util.Pair;
import org.whispersystems.signalservice.api.push.SignalServiceAddress;
import org.whispersystems.signalservice.api.push.TrustStore;
import org.whispersystems.signalservice.api.util.CredentialsProvider;
import org.whispersystems.signalservice.api.util.Tls12SocketFactory;
import org.whispersystems.signalservice.api.websocket.HealthMonitor;
import org.whispersystems.signalservice.api.websocket.WebSocketConnectionState;
import org.whispersystems.signalservice.internal.configuration.SignalServiceConfiguration;
import org.whispersystems.signalservice.internal.configuration.SignalServiceUrl;
import org.whispersystems.signalservice.internal.util.BlacklistingTrustManager;
import org.whispersystems.signalservice.internal.util.Util;

import java.io.IOException;
import java.net.ProxySelector;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.net.SocketFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;
import io.reactivex.rxjava3.subjects.BehaviorSubject;
import io.reactivex.rxjava3.subjects.SingleSubject;
import okhttp3.ConnectionSpec;
import okhttp3.Dns;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

public class WebSocketConnection extends WebSocketListener {

  private static final String TAG                         = WebSocketConnection.class.getSimpleName();
  public  static final int    KEEPALIVE_FREQUENCY_SECONDS = 30;

  private final LinkedList<WebSocketRequestMessage> incomingRequests = new LinkedList<>();
  private final Map<Long, OutgoingRequest>          outgoingRequests = new HashMap<>();
  private final Set<Long>                           keepAlives       = new HashSet<>();

  private final String                                    name;
  private final String                                    wsUri;
  private final TrustStore                                trustStore;
  private final Optional<CredentialsProvider>             credentialsProvider;
  private final String                                    signalAgent;
  private final HealthMonitor                             healthMonitor;
  private final List<Interceptor>                         interceptors;
  private final Dns                                       dns;
  private final SocketFactory                             socketFactory;
  private final ProxySelector                             proxySelector;
  private final BehaviorSubject<WebSocketConnectionState> webSocketState;
  private final boolean                                   allowStories;
  private final SignalServiceUrl                          serviceUrl;

  private OkHttpClient okHttpClient;
  private WebSocket client;

  public WebSocketConnection(String name,
                             SignalServiceConfiguration serviceConfiguration,
                             Optional<CredentialsProvider> credentialsProvider,
                             String signalAgent,
                             HealthMonitor healthMonitor,
                             boolean allowStories) {
    this(name, serviceConfiguration, credentialsProvider, signalAgent, healthMonitor, "", allowStories);
  }

  public WebSocketConnection(String name,
                             SignalServiceConfiguration serviceConfiguration,
                             Optional<CredentialsProvider> credentialsProvider,
                             String signalAgent,
                             HealthMonitor healthMonitor,
                             String extraPathUri,
                             boolean allowStories)
  {
    this.name                = "[" + name + ":" + System.identityHashCode(this) + "]";
    this.trustStore          = serviceConfiguration.getSignalServiceUrls()[0].getTrustStore();
    this.credentialsProvider = credentialsProvider;
    this.signalAgent         = signalAgent;
    this.interceptors        = serviceConfiguration.getNetworkInterceptors();
    this.dns                 = serviceConfiguration.getDns();
    this.socketFactory       = serviceConfiguration.getSocketFactory();
    this.proxySelector       = serviceConfiguration.getProxySelector();
    this.healthMonitor       = healthMonitor;
    this.webSocketState      = BehaviorSubject.createDefault(WebSocketConnectionState.DISCONNECTED);
    this.allowStories        = allowStories;
    this.serviceUrl          = serviceConfiguration.getSignalServiceUrls()[0];

    String uri = serviceUrl.getUrl().replace("https://", "wss://").replace("http://", "ws://");

    if (credentialsProvider.isPresent()) {
      this.wsUri = uri + "/v1/websocket/" + extraPathUri + "?login=%s&password=%s";
    } else {
      this.wsUri = uri + "/v1/websocket/" + extraPathUri;
    }
  }

  public String getName() {
    return name;
  }

  public synchronized Observable<WebSocketConnectionState> connect() {
    log("connect()");

    if (client == null) {
      String filledUri;

      if (credentialsProvider.isPresent()) {
        String identifier = Objects.requireNonNull(credentialsProvider.get().getAci()).toString();
        if (credentialsProvider.get().getDeviceId() != SignalServiceAddress.DEFAULT_DEVICE_ID) {
          identifier += "." + credentialsProvider.get().getDeviceId();
        }
        filledUri = String.format(wsUri, identifier, credentialsProvider.get().getPassword());
      } else {
        filledUri = wsUri;
      }

      Pair<SSLSocketFactory, X509TrustManager> sslSocketFactory = createTlsSocketFactory(trustStore);

      OkHttpClient.Builder clientBuilder = new OkHttpClient.Builder().socketFactory(socketFactory)
                                                                     .proxySelector(proxySelector)
                                                                     .dns(dns)
                                                                     .sslSocketFactory(new Tls12SocketFactory(sslSocketFactory.first()),
                                                                                       sslSocketFactory.second())
                                                                     .connectionSpecs(serviceUrl.getConnectionSpecs().orElse(Util.immutableList(ConnectionSpec.RESTRICTED_TLS)))
                                                                     .readTimeout(KEEPALIVE_FREQUENCY_SECONDS + 10, TimeUnit.SECONDS)
                                                                     .connectTimeout(KEEPALIVE_FREQUENCY_SECONDS + 10, TimeUnit.SECONDS);

      for (Interceptor interceptor : interceptors) {
        clientBuilder.addInterceptor(interceptor);
      }

      okHttpClient = clientBuilder.build();

      Request.Builder requestBuilder = new Request.Builder().url(filledUri);

      if (signalAgent != null) {
        requestBuilder.addHeader("X-Signal-Agent", signalAgent);
      }

      requestBuilder.addHeader("X-Signal-Receive-Stories", allowStories ? "true" : "false");

      if (serviceUrl.getHostHeader().isPresent()) {
        requestBuilder.addHeader("Host", serviceUrl.getHostHeader().get());
        Log.w(TAG, "Using alternate host: " + serviceUrl.getHostHeader().get());
      }

      webSocketState.onNext(WebSocketConnectionState.CONNECTING);

      this.client = okHttpClient.newWebSocket(requestBuilder.build(), this);
    }
    return webSocketState;
  }

  public synchronized boolean isDead() {
    return client == null;
  }

  public synchronized void disconnect() {
    log("disconnect()");

    if (client != null) {
      client.close(1000, "OK");
      client = null;
      webSocketState.onNext(WebSocketConnectionState.DISCONNECTING);
    }

    notifyAll();
  }

  public synchronized Optional<WebSocketRequestMessage> readRequestIfAvailable() {
    if (incomingRequests.size() > 0) {
      return Optional.of(incomingRequests.removeFirst());
    } else {
      return Optional.empty();
    }
  }

  public synchronized WebSocketRequestMessage readRequest(long timeoutMillis)
      throws TimeoutException, IOException
  {
    if (client == null) {
      throw new IOException("Connection closed!");
    }

    long startTime = System.currentTimeMillis();

    while (client != null && incomingRequests.isEmpty() && elapsedTime(startTime) < timeoutMillis) {
      Util.wait(this, Math.max(1, timeoutMillis - elapsedTime(startTime)));
    }

    if (incomingRequests.isEmpty() && client == null) {
      throw new IOException("Connection closed!");
    } else if (incomingRequests.isEmpty()) {
      throw new TimeoutException("Timeout exceeded");
    } else {
      return incomingRequests.removeFirst();
    }
  }

  public synchronized Single<WebsocketResponse> sendRequest(WebSocketRequestMessage request) throws IOException {
    if (client == null) {
      throw new IOException("No connection!");
    }

    WebSocketMessage message = new WebSocketMessage.Builder()
                                                   .type(WebSocketMessage.Type.REQUEST)
                                                   .request(request)
                                                   .build();

    SingleSubject<WebsocketResponse> single = SingleSubject.create();

    outgoingRequests.put(request.id, new OutgoingRequest(single));

    if (!client.send(ByteString.of(message.encode()))) {
      throw new IOException("Write failed!");
    }

    return single.subscribeOn(Schedulers.io())
                 .observeOn(Schedulers.io())
                 .timeout(10, TimeUnit.SECONDS, Schedulers.io());
  }

  public synchronized void sendResponse(WebSocketResponseMessage response) throws IOException {
    if (client == null) {
      throw new IOException("Connection closed!");
    }

    WebSocketMessage message = new WebSocketMessage.Builder()
                                                   .type(WebSocketMessage.Type.RESPONSE)
                                                   .response(response)
                                                   .build();

    if (!client.send(ByteString.of(message.encode()))) {
      throw new IOException("Write failed!");
    }
  }

  public synchronized void sendKeepAlive() throws IOException {
    if (client != null) {
      log( "Sending keep alive...");
      long id = System.currentTimeMillis();
      byte[] message = new WebSocketMessage.Builder()
                                           .type(WebSocketMessage.Type.REQUEST)
                                           .request(new WebSocketRequestMessage.Builder()
                                                                               .id(id)
                                                                               .path("/v1/keepalive")
                                                                               .verb("GET")
                                                                               .build())
                                           .build()
                                           .encode();
      keepAlives.add(id);
      if (!client.send(ByteString.of(message))) {
        throw new IOException("Write failed!");
      }
    }
  }

  @Override
  public synchronized void onOpen(WebSocket webSocket, Response response) {
    if (client != null) {
      log("onOpen() connected");
      webSocketState.onNext(WebSocketConnectionState.CONNECTED);
    }
  }

  @Override
  public synchronized void onMessage(WebSocket webSocket, ByteString payload) {
    try {
      WebSocketMessage message = WebSocketMessage.ADAPTER.decode(payload.toByteArray());

      if (message.type == WebSocketMessage.Type.REQUEST) {
        incomingRequests.add(message.request);
      } else if (message.type == WebSocketMessage.Type.RESPONSE) {
        OutgoingRequest listener = outgoingRequests.remove(message.response.id);
        if (listener != null) {
          listener.onSuccess(new WebsocketResponse(message.response.status,
                                                   new String(message.response.body.toByteArray()),
                                                   message.response.headers,
                                                   !credentialsProvider.isPresent()));
          if (message.response.status >= 400) {
            healthMonitor.onMessageError(message.response.status, credentialsProvider.isPresent());
          }
        } else if (keepAlives.remove(message.response.id)) {
          healthMonitor.onKeepAliveResponse(message.response.id, credentialsProvider.isPresent());
        }
      }

      notifyAll();
    } catch (IOException e) {
      warn(e);
    }
  }

  @Override
  public synchronized void onClosed(WebSocket webSocket, int code, String reason) {
    log("onClose()");
    webSocketState.onNext(WebSocketConnectionState.DISCONNECTED);

    cleanupAfterShutdown();

    notifyAll();
  }

  @Override
  public synchronized void onFailure(WebSocket webSocket, Throwable t, Response response) {
    warn("onFailure()", t);

    if (response != null && (response.code() == 401 || response.code() == 403)) {
      webSocketState.onNext(WebSocketConnectionState.AUTHENTICATION_FAILED);
    } else {
      webSocketState.onNext(WebSocketConnectionState.FAILED);
    }

    cleanupAfterShutdown();

    if (okHttpClient != null ) {
      okHttpClient.dispatcher().executorService().shutdown();
      okHttpClient = null;
    }

    notifyAll();
  }

  private void cleanupAfterShutdown() {
    Iterator<Map.Entry<Long, OutgoingRequest>> iterator = outgoingRequests.entrySet().iterator();

    while (iterator.hasNext()) {
      Map.Entry<Long, OutgoingRequest> entry = iterator.next();
      entry.getValue().onError(new IOException("Closed unexpectedly"));
      iterator.remove();
    }

    if (client != null) {
      log("Client not null when closed");
      client.close(1000, "OK");
      client = null;
    }
  }

  @Override
  public void onMessage(WebSocket webSocket, String text) {
    Log.d(TAG, "onMessage(text)");
  }

  @Override
  public synchronized void onClosing(WebSocket webSocket, int code, String reason) {
    log("onClosing()");
    webSocketState.onNext(WebSocketConnectionState.DISCONNECTING);
    webSocket.close(1000, "OK");
  }

  private long elapsedTime(long startTime) {
    return System.currentTimeMillis() - startTime;
  }

  private Pair<SSLSocketFactory, X509TrustManager> createTlsSocketFactory(TrustStore trustStore) {
    try {
      SSLContext     context       = SSLContext.getInstance("TLS");
      TrustManager[] trustManagers = BlacklistingTrustManager.createFor(trustStore);
      context.init(null, trustManagers, null);

      return new Pair<>(context.getSocketFactory(), (X509TrustManager) trustManagers[0]);
    } catch (NoSuchAlgorithmException | KeyManagementException e) {
      throw new AssertionError(e);
    }
  }

  private void log(String message) {
    Log.i(TAG, name + " " + message);
  }

  @SuppressWarnings("SameParameterValue")
  private void warn(String message) {
    Log.w(TAG, name + " " + message);
  }

  private void warn(Throwable e) {
    Log.w(TAG, name, e);
  }

  @SuppressWarnings("SameParameterValue")
  private void warn(String message, Throwable e) {
    Log.w(TAG, name + " " + message, e);
  }

  private static class OutgoingRequest {
    private final SingleSubject<WebsocketResponse> responseSingle;

    private OutgoingRequest(SingleSubject<WebsocketResponse> future) {
      this.responseSingle = future;
    }

    public void onSuccess(WebsocketResponse response) {
      responseSingle.onSuccess(response);
    }

    public void onError(Throwable throwable) {
      responseSingle.onError(throwable);
    }
  }
}
