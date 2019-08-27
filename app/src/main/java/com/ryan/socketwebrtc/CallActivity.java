package com.ryan.socketwebrtc;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import org.java_websocket.WebSocket;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.handshake.ServerHandshake;
import org.java_websocket.server.WebSocketServer;
import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.Camera1Enumerator;
import org.webrtc.Camera2Enumerator;
import org.webrtc.CameraEnumerator;
import org.webrtc.DataChannel;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.Logging;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.MediaStreamTrack;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RendererCommon;
import org.webrtc.RtpReceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoDecoderFactory;
import org.webrtc.VideoEncoderFactory;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

public class CallActivity extends Activity {

    /** ---------和信令服务相关----------- */
    private final String address = "ws://10.88.1.1";

    private final int port = 8887;

    private boolean mIsServer = false;

    private SignalServer mServer;
    private SignalClient mClient;

    /** ---------和webrtc相关----------- */
    // 视频信息
    private static final int VIDEO_RESOLUTION_WIDTH = 1280;
    private static final int VIDEO_RESOLUTION_HEIGHT = 720;
    private static final int VIDEO_FPS = 30;

    // 打印log
    private TextView mLogcatView;

    // Opengl es
    private EglBase mRootEglBase;
    // 纹理渲染
    private SurfaceTextureHelper mSurfaceTextureHelper;

    private SurfaceViewRenderer mLocalSurfaceView;
    private SurfaceViewRenderer mRemoteSurfaceView;

    // 音视频数据
    public static final String VIDEO_TRACK_ID = "1";//"ARDAMSv0";
    public static final String AUDIO_TRACK_ID = "2";//"ARDAMSa0";
    private VideoTrack mVideoTrack;
    private AudioTrack mAudioTrack;

    // 视频采集
    private VideoCapturer mVideoCapturer;

    //用于数据传输
    private PeerConnection mPeerConnection;
    private PeerConnectionFactory mPeerConnectionFactory;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_call);

        // 用户打印信息
        mLogcatView = findViewById(R.id.LogcatView);

        mRootEglBase = EglBase.create();

        // 用于展示本地和远端视频
        mLocalSurfaceView = findViewById(R.id.LocalSurfaceView);
        mRemoteSurfaceView = findViewById(R.id.RemoteSurfaceView);

        mLocalSurfaceView.init(mRootEglBase.getEglBaseContext(), null);
        mLocalSurfaceView.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL);
        mLocalSurfaceView.setMirror(true);
        mLocalSurfaceView.setEnableHardwareScaler(false /* enabled */);

        mRemoteSurfaceView.init(mRootEglBase.getEglBaseContext(), null);
        mRemoteSurfaceView.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL);
        mRemoteSurfaceView.setMirror(true);
        mRemoteSurfaceView.setEnableHardwareScaler(true /* enabled */);
        mRemoteSurfaceView.setZOrderMediaOverlay(true); // 注意这句，因为2个surfaceview是叠加的

        // 创建PC factory , PC就是从factory里面获取的
        mPeerConnectionFactory = createPeerConnectionFactory(this);

        // NOTE: this _must_ happen while PeerConnectionFactory is alive!
        Logging.enableLogToDebugOutput(Logging.Severity.LS_VERBOSE);

        // 创建视频采集器
        mVideoCapturer = createVideoCapturer();

        mSurfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", mRootEglBase.getEglBaseContext());
        VideoSource videoSource = mPeerConnectionFactory.createVideoSource(false);
        mVideoCapturer.initialize(mSurfaceTextureHelper, getApplicationContext(), videoSource.getCapturerObserver());

        mVideoTrack = mPeerConnectionFactory.createVideoTrack(VIDEO_TRACK_ID, videoSource);
        mVideoTrack.setEnabled(true);
        mVideoTrack.addSink(mLocalSurfaceView); // 设置渲染到本地surfaceview上

        //AudioSource 和 AudioTrack 与VideoSource和VideoTrack相似，只是不需要AudioCapturer 来获取麦克风，
        AudioSource audioSource = mPeerConnectionFactory.createAudioSource(new MediaConstraints());
        mAudioTrack = mPeerConnectionFactory.createAudioTrack(AUDIO_TRACK_ID, audioSource);
        mAudioTrack.setEnabled(true);

        /** ---------开始启动信令服务----------- */
        mIsServer = getIntent().getBooleanExtra("server", false);

        if (mIsServer) {
            mServer = new SignalServer(port);
            mServer.start();
        }
        else {
            try {
                mClient = new SignalClient(new URI( address + ":" + port ));
                mClient.connect();
            } catch (URISyntaxException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 开始采集并本地显示
        mVideoCapturer.startCapture(VIDEO_RESOLUTION_WIDTH, VIDEO_RESOLUTION_HEIGHT, VIDEO_FPS);
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            // 停止采集
            mVideoCapturer.stopCapture();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    // 注意这里退出时的销毁动作
    @Override
    protected void onDestroy() {
        super.onDestroy();
        doLeave();
        mLocalSurfaceView.release();
        mRemoteSurfaceView.release();

        mVideoCapturer.dispose();
        mSurfaceTextureHelper.dispose();

        PeerConnectionFactory.stopInternalTracingCapture();
        PeerConnectionFactory.shutdownInternalTracer();
        mPeerConnectionFactory.dispose();
    }

    public static class SimpleSdpObserver implements SdpObserver {
        @Override
        public void onCreateSuccess(SessionDescription sessionDescription) {
            Logger.d( "SdpObserver: onCreateSuccess !");
        }

        @Override
        public void onSetSuccess() {
            Logger.d("SdpObserver: onSetSuccess");
        }

        @Override
        public void onCreateFailure(String msg) {
            Logger.d( "SdpObserver onCreateFailure: " + msg);
        }

        @Override
        public void onSetFailure(String msg) {
            Logger.d("SdpObserver onSetFailure: " + msg);
        }
    }

    private void updateCallState(boolean idle) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (idle) {
                    mRemoteSurfaceView.setVisibility(View.GONE);
                } else {
                    mRemoteSurfaceView.setVisibility(View.VISIBLE);
                }
            }
        });
    }

    /**
     * 有其他用户连进来，
     */
    public void doStartCall(WebSocket conn) {
        printInfoOnScreen("Start Call, Wait ...");
        if (mPeerConnection == null) {
            mPeerConnection = createPeerConnection();
        }
        MediaConstraints mediaConstraints = new MediaConstraints();
        mediaConstraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true")); // 接收远端音频
        mediaConstraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true")); // 接收远端视频
        mediaConstraints.optional.add(new MediaConstraints.KeyValuePair("DtlsSrtpKeyAgreement", "true"));
        mPeerConnection.createOffer(new SimpleSdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription sessionDescription) {
                Logger.d("Create local offer success: \n" + sessionDescription.description);
                mPeerConnection.setLocalDescription(new SimpleSdpObserver(), sessionDescription);
                JSONObject message = new JSONObject();
                try {
                    message.put("type", "offer");
                    message.put("sdp", sessionDescription.description);
                    conn.send(message.toString());
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }
        }, mediaConstraints);
    }

    public void doAnswerCall() {
        printInfoOnScreen("Answer Call, Wait ...");

        if (mPeerConnection == null) {
            mPeerConnection = createPeerConnection();
        }

        MediaConstraints sdpMediaConstraints = new MediaConstraints();
        Logger.d("Create answer ...");
        mPeerConnection.createAnswer(new SimpleSdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription sessionDescription) {
                Logger.d("Create answer success !");
                mPeerConnection.setLocalDescription(new SimpleSdpObserver(),
                        sessionDescription);

                JSONObject message = new JSONObject();
                try {
                    message.put("type", "answer");
                    message.put("sdp", sessionDescription.description);
                    sendMessage(message.toString());
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }
        }, sdpMediaConstraints);
        updateCallState(false);
    }

    public void doLeave() {
        printInfoOnScreen("Leave room, Wait ...");
        printInfoOnScreen("Hangup Call, Wait ...");
        if (mPeerConnection == null) {
            return;
        }
        mPeerConnection.close();
        mPeerConnection = null;
        printInfoOnScreen("Hangup Done.");
        updateCallState(true);
    }

    public PeerConnection createPeerConnection() {
        Logger.d("Create PeerConnection ...");

        LinkedList<PeerConnection.IceServer> iceServers = new LinkedList<PeerConnection.IceServer>();

        // 设置ICE服务器
        PeerConnection.IceServer ice_server =
                PeerConnection.IceServer.builder("turn:xxxx:3478")
                        .setPassword("xxx")
                        .setUsername("xxx")
                        .createIceServer();

        iceServers.add(ice_server);

        PeerConnection.RTCConfiguration rtcConfig = new PeerConnection.RTCConfiguration(iceServers);
        // TCP candidates are only useful when connecting to a server that supports
        // ICE-TCP.
        rtcConfig.tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED; // 不要使用TCP
        rtcConfig.bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE; // max-bundle表示音视频都绑定到同一个传输通道
        rtcConfig.rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE; // 只收集RTCP和RTP复用的ICE候选者，如果RTCP不能复用，就失败
        rtcConfig.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY;
        //rtcConfig.iceCandidatePoolSize = 10;
        rtcConfig.iceTransportsType = PeerConnection.IceTransportsType.ALL;

        // Use ECDSA encryption.
        //rtcConfig.keyType = PeerConnection.KeyType.ECDSA;
        // Enable DTLS for normal calls and disable for loopback calls.
        rtcConfig.enableDtlsSrtp = true;
        //rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        PeerConnection connection =
                mPeerConnectionFactory.createPeerConnection(rtcConfig,
                        mPeerConnectionObserver); // PC的observer
        if (connection == null) {
            Logger.d( "Failed to createPeerConnection !");
            return null;
        }

        List<String> mediaStreamLabels = Collections.singletonList("ARDAMS");
        connection.addTrack(mVideoTrack, mediaStreamLabels);
        connection.addTrack(mAudioTrack, mediaStreamLabels);

        return connection;
    }

    public PeerConnectionFactory createPeerConnectionFactory(Context context) {
        final VideoEncoderFactory encoderFactory;
        final VideoDecoderFactory decoderFactory;

        encoderFactory = new DefaultVideoEncoderFactory(
                mRootEglBase.getEglBaseContext(),
                false /* enableIntelVp8Encoder */,
                true);
        decoderFactory = new DefaultVideoDecoderFactory(mRootEglBase.getEglBaseContext());

        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(true)
                .createInitializationOptions());

        PeerConnectionFactory.Builder builder = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(encoderFactory)
                .setVideoDecoderFactory(decoderFactory);
        builder.setOptions(null);

        return builder.createPeerConnectionFactory();
    }

    /*
     * Read more about Camera2 here
     * https://developer.android.com/reference/android/hardware/camera2/package-summary.html
     **/
    private VideoCapturer createVideoCapturer() {
        if (Camera2Enumerator.isSupported(this)) {
            return createCameraCapturer(new Camera2Enumerator(this));
        } else {
            return createCameraCapturer(new Camera1Enumerator(true));
        }
    }

    private VideoCapturer createCameraCapturer(CameraEnumerator enumerator) {
        final String[] deviceNames = enumerator.getDeviceNames();

        // First, try to find front facing camera
        Logger.d("Looking for front facing cameras.");
        for (String deviceName : deviceNames) {
            if (enumerator.isBackFacing(deviceName)) {
                Logger.d( "Creating front facing camera capturer.");
                VideoCapturer videoCapturer = enumerator.createCapturer(deviceName, null);
                if (videoCapturer != null) {
                    return videoCapturer;
                }
            }
        }

        // Front facing camera not found, try something else
        Logger.d("Looking for other cameras.");
        for (String deviceName : deviceNames) {
            if (!enumerator.isBackFacing(deviceName)) {
                Logger.d("Creating other camera capturer.");
                VideoCapturer videoCapturer = enumerator.createCapturer(deviceName, null);
                if (videoCapturer != null) {
                    return videoCapturer;
                }
            }
        }
        return null;
    }

    private PeerConnection.Observer mPeerConnectionObserver = new PeerConnection.Observer() {
        @Override
        public void onSignalingChange(PeerConnection.SignalingState signalingState) {
            Logger.d("onSignalingChange: " + signalingState);
        }

        @Override
        public void onIceConnectionChange(PeerConnection.IceConnectionState iceConnectionState) {
            Logger.d("onIceConnectionChange: " + iceConnectionState);
        }

        @Override
        public void onIceConnectionReceivingChange(boolean b) {
            Logger.d("onIceConnectionChange: " + b);
        }

        @Override
        public void onIceGatheringChange(PeerConnection.IceGatheringState iceGatheringState) {
            Logger.d("onIceGatheringChange: " + iceGatheringState);
        }

        @Override
        public void onIceCandidate(IceCandidate iceCandidate) {
            Logger.d("onIceCandidate: " + iceCandidate);
            // 得到candidate，就发送给信令服务器
            try {
                JSONObject message = new JSONObject();
                //message.put("userId", RTCWebRTCSignalClient.getInstance().getUserId());
                message.put("type", "candidate");
                message.put("label", iceCandidate.sdpMLineIndex);
                message.put("id", iceCandidate.sdpMid);
                message.put("candidate", iceCandidate.sdp);
                sendMessage(message);
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onIceCandidatesRemoved(IceCandidate[] iceCandidates) {
            for (int i = 0; i < iceCandidates.length; i++) {
                Logger.d("onIceCandidatesRemoved: " + iceCandidates[i]);
            }
            mPeerConnection.removeIceCandidates(iceCandidates);
        }

        @Override
        public void onAddStream(MediaStream mediaStream) {
            Logger.d("onAddStream: " + mediaStream.videoTracks.size());
        }

        @Override
        public void onRemoveStream(MediaStream mediaStream) {
            Logger.d("onRemoveStream");
        }

        @Override
        public void onDataChannel(DataChannel dataChannel) {
            Logger.d("onDataChannel");
        }

        @Override
        public void onRenegotiationNeeded() {
            Logger.d("onRenegotiationNeeded");
        }

        // 收到了媒体流
        @Override
        public void onAddTrack(RtpReceiver rtpReceiver, MediaStream[] mediaStreams) {
            MediaStreamTrack track = rtpReceiver.track();
            if (track instanceof VideoTrack) {
                Logger.d("onAddVideoTrack");
                VideoTrack remoteVideoTrack = (VideoTrack) track;
                remoteVideoTrack.setEnabled(true);
                remoteVideoTrack.addSink(mRemoteSurfaceView);
            }
        }
    };

    private void sendMessage(JSONObject message) {
        if (mIsServer) {
            mServer.broadcast(message.toString());
        }
        else {
            mClient.send(message.toString());
        }
    }

    private void sendMessage(String message) {
        if (mIsServer) {
            mServer.broadcast(message);
        }
        else {
            mClient.send(message);
        }
    }

    class SignalServer extends WebSocketServer {

        public SignalServer( int port ) {
            super(new InetSocketAddress(port));
        }

        @Override
        public void onOpen(WebSocket conn, ClientHandshake handshake) {
            Logger.d("=== SignalServer onOpen()");
            printInfoOnScreen("onOpen有客户端连接上...调用start call");
            //调用call， 进行媒体协商
            doStartCall(conn);
        }

        @Override
        public void onClose(WebSocket conn, int code, String reason, boolean remote) {
            Logger.d("=== SignalServer onClose() reason="+reason+", remote="+remote);
            printInfoOnScreen("onClose客户端断开...调用doLeave，reason="+reason);
            doLeave();
        }

        @Override
        public void onMessage(WebSocket conn, String message) {
            Logger.d("=== SignalServer onMessage() message="+message);
            try {
                JSONObject jsonMessage = new JSONObject(message);

                String type = jsonMessage.getString("type");
                if (type.equals("offer")) {
                    onRemoteOfferReceived(jsonMessage);
                }else if(type.equals("answer")) {
                    onRemoteAnswerReceived(jsonMessage);
                }else if(type.equals("candidate")) {
                    onRemoteCandidateReceived(jsonMessage);
                }else{
                    Logger.e("the type is invalid: " + type);
                }
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onError(WebSocket conn, Exception ex) {
            ex.printStackTrace();
            Logger.e("=== SignalServer onMessage() ex="+ex.getMessage());
        }

        @Override
        public void onStart() {
            Logger.d("=== SignalServer onStart()");
            setConnectionLostTimeout(0);
            setConnectionLostTimeout(100);

            printInfoOnScreen("onStart服务端建立成功...创建PC");
            //这里应该创建PeerConnection
            if (mPeerConnection == null) {
                mPeerConnection = createPeerConnection();
            }
        }
    }

    class SignalClient extends WebSocketClient {

        public SignalClient(URI serverUri) {
            super(serverUri);
        }

        @Override
        public void onOpen(ServerHandshake handshakedata) {
            Logger.d("=== SignalClient onOpen()");
            printInfoOnScreen("连接服务端成功...创建PC");
            //这里应该创建PeerConnection
            if (mPeerConnection == null) {
                mPeerConnection = createPeerConnection();
            }
        }

        @Override
        public void onMessage(final String message) {
            Logger.d("=== SignalClient onMessage(): message="+message);
            try {
                JSONObject jsonMessage = new JSONObject(message);

                String type = jsonMessage.getString("type");
                if (type.equals("offer")) {
                    onRemoteOfferReceived(jsonMessage);
                }else if(type.equals("answer")) {
                    onRemoteAnswerReceived(jsonMessage);
                }else if(type.equals("candidate")) {
                    onRemoteCandidateReceived(jsonMessage);
                }else{
                    Logger.e("the type is invalid: " + type);
                }
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onClose(int code, String reason, boolean remote) {
            Logger.d("=== SignalClient onClose(): reason="+reason+", remote="+remote);
            printInfoOnScreen("和服务端断开...调用doLeave");
            doLeave();
        }

        @Override
        public void onError(Exception ex) {
            ex.printStackTrace();
            Logger.d("=== SignalClient onMessage() ex="+ex.getMessage());
        }
    }

    // 接听方，收到offer
    private void onRemoteOfferReceived(JSONObject message) {
        printInfoOnScreen("Receive Remote Call ...");

        if (mPeerConnection == null) {
            mPeerConnection = createPeerConnection();
        }

        try {
            String description = message.getString("sdp");
            mPeerConnection.setRemoteDescription(
                    new SimpleSdpObserver(),
                    new SessionDescription(
                            SessionDescription.Type.OFFER,
                            description));
            printInfoOnScreen("收到offer...调用doAnswerCall");
            doAnswerCall();
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    // 发送方，收到answer
    private void onRemoteAnswerReceived(JSONObject message) {
        printInfoOnScreen("Receive Remote Answer ...");
        try {
            String description = message.getString("sdp");
            mPeerConnection.setRemoteDescription(
                    new SimpleSdpObserver(),
                    new SessionDescription(
                            SessionDescription.Type.ANSWER,
                            description));
        } catch (JSONException e) {
            e.printStackTrace();
        }
        printInfoOnScreen("收到answer.....");
        updateCallState(false);
    }

    // 收到对端发过来的candidate
    private void onRemoteCandidateReceived(JSONObject message) {
        printInfoOnScreen("Receive Remote Candidate ...");
        try {
            // candidate 候选者描述信息
            // sdpMid 与候选者相关的媒体流的识别标签
            // sdpMLineIndex 在SDP中m=的索引值
            // usernameFragment 包括了远端的唯一识别
            IceCandidate remoteIceCandidate =
                    new IceCandidate(message.getString("id"),
                            message.getInt("label"),
                            message.getString("candidate"));

            printInfoOnScreen("收到Candidate.....");
            mPeerConnection.addIceCandidate(remoteIceCandidate);
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    private void onRemoteHangup() {
        printInfoOnScreen("Receive Remote Hangup Event ...");
        doLeave();
    }

    private void printInfoOnScreen(String msg) {
        Logger.d(msg);
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                String output = mLogcatView.getText() + "\n" + msg;
                mLogcatView.setText(output);
            }
        });
    }


}
