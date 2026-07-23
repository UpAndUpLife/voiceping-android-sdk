package com.smartwalkie.voicepingsdk;

import android.annotation.SuppressLint;
import android.content.Context;
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
import com.smartwalkie.voicepingsdk.listener.PresenceListener;
import com.smartwalkie.voicepingsdk.listener.PresenceMember;
import com.smartwalkie.voicepingsdk.listener.TokenCallback;
import com.smartwalkie.voicepingsdk.listener.TokenProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.smartwalkie.voicepingsdk.model.ChannelType;
import com.smartwalkie.voicepingsdk.model.Message;
import com.smartwalkie.voicepingsdk.model.MessageType;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.X509TrustManager;

import java.net.CookieManager;
import java.net.CookiePolicy;

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
    private final int CONNECTED = 100;
    private final int DISCONNECTED = 200;
    private final int MESSAGE = 300;
    private final int FAILURE = 400;

    private final Context mContext;
    private OkHttpClient mOkHttpClient;
    private WebSocket mWebSocket;
    private String mServerUrl;
    private String mUserId;
    private String mDeviceId;
    private final Handler mMainHandler;
    private final Handler mBackgroundHandler;

    private ConnectCallback mConnectCallback;
    private DisconnectCallback mDisconnectCallback;
    private IncomingAudioListener mIncomingAudioListener;
    private OutgoingAudioListener mOutgoingAudioListener;
    private PresenceListener mPresenceListener;
    private ConnectionStateListener mConnectionStateListener;
    private volatile ConnectionState mConnectionState = ConnectionState.DISCONNECTED;
    private volatile boolean mIsReconnecting;

    private static final long RECONNECT_BASE_MS = 1000L;
    private static final long RECONNECT_MAX_MS = 30_000L;
    private static final double RECONNECT_JITTER = 0.3;
    private int mReconnectAttempt;
    private final SecureRandom mReconnectRandom = new SecureRandom();

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
    private volatile boolean mAwaitingToken;
    private volatile Runnable mOnConnectedHook;

    OkConnection(Context context, IncomingAudioListener listener, Looper backgroundLooper) {
        mContext = context;
        mIncomingAudioListener = listener;
        mMainHandler = new Handler(Looper.getMainLooper());
        mBackgroundHandler = new Handler(backgroundLooper, new Handler.Callback() {

            @Override
            public boolean handleMessage(android.os.Message msg) {
                switch (msg.what) {
                    case CONNECTED:
                        updateConnectionState(ConnectionState.CONNECTED);
                        resetReconnectBackoff();
                        send(MessageHelper.createConnectionMessage(mUserId));
                        Runnable hook = mOnConnectedHook;
                        if (hook != null) hook.run();
                        Message dummyAckStart = MessageHelper.createAckStartMessage(mUserId, "00000", ChannelType.PRIVATE, System.currentTimeMillis());
                        if (dummyAckStart != null) {
                            send(dummyAckStart.getPayload());
                        }
                        if (mConnectCallback != null) {
                            mMainHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    if (mConnectCallback != null) {
                                        mConnectCallback.onConnected();
                                        mConnectCallback = null;
                                    }
                                }
                            });
                        }
                        return true;
                    case DISCONNECTED:
                        updateConnectionState(ConnectionState.DISCONNECTED);
                        if (mDisconnectCallback != null) {
                            mMainHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    if (mDisconnectCallback != null) {
                                        mDisconnectCallback.onDisconnected();
                                        mDisconnectCallback = null;
                                    }
                                }
                            });
                        }
                        return true;
                    case MESSAGE:
                        Message message = (Message) msg.obj;
                        if (message == null) return false;
                        if (message.getMessageType() == MessageType.DUPLICATE_CONNECT) {
                            mMainHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    disconnect(null);
                                    if (mConnectionStateListener != null) {
                                        mConnectionStateListener.onConnectionError(
                                                new VoicePingException("DUPLICATE_CONNECT", ErrorCode.DUPLICATE_CONNECT));
                                    }
                                }
                            });
                        }
                        if (mIncomingAudioListener != null && isIncomingMessageType(message.getMessageType())) {
                            checkAudioGap(message);
                            mIncomingAudioListener.onMessageReceived(message);
                        }
                        if (mOutgoingAudioListener != null && isOutgoingMessageType(message.getMessageType())) {
                            mOutgoingAudioListener.onMessageReceived(message);
                        }
                        if (message.getMessageType() == MessageType.PRESENCE) {
                            dispatchPresence(message);
                        }
                        return true;
                    case FAILURE:
                        if (mConnectionState == ConnectionState.DISCONNECTED) return true;
                        if (mConnectionState == ConnectionState.CONNECTED) {
                            updateConnectionState(ConnectionState.DISCONNECTED);
                        }
                        if (mConnectCallback != null) {
                            mMainHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    if (mConnectCallback != null) {
                                        mConnectCallback.onFailed(new VoicePingException("Failed to connect!", ErrorCode.CONNECTION_FAILURE));
                                        mConnectCallback = null;
                                    }
                                }
                            });
                        }
                        if (mIncomingAudioListener != null) {
                            mIncomingAudioListener.onConnectionFailure(new VoicePingException(
                                    "Connection failure. Please check your internet connection!", ErrorCode.CONNECTION_FAILURE));
                        }
                        if (mOutgoingAudioListener != null) {
                            mOutgoingAudioListener.onConnectionFailure(new VoicePingException(
                                    "Connection failure. Please check your internet connection!", ErrorCode.CONNECTION_FAILURE));
                        }
                        reconnectWithDelay();
                        return true;
                }
                return false;
            }
        });
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
                || messageType == MessageType.ACK_END
                || messageType == MessageType.MESSAGE_DELIVERED
                || messageType == MessageType.MESSAGE_READ
                || messageType == MessageType.UNAUTHORIZED_GROUP;
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

    private void dispatchPresence(Message message) {
        PresenceListener listener = mPresenceListener;
        if (listener == null) return;
        String channel = message.getReceiverId();
        String json = message.getContent();
        if (channel == null || json == null || json.isEmpty()) return;
        try {
            JSONObject obj = new JSONObject(json);
            String event = obj.optString("event", "");
            if ("sync".equals(event)) {
                JSONArray arr = obj.optJSONArray("members");
                List<PresenceMember> list = new ArrayList<>();
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject m = arr.getJSONObject(i);
                        list.add(new PresenceMember(m.optString("clientId"), m.optString("data")));
                    }
                }
                mMainHandler.post(() -> listener.onPresenceSync(channel, list));
            } else {
                JSONObject m = obj.optJSONObject("member");
                if (m == null) return;
                PresenceMember member = new PresenceMember(m.optString("clientId"), m.optString("data"));
                switch (event) {
                    case "enter":  mMainHandler.post(() -> listener.onPresenceEnter(channel, member)); break;
                    case "leave":  mMainHandler.post(() -> listener.onPresenceLeave(channel, member)); break;
                    case "update": mMainHandler.post(() -> listener.onPresenceUpdate(channel, member)); break;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "PRESENCE parse failed: " + e.getMessage());
        }
    }

    @Override
    public String getServerUrl() {
        return mServerUrl;
    }

    @Override
    public void connect(@NotNull String serverUrl, @NotNull String userId, @NotNull String deviceId, @NotNull ConnectCallback callback) {
        if (!NetworkUtil.isNetworkConnected(mContext)) {
            callback.onFailed(new VoicePingException("Please check your internet connection!", ErrorCode.INTERNET_DISCONNECTED));
            return;
        }
        if (serverUrl.isEmpty() || userId.isEmpty() || deviceId.isEmpty()) {
            callback.onFailed(new VoicePingException("Invalid parameters!", ErrorCode.INTERNET_DISCONNECTED));
            return;
        }
        mServerUrl = serverUrl;
        mOkHttpClient = createHttpClient(serverUrl);
        mUserId = userId;
        mDeviceId = deviceId;
        mConnectCallback = callback;
        connect();
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
        if (mConnectionStateListener != null) {
            mConnectionStateListener.onConnectionStateChanged(connectionState);
        }
    }

    private void connect() {
        if (mServerUrl == null || mServerUrl.isEmpty()) return;
        if (mOkHttpClient == null) return;
        updateConnectionState(ConnectionState.CONNECTING);
        Request request = new Request.Builder().url(mServerUrl)
                .addHeader("VoicePingToken", mUserId != null ? mUserId : "")
                .addHeader("DeviceId", mDeviceId != null ? mDeviceId : "")
                .build();
        Log.d(TAG, "Connecting. Request headers: " + request.headers().toString());
        mWebSocket = mOkHttpClient.newWebSocket(request, new SocketListener());
//        mOkHttpClient.dispatcher().executorService().shutdown();
    }

    private void reconnectWithDelay() {
        if (mAwaitingToken) {
            Log.d(TAG, "reconnect suppressed: awaiting fresh JWT");
            return;
        }
        updateConnectionState(ConnectionState.CONNECTING);
        if (mIsReconnecting) return;
        mIsReconnecting = true;
        long delay = nextReconnectDelayMs();
        Log.d(TAG, "reconnect scheduled in " + delay + "ms (attempt=" + mReconnectAttempt + ")");
        Timer timer = new Timer();
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                mIsReconnecting = false;
                if (mConnectionState == ConnectionState.CONNECTING) {
                    connect();
                }
            }
        }, delay);
    }

    private long nextReconnectDelayMs() {
        long exp = RECONNECT_BASE_MS << Math.min(mReconnectAttempt, 5);
        long capped = Math.min(exp, RECONNECT_MAX_MS);
        double jitter = 1.0 + (mReconnectRandom.nextDouble() * 2 - 1) * RECONNECT_JITTER;
        mReconnectAttempt++;
        return Math.max(RECONNECT_BASE_MS, (long) (capped * jitter));
    }

    private void resetReconnectBackoff() {
        mReconnectAttempt = 0;
    }

    private void handleTokenExpired() {
        TokenProvider provider = mTokenProvider;
        if (provider == null) {
            mBackgroundHandler.sendEmptyMessage(FAILURE);
            return;
        }
        Log.d(TAG, "TokenExpired: requesting fresh JWT");
        mAwaitingToken = true;
        updateConnectionState(ConnectionState.CONNECTING);
        provider.onTokenExpired(new TokenCallback() {
            @Override
            public void onToken(@NotNull String jwt) {
                mAwaitingToken = false;
                mUserId = jwt;
                resetReconnectBackoff();
                Log.d(TAG, "TokenExpired: got fresh JWT, reconnecting");
                mBackgroundHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        connect();
                    }
                });
            }

            @Override
            public void onError(@NotNull Throwable e) {
                mAwaitingToken = false;
                Log.w(TAG, "TokenExpired: refresh failed, falling back to backoff: " + e.getMessage());
                mBackgroundHandler.sendEmptyMessage(FAILURE);
            }
        });
    }

    @Override
    public void disconnect(DisconnectCallback callback) {
        if (callback != null) {
            mDisconnectCallback = callback;
        }
        Log.d(TAG, "close WebSocket...");
        resetReconnectBackoff();
        if (mWebSocket != null) {
            // Graceful close: flushes enqueued frames (LEAVE/UNSUBSCRIBE) and
            // sends a close frame before tearing down. cancel() would discard
            // the write queue, causing router to fall back to grace TTL for
            // presence updates when the app is torn down while radio is cold.
            boolean closing = mWebSocket.close(1000, "client_shutdown");
            if (!closing) mWebSocket.cancel();
        }
        updateConnectionState(ConnectionState.DISCONNECTED);
        if (mDisconnectCallback != null) {
            mDisconnectCallback.onDisconnected();
            mDisconnectCallback = null;
        }
    }

    @Override
    public void send(byte[] data) {
        if (mConnectionState != ConnectionState.CONNECTED) {
            if (mOutgoingAudioListener != null) {
                mOutgoingAudioListener.onSendMessageFailed(data, new VoicePingException("WebSocket is not connected!", ErrorCode.SOCKET_DISCONNECTED));
            }
            return;
        }
        if (mWebSocket == null) return;
        if (mWebSocket.queueSize() > SEND_QUEUE_DROP_THRESHOLD_BYTES) {
            mDroppedFrameCount++;
            if ((mDroppedFrameCount & 0x1F) == 0) {
                Log.w(TAG, "send backpressure: dropped " + mDroppedFrameCount
                        + " frames, queueSize=" + mWebSocket.queueSize());
            }
            return;
        }
        mWebSocket.send(ByteString.of(data));
    }

    private class SocketListener extends WebSocketListener {

        @Override
        public void onOpen(WebSocket webSocket, Response response) {
            String responseString = response != null ? response.toString() : "";
            Log.d(TAG, "WebSocket onOpen, response: " + responseString);
            mBackgroundHandler.sendEmptyMessage(CONNECTED);
        }

        @Override
        public void onMessage(WebSocket webSocket, ByteString bytes) {
            Message message = MessageHelper.unpackMessage(bytes.toByteArray());
            if (message == null) return;
//            Log.d(TAG, "message: " + MessageType.getText(message.getMessageType()));

            android.os.Message msg = new android.os.Message();
            msg.what = MESSAGE;
            msg.obj = message;
            mBackgroundHandler.sendMessage(msg);
        }

        @Override
        public void onClosed(WebSocket webSocket, int code, String reason) {
            Log.d(TAG, "WebSocket onClosed, reason: " + reason);
            mBackgroundHandler.sendEmptyMessage(DISCONNECTED);
        }

        @Override
        public void onFailure(WebSocket webSocket, Throwable t, Response response) {
            Log.d(TAG, "WebSocket onFailure, error: " + t.getMessage());
            t.printStackTrace();
            if (response != null) Log.d(TAG, response.toString());
            if (response != null && response.code() == 401
                    && "TokenExpired".equalsIgnoreCase(response.message())
                    && mTokenProvider != null) {
                handleTokenExpired();
                return;
            }
            mBackgroundHandler.sendEmptyMessage(FAILURE);
        }
    }
}
