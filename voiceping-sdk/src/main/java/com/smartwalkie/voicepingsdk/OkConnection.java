package com.smartwalkie.voicepingsdk;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.smartwalkie.voicepingsdk.callback.ConnectCallback;
import com.smartwalkie.voicepingsdk.callback.DisconnectCallback;
import com.smartwalkie.voicepingsdk.exception.ErrorCode;
import com.smartwalkie.voicepingsdk.exception.VoicePingException;
import com.smartwalkie.voicepingsdk.listener.ConnectionStateListener;
import com.smartwalkie.voicepingsdk.listener.IncomingAudioListener;
import com.smartwalkie.voicepingsdk.listener.OutgoingAudioListener;
import com.smartwalkie.voicepingsdk.listener.PersonPresence;
import com.smartwalkie.voicepingsdk.listener.PresenceListener;
import com.smartwalkie.voicepingsdk.listener.SiteVersion;
import com.smartwalkie.voicepingsdk.listener.TokenCallback;
import com.smartwalkie.voicepingsdk.listener.TokenProvider;
import com.smartwalkie.voicepingsdk.model.Message;
import com.smartwalkie.voicepingsdk.model.MessageType;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.json.JSONArray;
import org.json.JSONObject;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.X509TrustManager;

import okhttp3.JavaNetCookieJar;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

class OkConnection implements Connection {
    private final String TAG = OkConnection.class.getSimpleName();

    // Process-lifetime cookie jar. Traefik sticky cookie (vp_node) must survive
    // reconnects so the LB pins to the same node → hits local subscription map
    // fast path for session restore. Cross-process persistence not needed;
    // Redis-backed restore covers cold start.
    private static final CookieManager COOKIE_MANAGER =
            new CookieManager(null, CookiePolicy.ACCEPT_ALL);

    // Router close codes.
    private static final int CLOSE_NORMAL = 1000;
    private static final int CLOSE_SESSION_TAKEN = 4409;

    private final Context mContext;
    private final ConnectivityManager mConnectivityManager;
    private OkHttpClient mOkHttpClient;
    private String mServerUrl;
    private String mToken;
    private final Handler mMainHandler;
    // All connection state below is touched only on this handler's thread.
    private final Handler mBackgroundHandler;

    // Current socket. Events from any other socket are stale and ignored.
    private volatile WebSocket mWebSocket;
    private ConnectCallback mConnectCallback;
    private IncomingAudioListener mIncomingAudioListener;
    private volatile OutgoingAudioListener mOutgoingAudioListener;
    private volatile PresenceListener mPresenceListener;
    private volatile ConnectionStateListener mConnectionStateListener;
    private volatile ConnectionState mConnectionState = ConnectionState.DISCONNECTED;
    // True from connect() until disconnect(), 4409, or an unrecoverable 401.
    private boolean mWanted;

    private static final long RECONNECT_BASE_MS = 1000L;
    private static final long RECONNECT_MAX_MS = 30_000L;
    private static final double RECONNECT_JITTER = 0.3;
    // Wi-Fi to mobile handoff fires several callbacks; reconnect once after they settle.
    private static final long NETWORK_DEBOUNCE_MS = 300L;
    private int mReconnectAttempt;
    private final SecureRandom mReconnectRandom = new SecureRandom();
    private final Runnable mReconnectRunnable = new Runnable() {
        @Override
        public void run() {
            if (mWanted) openSocket();
        }
    };
    private final Runnable mNetworkChangedRunnable = new Runnable() {
        @Override
        public void run() {
            if (!mWanted || mAwaitingToken) return;
            boolean newNetwork = mDefaultNetwork != null && !mDefaultNetwork.equals(mSocketNetwork);
            if (newNetwork || mWebSocket == null) {
                Log.d(TAG, "network changed: reconnecting now");
                resetReconnectBackoff();
                openSocket();
            }
        }
    };
    private ConnectivityManager.NetworkCallback mNetworkCallback;
    private Network mDefaultNetwork;
    private Network mSocketNetwork;

    // Session key -> last AUDIO arrival ms. Used to flag inter-arrival gaps.
    private final Map<String, Long> mLastAudioArrivalMs = new HashMap<>();
    // Frame period ~60ms (16kHz / 960 samples). 500ms = ~8 frames missing,
    // well above normal mobile WS jitter — signals real drop, not RTT noise.
    private static final long AUDIO_GAP_THRESHOLD_MS = 500L;

    // Drop outgoing AUDIO frames when OkHttp WS send queue exceeds this many
    // bytes. Router applies its own backpressure; buffering locally past this
    // just adds latency without helping.
    private static final long SEND_QUEUE_DROP_THRESHOLD_BYTES = 256 * 1024L;
    private volatile long mDroppedFrameCount;

    private volatile TokenProvider mTokenProvider;
    // True while awaiting a fresh JWT — suppresses backoff-driven reconnects.
    private boolean mAwaitingToken;
    private volatile Runnable mOnConnectedHook;

    OkConnection(Context context, IncomingAudioListener listener, Looper backgroundLooper) {
        mContext = context;
        mConnectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        mIncomingAudioListener = listener;
        mMainHandler = new Handler(Looper.getMainLooper());
        mBackgroundHandler = new Handler(backgroundLooper);
    }

    private OkHttpClient createHttpClient(String serverUrl) {
        OkHttpClient.Builder clientBuilder = new OkHttpClient.Builder()
                .readTimeout(5, TimeUnit.SECONDS)
                .pingInterval(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .cookieJar(new JavaNetCookieJar(COOKIE_MANAGER));
        if (serverUrl != null && serverUrl.startsWith("wss:")) {
            try {
                SSLContext sslContext = SSLContext.getInstance("TLS");
                X509TrustManager trustManager = new X509TrustManager() {
                    @SuppressLint("TrustAllX509TrustManager")
                    /**
                     * TODO
                     * We should place another client check here for adding an extra security measure
                     */
                    @Override
                    public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                    }

                    @SuppressLint("TrustAllX509TrustManager")
                    /**
                     * TODO
                     * We should place another server check here for adding an extra security measure
                     */
                    @Override
                    public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                    }

                    @Override
                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[0];
                    }
                };
                sslContext.init(null, new X509TrustManager[]{trustManager}, new SecureRandom());
                clientBuilder.sslSocketFactory(sslContext.getSocketFactory(), trustManager);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return clientBuilder.build();
    }

    private void checkAudioGap(Message message) {
        int type = message.getMessageType();
        String sender = message.getSenderId();
        if (sender == null) return;
        String key = message.getChannelType() + ":" + sender;
        long now = System.currentTimeMillis();
        if (type == MessageType.START_TALKING) {
            mLastAudioArrivalMs.put(key, now);
        } else if (type == MessageType.STOP_TALKING) {
            mLastAudioArrivalMs.remove(key);
        } else if (type == MessageType.AUDIO) {
            Long last = mLastAudioArrivalMs.put(key, now);
            if (last != null) {
                long gap = now - last;
                if (gap > AUDIO_GAP_THRESHOLD_MS && mIncomingAudioListener != null) {
                    mIncomingAudioListener.onAudioGapDetected(sender, message.getChannelType(), gap);
                }
            }
        }
    }

    private boolean isIncomingMessageType(int messageType) {
        return messageType == MessageType.START_TALKING
                || messageType == MessageType.AUDIO
                || messageType == MessageType.STOP_TALKING;
    }

    private boolean isOutgoingMessageType(int messageType) {
        return messageType == MessageType.ACK_START
                || messageType == MessageType.ACK_START_FAILED
                || messageType == MessageType.ACK_END;
    }

    @Override
    public void setConnectionStateListener(@Nullable ConnectionStateListener listener) {
        mConnectionStateListener = listener;
    }

    @Override
    public void setOutgoingAudioListener(OutgoingAudioListener listener) {
        mOutgoingAudioListener = listener;
    }

    @Override
    public void setPresenceListener(@Nullable PresenceListener listener) {
        mPresenceListener = listener;
    }

    @Override
    public void setTokenProvider(@Nullable TokenProvider provider) {
        mTokenProvider = provider;
    }

    @Override
    public void setOnConnectedHook(@Nullable Runnable hook) {
        mOnConnectedHook = hook;
    }

    // Router events arrive as PRIVATE from 0 with a JSON string payload.
    private void dispatchRouterEvent(final Message message) {
        final PresenceListener listener = mPresenceListener;
        if (listener == null) return;
        int type = message.getMessageType();
        if (type == MessageType.TEAM_CHANGED) {
            mMainHandler.post(listener::onTeamChanged);
            return;
        }
        String json = message.getContent();
        if (json == null || json.isEmpty()) return;
        try {
            JSONObject obj = new JSONObject(json);
            if (type == MessageType.SITE_STATUS) {
                final SiteVersion site = new SiteVersion(obj.getString("site"), obj.optString("v", ""));
                mMainHandler.post(() -> listener.onSiteStatus(site));
            } else if (type == MessageType.PRESENCE) {
                String kind = obj.optString("kind", "");
                if ("snapshot".equals(kind)) {
                    final List<PersonPresence> people = new ArrayList<>();
                    JSONArray peopleArr = obj.optJSONArray("people");
                    if (peopleArr != null) {
                        for (int i = 0; i < peopleArr.length(); i++) {
                            people.add(parsePerson(peopleArr.getJSONObject(i)));
                        }
                    }
                    final List<SiteVersion> sites = new ArrayList<>();
                    JSONArray siteArr = obj.optJSONArray("sites");
                    if (siteArr != null) {
                        for (int i = 0; i < siteArr.length(); i++) {
                            JSONObject s = siteArr.getJSONObject(i);
                            sites.add(new SiteVersion(s.getString("id"), s.optString("v", "")));
                        }
                    }
                    mMainHandler.post(() -> listener.onPresenceSnapshot(people, sites));
                } else if ("presence".equals(kind)) {
                    final PersonPresence person = parsePerson(obj);
                    mMainHandler.post(() -> listener.onPresence(person));
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "router event parse failed: " + e.getMessage());
        }
    }

    private PersonPresence parsePerson(JSONObject o) throws Exception {
        return new PersonPresence(o.getString("id"), o.getBoolean("online"), o.optString("v", ""));
    }

    @Override
    public String getServerUrl() {
        return mServerUrl;
    }

    @Override
    public void connect(@NotNull final String serverUrl, @NotNull final String token, @NotNull final ConnectCallback callback) {
        if (!NetworkUtil.isNetworkConnected(mContext)) {
            callback.onFailed(new VoicePingException("Please check your internet connection!", ErrorCode.INTERNET_DISCONNECTED));
            return;
        }
        if (serverUrl.isEmpty() || token.isEmpty()) {
            callback.onFailed(new VoicePingException("Invalid parameters!", ErrorCode.INTERNET_DISCONNECTED));
            return;
        }
        mBackgroundHandler.post(new Runnable() {
            @Override
            public void run() {
                mServerUrl = serverUrl;
                mOkHttpClient = createHttpClient(serverUrl);
                mToken = token;
                mConnectCallback = callback;
                start();
            }
        });
    }

    @Override
    public void updateToken(@NotNull final String token) {
        mBackgroundHandler.post(new Runnable() {
            @Override
            public void run() {
                if (mServerUrl == null) return;
                mToken = token;
                mAwaitingToken = false;
                Log.d(TAG, "token updated: reconnecting");
                start();
            }
        });
    }

    @NotNull
    @Override
    public ConnectionState getConnectionState() {
        return mConnectionState;
    }

    private void updateConnectionState(ConnectionState connectionState) {
        if (mConnectionState == connectionState) return;
        Log.d(TAG, "updateConnectionState: " + connectionState.name());
        mConnectionState = connectionState;
        ConnectionStateListener listener = mConnectionStateListener;
        if (listener != null) {
            listener.onConnectionStateChanged(connectionState);
        }
    }

    // Starts (or restarts) the connection with the current token.
    private void start() {
        mWanted = true;
        registerNetworkCallback();
        resetReconnectBackoff();
        openSocket();
    }

    private void openSocket() {
        if (mServerUrl == null || mServerUrl.isEmpty() || mOkHttpClient == null) return;
        mBackgroundHandler.removeCallbacks(mReconnectRunnable);
        // cancel(), not close(): the router quietly replaces a socket of the same deviceId,
        // so no offline blip and no wait for a close handshake on a possibly dead link.
        WebSocket old = mWebSocket;
        mWebSocket = null;
        if (old != null) old.cancel();
        updateConnectionState(ConnectionState.CONNECTING);
        mSocketNetwork = mDefaultNetwork;
        Request request = new Request.Builder().url(mServerUrl)
                .addHeader("token", mToken != null ? mToken : "")
                .build();
        Log.d(TAG, "Connecting...");
        mWebSocket = mOkHttpClient.newWebSocket(request, new SocketListener());
    }

    private void registerNetworkCallback() {
        if (mNetworkCallback != null || mConnectivityManager == null) return;
        mDefaultNetwork = mConnectivityManager.getActiveNetwork();
        mSocketNetwork = mDefaultNetwork;
        mNetworkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(@NotNull Network network) {
                mDefaultNetwork = network;
                mBackgroundHandler.removeCallbacks(mNetworkChangedRunnable);
                mBackgroundHandler.postDelayed(mNetworkChangedRunnable, NETWORK_DEBOUNCE_MS);
            }

            @Override
            public void onLost(@NotNull Network network) {
                if (network.equals(mDefaultNetwork)) mDefaultNetwork = null;
            }
        };
        mConnectivityManager.registerDefaultNetworkCallback(mNetworkCallback, mBackgroundHandler);
    }

    private void unregisterNetworkCallback() {
        if (mNetworkCallback == null || mConnectivityManager == null) return;
        try {
            mConnectivityManager.unregisterNetworkCallback(mNetworkCallback);
        } catch (IllegalArgumentException ignored) {
        }
        mNetworkCallback = null;
        mBackgroundHandler.removeCallbacks(mNetworkChangedRunnable);
    }

    // The one backoff path: 1 s doubling to 30 s, jittered.
    private void scheduleReconnect() {
        if (!mWanted) return;
        if (mAwaitingToken) {
            Log.d(TAG, "reconnect suppressed: awaiting fresh JWT");
            return;
        }
        updateConnectionState(ConnectionState.CONNECTING);
        long delay = nextReconnectDelayMs();
        Log.d(TAG, "reconnect scheduled in " + delay + "ms (attempt=" + mReconnectAttempt + ")");
        mBackgroundHandler.removeCallbacks(mReconnectRunnable);
        mBackgroundHandler.postDelayed(mReconnectRunnable, delay);
    }

    private long nextReconnectDelayMs() {
        long exp = RECONNECT_BASE_MS << Math.min(mReconnectAttempt, 5);
        long capped = Math.min(exp, RECONNECT_MAX_MS);
        double jitter = 1.0 + (mReconnectRandom.nextDouble() * 2 - 1) * RECONNECT_JITTER;
        mReconnectAttempt++;
        return Math.max(RECONNECT_BASE_MS, Math.min(RECONNECT_MAX_MS, (long) (capped * jitter)));
    }

    private void resetReconnectBackoff() {
        mReconnectAttempt = 0;
    }

    private void handleTokenExpired() {
        TokenProvider provider = mTokenProvider;
        if (provider == null) {
            stopWith(new VoicePingException("Token expired", ErrorCode.TOKEN_EXPIRED));
            return;
        }
        Log.d(TAG, "TokenExpired: requesting fresh JWT");
        mAwaitingToken = true;
        updateConnectionState(ConnectionState.CONNECTING);
        provider.onTokenExpired(new TokenCallback() {
            @Override
            public void onToken(@NotNull final String jwt) {
                mBackgroundHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!mAwaitingToken || !mWanted) return;
                        mAwaitingToken = false;
                        mToken = jwt;
                        resetReconnectBackoff();
                        Log.d(TAG, "TokenExpired: got fresh JWT, reconnecting");
                        openSocket();
                    }
                });
            }

            @Override
            public void onError(@NotNull final Throwable e) {
                mBackgroundHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!mAwaitingToken) return;
                        mAwaitingToken = false;
                        Log.w(TAG, "TokenExpired: refresh failed, falling back to backoff: " + e.getMessage());
                        scheduleReconnect();
                    }
                });
            }
        });
    }

    // Ends the connection without reconnecting and tells the app why (4409, unauthorized).
    private void stopWith(final VoicePingException error) {
        mWanted = false;
        mAwaitingToken = false;
        mBackgroundHandler.removeCallbacks(mReconnectRunnable);
        unregisterNetworkCallback();
        WebSocket old = mWebSocket;
        mWebSocket = null;
        if (old != null) old.cancel();
        updateConnectionState(ConnectionState.DISCONNECTED);
        ConnectionStateListener listener = mConnectionStateListener;
        if (listener != null) listener.onConnectionError(error);
        failPendingConnect(error);
    }

    private void failPendingConnect(final VoicePingException error) {
        final ConnectCallback callback = mConnectCallback;
        mConnectCallback = null;
        if (callback != null) {
            mMainHandler.post(new Runnable() {
                @Override
                public void run() {
                    callback.onFailed(error);
                }
            });
        }
    }

    @Override
    public void disconnect(final DisconnectCallback callback) {
        mBackgroundHandler.post(new Runnable() {
            @Override
            public void run() {
                Log.d(TAG, "close WebSocket...");
                mWanted = false;
                mAwaitingToken = false;
                mBackgroundHandler.removeCallbacks(mReconnectRunnable);
                unregisterNetworkCallback();
                resetReconnectBackoff();
                WebSocket ws = mWebSocket;
                mWebSocket = null;
                if (ws != null) {
                    // Graceful close so the router marks the user offline at once.
                    if (!ws.close(CLOSE_NORMAL, "client_shutdown")) ws.cancel();
                }
                updateConnectionState(ConnectionState.DISCONNECTED);
                if (callback != null) {
                    mMainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onDisconnected();
                        }
                    });
                }
            }
        });
    }

    @Override
    public void send(byte[] data) {
        WebSocket ws = mWebSocket;
        if (mConnectionState != ConnectionState.CONNECTED || ws == null) {
            OutgoingAudioListener listener = mOutgoingAudioListener;
            if (listener != null) {
                listener.onSendMessageFailed(data, new VoicePingException("WebSocket is not connected!", ErrorCode.SOCKET_DISCONNECTED));
            }
            return;
        }
        if (ws.queueSize() > SEND_QUEUE_DROP_THRESHOLD_BYTES) {
            mDroppedFrameCount++;
            if ((mDroppedFrameCount & 0x1F) == 0) {
                Log.w(TAG, "send backpressure: dropped " + mDroppedFrameCount
                        + " frames, queueSize=" + ws.queueSize());
            }
            return;
        }
        ws.send(ByteString.of(data));
    }

    // ---- socket events, all handled on the background thread ----

    private void onSocketOpen(WebSocket ws) {
        if (ws != mWebSocket) return;
        updateConnectionState(ConnectionState.CONNECTED);
        resetReconnectBackoff();
        Runnable hook = mOnConnectedHook;
        if (hook != null) hook.run();
        final ConnectCallback callback = mConnectCallback;
        mConnectCallback = null;
        if (callback != null) {
            mMainHandler.post(new Runnable() {
                @Override
                public void run() {
                    callback.onConnected();
                }
            });
        }
    }

    private void onSocketMessage(WebSocket ws, Message message) {
        if (ws != mWebSocket) return;
        int type = message.getMessageType();
        if (mIncomingAudioListener != null && isIncomingMessageType(type)) {
            checkAudioGap(message);
            mIncomingAudioListener.onMessageReceived(message);
        }
        OutgoingAudioListener outgoing = mOutgoingAudioListener;
        if (outgoing != null && isOutgoingMessageType(type)) {
            outgoing.onMessageReceived(message);
        }
        if (type == MessageType.PRESENCE || type == MessageType.TEAM_CHANGED
                || type == MessageType.SITE_STATUS) {
            dispatchRouterEvent(message);
        }
    }

    private void onSocketEnded(WebSocket ws, int code, String reason) {
        if (ws != mWebSocket) return;
        mWebSocket = null;
        if (code == CLOSE_SESSION_TAKEN) {
            stopWith(new VoicePingException("Signed in on another device", ErrorCode.SESSION_TAKEN));
            return;
        }
        if (code == CLOSE_NORMAL) {
            // Replaced by another socket of this same device: nothing to restore or report.
            Log.d(TAG, "socket closed normally: " + reason);
            updateConnectionState(ConnectionState.DISCONNECTED);
            return;
        }
        connectionLost();
    }

    private void onSocketFailure(WebSocket ws, Throwable t, Response response) {
        if (ws != mWebSocket) return;
        Log.d(TAG, "WebSocket onFailure, error: " + t.getMessage()
                + (response != null ? ", response: " + response : ""));
        mWebSocket = null;
        if (response != null && response.code() == 401) {
            if ("TokenExpired".equalsIgnoreCase(response.message())) {
                handleTokenExpired();
            } else {
                stopWith(new VoicePingException("Unauthorized", ErrorCode.UNAUTHORIZED));
            }
            return;
        }
        connectionLost();
    }

    // Unexpected loss: tell talk listeners, then retry on the backoff.
    private void connectionLost() {
        if (!mWanted) return;
        VoicePingException error = new VoicePingException(
                "Connection failure. Please check your internet connection!", ErrorCode.CONNECTION_FAILURE);
        failPendingConnect(new VoicePingException("Failed to connect!", ErrorCode.CONNECTION_FAILURE));
        if (mIncomingAudioListener != null) mIncomingAudioListener.onConnectionFailure(error);
        OutgoingAudioListener outgoing = mOutgoingAudioListener;
        if (outgoing != null) outgoing.onConnectionFailure(error);
        scheduleReconnect();
    }

    private class SocketListener extends WebSocketListener {

        @Override
        public void onOpen(final WebSocket webSocket, Response response) {
            Log.d(TAG, "WebSocket onOpen");
            mBackgroundHandler.post(new Runnable() {
                @Override
                public void run() {
                    onSocketOpen(webSocket);
                }
            });
        }

        @Override
        public void onMessage(final WebSocket webSocket, ByteString bytes) {
            final Message message = MessageHelper.unpackMessage(bytes.toByteArray());
            if (message == null) return;
            mBackgroundHandler.post(new Runnable() {
                @Override
                public void run() {
                    onSocketMessage(webSocket, message);
                }
            });
        }

        @Override
        public void onClosing(final WebSocket webSocket, final int code, final String reason) {
            Log.d(TAG, "WebSocket onClosing, code: " + code + ", reason: " + reason);
            webSocket.close(CLOSE_NORMAL, null);
            mBackgroundHandler.post(new Runnable() {
                @Override
                public void run() {
                    onSocketEnded(webSocket, code, reason);
                }
            });
        }

        @Override
        public void onClosed(final WebSocket webSocket, final int code, final String reason) {
            Log.d(TAG, "WebSocket onClosed, code: " + code + ", reason: " + reason);
            mBackgroundHandler.post(new Runnable() {
                @Override
                public void run() {
                    onSocketEnded(webSocket, code, reason);
                }
            });
        }

        @Override
        public void onFailure(final WebSocket webSocket, final Throwable t, final Response response) {
            mBackgroundHandler.post(new Runnable() {
                @Override
                public void run() {
                    onSocketFailure(webSocket, t, response);
                }
            });
        }
    }
}
