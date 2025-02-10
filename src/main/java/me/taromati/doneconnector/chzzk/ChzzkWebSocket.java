package me.taromati.doneconnector.chzzk;

import lombok.Getter;
import me.taromati.doneconnector.DoneConnector;
import me.taromati.doneconnector.Logger;
import me.taromati.doneconnector.metrics.MetricsCollector;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class ChzzkWebSocket extends WebSocketClient {
    private static final int MAX_RECONNECT_ATTEMPTS = 5;
    private static final int RECONNECT_DELAY_MS = 5000;
    private static final int INITIAL_BACKOFF_MS = 1000;
    private static final int MAX_BACKOFF_MS = 30000;
    private static final int CONNECTION_TIMEOUT_MS = 30000;
    private static final int MAX_MESSAGE_QUEUE_SIZE = 1000;
    private static final int COMMAND_EXECUTION_TIMEOUT = 5000;

    private final String chatChannelId;
    private final String accessToken;
    private final String extraToken;
    @Getter
    private final Map<String, String> chzzkUser;
    private final HashMap<Integer, List<String>> donationRewards;
    private final MetricsCollector metricsCollector;
    
    private final ScheduledExecutorService scheduler;
    private final BlockingQueue<String> messageQueue;
    private final AtomicInteger reconnectAttempts;
    private final AtomicLong messageCounter;
    private final Random random;
    private final Object connectionLock;
    
    private volatile ConnectionState connectionState;
    private volatile boolean isAlive;
    private Thread pingThread;
    private Thread messageProcessingThread;

    private static final int CHZZK_CHAT_CMD_PING = 0;
    private static final int CHZZK_CHAT_CMD_PONG = 10000;
    private static final int CHZZK_CHAT_CMD_CONNECT = 100;
    private static final int CHZZK_CHAT_CMD_CONNECT_RES = 10100;
    private static final int CHZZK_CHAT_CMD_CHAT = 93101;
    private static final int CHZZK_CHAT_CMD_DONATION = 93102;

    private final JSONParser jsonParser = new JSONParser();

    enum ConnectionState {
        CONNECTED, DISCONNECTED, CONNECTING, RECONNECTING
    }

    @Override
    public void send(String message) {
        try {
            super.send(message);
        } catch (Exception e) {
            Logger.error("[ChzzkWebsocket][" + chzzkUser.get("nickname") + 
                        "] 메시지 전송 실패: " + e.getMessage());
            synchronized (connectionLock) {
                connectionState = ConnectionState.DISCONNECTED;
                attemptReconnect();
            }
        }
    }

    public ChzzkWebSocket(String serverUri, String chatChannelId, String accessToken, 
                         String extraToken, Map<String, String> chzzkUser,
                         HashMap<Integer, List<String>> donationRewards) {
        super(URI.create(serverUri));
        
        this.chatChannelId = chatChannelId;
        this.accessToken = accessToken;
        this.extraToken = extraToken;
        this.chzzkUser = chzzkUser;
        this.donationRewards = donationRewards;
        
        this.scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r);
            t.setName("ChzzkScheduler-" + chzzkUser.get("nickname"));
            return t;
        });
        this.messageQueue = new LinkedBlockingQueue<>(MAX_MESSAGE_QUEUE_SIZE);
        this.reconnectAttempts = new AtomicInteger(0);
        this.messageCounter = new AtomicLong(0);
        this.random = new Random();
        this.connectionLock = new Object();
        this.connectionState = ConnectionState.DISCONNECTED;
        this.isAlive = true;
        this.metricsCollector = new MetricsCollector(chzzkUser.get("nickname"));

        // 웹소켓 설정
        this.setConnectionLostTimeout(30);
        this.setConnectionLostTimeout(CONNECTION_TIMEOUT_MS / 1000); // milliseconds to seconds
        
        // 모니터링 시작
        startConnectionMonitoring();
        startMessageProcessing();
    }

    private void startConnectionMonitoring() {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                if (connectionState == ConnectionState.DISCONNECTED) {
                    attemptReconnect();
                }
                metricsCollector.updateMetrics(
                    connectionState.toString(),
                    reconnectAttempts.get(),
                    messageCounter.get(),
                    messageQueue.size()
                );
            } catch (Exception e) {
                Logger.error("[ChzzkWebsocket][" + chzzkUser.get("nickname") + 
                           "] 모니터링 중 오류 발생: " + e.getMessage());
            }
        }, 30, 30, TimeUnit.SECONDS);
    }

    private void startMessageProcessing() {
        messageProcessingThread = new Thread(() -> {
            while (isAlive) {
                try {
                    String message = messageQueue.poll(1, TimeUnit.SECONDS);
                    if (message != null) {
                        processMessage(message);
                    }
                } catch (InterruptedException e) {
                    Logger.info("[ChzzkWebsocket][" + chzzkUser.get("nickname") + 
                            "] 메시지 처리 스레드가 종료됩니다.");
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    Logger.error("[ChzzkWebsocket][" + chzzkUser.get("nickname") + 
                               "] 메시지 처리 중 오류 발생: " + e.getMessage());
                }
            }
        }, "ChzzkMessageProcessor-" + chzzkUser.get("nickname"));
        messageProcessingThread.start();
    }

    private void attemptReconnect() {
        synchronized (connectionLock) {
            if (connectionState != ConnectionState.DISCONNECTED || 
                reconnectAttempts.get() >= MAX_RECONNECT_ATTEMPTS) {
                return;
            }

            connectionState = ConnectionState.RECONNECTING;
            int attempts = reconnectAttempts.incrementAndGet();
            
            // 지수 백오프 계산
            int backoffMs = Math.min(
                INITIAL_BACKOFF_MS * (1 << attempts),
                MAX_BACKOFF_MS
            );
            
            Logger.info(ChatColor.YELLOW + "[ChzzkWebsocket][" + chzzkUser.get("nickname") + 
                       "] 재연결 시도 중... (" + attempts + "/" + MAX_RECONNECT_ATTEMPTS + 
                       ") 대기 시간: " + backoffMs + "ms");

            scheduler.schedule(() -> {
                try {
                    if (reconnectBlocking()) {
                        connectionState = ConnectionState.CONNECTING;
                    } else {
                        connectionState = ConnectionState.DISCONNECTED;
                        attemptReconnect();
                    }
                } catch (Exception e) {
                    Logger.error("[ChzzkWebsocket][" + chzzkUser.get("nickname") + 
                               "] 재연결 시도 중 오류 발생: " + e.getMessage());
                    connectionState = ConnectionState.DISCONNECTED;
                }
            }, backoffMs, TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public void onOpen(ServerHandshake handshakedata) {
        synchronized (connectionLock) {
            connectionState = ConnectionState.CONNECTED;
            reconnectAttempts.set(0);
            
            Logger.info(ChatColor.GREEN + "[ChzzkWebsocket][" + chzzkUser.get("nickname") + 
                       "] 치지직 웹소켓 연결이 성공했습니다.");
            
            sendAuthenticationMessage();
            startPingThread();
        }
    }

    private void sendAuthenticationMessage() {
    int maxRetries = 3;
    int retryCount = 0;
    
    while (retryCount < maxRetries && connectionState == ConnectionState.CONNECTED) {
        try {
            JSONObject baseObject = new JSONObject();
            baseObject.put("ver", "2");
            baseObject.put("svcid", "game");
            baseObject.put("cid", this.chatChannelId);
            
            JSONObject sendObject = new JSONObject(baseObject);
            sendObject.put("cmd", CHZZK_CHAT_CMD_CONNECT);
            sendObject.put("tid", 1);
            
            JSONObject bdyObject = new JSONObject();
            bdyObject.put("uid", null);
            bdyObject.put("devType", 2001);
            bdyObject.put("accTkn", this.accessToken);
            bdyObject.put("auth", "READ");
            
            sendObject.put("bdy", bdyObject);
            
            send(sendObject.toJSONString());
            return;
        } catch (Exception e) {
            retryCount++;
            if (retryCount >= maxRetries) {
                Logger.error("[ChzzkWebsocket][" + chzzkUser.get("nickname") + 
                           "] 인증 메시지 전송 실패: " + e.getMessage());
                break;
            }
            Logger.warn("[ChzzkWebsocket][" + chzzkUser.get("nickname") + 
                       "] 인증 메시지 전송 재시도 중... (" + retryCount + "/" + maxRetries + ")");
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    // 모든 재시도 실패 시 재연결 시도
    if (retryCount >= maxRetries) {
        synchronized (connectionLock) {
            connectionState = ConnectionState.DISCONNECTED;
            attemptReconnect();
        }
    }
}

    @Override
    public void onMessage(String message) {
        try {
            if (!messageQueue.offer(message)) {
                Logger.warn("[ChzzkWebsocket][" + chzzkUser.get("nickname") + 
                          "] 메시지 큐가 가득 찼습니다. 메시지 드롭됨.");
                return;
            }
            messageCounter.incrementAndGet();
        } catch (Exception e) {
            Logger.error("[ChzzkWebsocket][" + chzzkUser.get("nickname") + 
                        "] 메시지 큐잉 중 오류 발생: " + e.getMessage());
        }
    }

    private void processMessage(String message) {
        try {
            JSONObject messageObject = (JSONObject) jsonParser.parse(message);
            int cmd = Integer.parseInt(messageObject.get("cmd").toString());

            switch (cmd) {
                case CHZZK_CHAT_CMD_PING:
                    handlePing();
                    break;
                case CHZZK_CHAT_CMD_PONG:
                    Logger.debug("[ChzzkWebsocket][" + chzzkUser.get("nickname") + "] pong");
                    break;
                case CHZZK_CHAT_CMD_DONATION:
                    handleDonation(messageObject);
                    break;
                case CHZZK_CHAT_CMD_CHAT:
                    handleChat(messageObject);
                    break;
                default:
                    Logger.debug("[ChzzkWebsocket][" + chzzkUser.get("nickname") + 
                               "] 알 수 없는 명령어: " + cmd);
            }
        } catch (ParseException e) {
            Logger.error("[ChzzkWebsocket][" + chzzkUser.get("nickname") + 
                        "] 메시지 파싱 중 오류 발생: " + e.getMessage());
        }
    }

    private void handlePing() {
        JSONObject pongObject = new JSONObject();
        pongObject.put("cmd", CHZZK_CHAT_CMD_PONG);
        pongObject.put("ver", 2);
        send(pongObject.toJSONString());
        Logger.debug("[ChzzkWebsocket][" + chzzkUser.get("nickname") + "] ping");
    }

    private void handleDonation(JSONObject messageObject) {
        try {
            JSONObject bdyObject = (JSONObject) ((JSONArray) messageObject.get("bdy")).get(0);
            String uid = (String) bdyObject.get("uid");
            String msg = (String) bdyObject.get("msg");
            String nickname = "익명";

            if (!Objects.equals(uid, "anonymous")) {
                String profile = (String) bdyObject.get("profile");
                JSONObject profileObject = (JSONObject) jsonParser.parse(profile);
                nickname = (String) profileObject.get("nickname");
            }

            String extras = (String) bdyObject.get("extras");
            JSONObject extraObject = (JSONObject) jsonParser.parse(extras);

            if (extraObject.get("payAmount") == null) {
                return;
            }

            int payAmount = Integer.parseInt(extraObject.get("payAmount").toString());
            Logger.info(ChatColor.YELLOW + nickname + ChatColor.WHITE + "님께서 " + 
                       ChatColor.GREEN + payAmount + "원" + ChatColor.WHITE + "을 후원해주셨습니다.");

            processDonationRewards(nickname, payAmount, msg);

        } catch (Exception e) {
            Logger.error("[ChzzkWebsocket][" + chzzkUser.get("nickname") + 
                        "] 후원 처리 중 오류 발생: " + e.getMessage());
        }
    }

    private void processDonationRewards(String nickname, int payAmount, String msg) {
        List<String> commands = donationRewards.getOrDefault(payAmount, donationRewards.get(0));
        
        if (commands == null || commands.isEmpty()) {
            return;
        }

        if (DoneConnector.random) {
            String command = commands.get(random.nextInt(commands.size()));
            executeCommand(chzzkUser.get("tag"), nickname, payAmount, msg, command);
        } else {
            for (String command : commands) {
                executeCommand(chzzkUser.get("tag"), nickname, payAmount, msg, command);
            }
        }
    }

    private void handleChat(JSONObject messageObject) {
        try {
            JSONObject bdyObject = (JSONObject) ((JSONArray) messageObject.get("bdy")).get(0);
            String uid = (String) bdyObject.get("uid");
            String message = (String) bdyObject.get("msg");
            
            if (message == null || message.trim().isEmpty()) {
                return;
            }

            String nickname = "익명";
            if (!Objects.equals(uid, "anonymous")) {
                String profile = (String) bdyObject.get("profile");
                JSONObject profileObject = (JSONObject) jsonParser.parse(profile);
                nickname = (String) profileObject.get("nickname");
            }

            Logger.debug("[ChzzkWebsocket][" + chzzkUser.get("nickname") + "] " + 
                        nickname + ": " + message);

        } catch (Exception e) {
            Logger.error("[ChzzkWebsocket][" + chzzkUser.get("nickname") + 
                        "] 채팅 메시지 처리 중 오류 발생: " + e.getMessage());
        }
    }

    private void executeCommand(String tag, String nickname, int payAmount, 
                              String msg, String command) {
        String[] commandArray = command.split(";");

        for (String cmd : commandArray) {
            String finalCommand = cmd
                .replaceAll("%tag%", tag)
                .replaceAll("%name%", nickname)
                .replaceAll("%amount%", String.valueOf(payAmount))
                .replaceAll("%message%", msg);

            try {
                Bukkit.getScheduler()
                    .callSyncMethod(DoneConnector.plugin, () -> 
                        Bukkit.dispatchCommand(
                            Bukkit.getConsoleSender(), 
                            finalCommand
                        )
                    ).get(COMMAND_EXECUTION_TIMEOUT, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                Logger.error("[ChzzkWebsocket][" + chzzkUser.get("nickname") + 
                           "] 명령어 실행 시간 초과: " + finalCommand);
            } catch (Exception e) {
                Logger.error("[ChzzkWebsocket][" + chzzkUser.get("nickname") + 
                           "] 명령어 실행 중 오류 발생: " + e.getMessage());
            }
        }
    }

    private void startPingThread() {
        if (pingThread != null && pingThread.isAlive()) {
            pingThread.interrupt();
        }

        pingThread = new Thread(() -> {
            while (isAlive && connectionState == ConnectionState.CONNECTED) {
                try {
                    Thread.sleep(19996);
                    if (connectionState == ConnectionState.CONNECTED) {
                        JSONObject pongObject = new JSONObject();
                        pongObject.put("cmd", CHZZK_CHAT_CMD_PONG);
                        pongObject.put("ver", 2);
                        send(pongObject.toJSONString());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    Logger.error("[ChzzkWebsocket][" + chzzkUser.get("nickname") + 
                               "] 핑 메시지 전송 중 오류 발생: " + e.getMessage());
                }
            }
        }, "ChzzkPingThread-" + chzzkUser.get("nickname"));
        
        pingThread.setDaemon(true);
        pingThread.start();
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        synchronized (connectionLock) {
            Logger.info(ChatColor.RED + "[ChzzkWebsocket][" + chzzkUser.get("nickname") + 
                       "] 웹소켓 연결이 종료되었습니다. (코드: " + code + ", 사유: " + reason + ")");
            
            connectionState = ConnectionState.DISCONNECTED;
            
            if (pingThread != null) {
                pingThread.interrupt();
                pingThread = null;
            }
            
            // 즉시 재연결 시도
            if (reconnectAttempts.get() < MAX_RECONNECT_ATTEMPTS) {
                scheduler.schedule(this::attemptReconnect, 
                                RECONNECT_DELAY_MS, TimeUnit.MILLISECONDS);
            }
        }
    }

    @Override
    public void onError(Exception ex) {
        Logger.error("[ChzzkWebsocket][" + chzzkUser.get("nickname") + 
                    "] 웹소켓 에러 발생: " + ex.getMessage());
        
        synchronized (connectionLock) {
            if (connectionState == ConnectionState.CONNECTED) {
                connectionState = ConnectionState.DISCONNECTED;
                attemptReconnect();
            }
        }
    }

    public void shutdown() {
        isAlive = false;
        connectionState = ConnectionState.DISCONNECTED;  // 추가 필요
        
        if (pingThread != null) {
            pingThread.interrupt();
            pingThread = null;
        }
        
        if (messageProcessingThread != null) {
            messageProcessingThread.interrupt();
            messageProcessingThread = null;
        }
        
        // 스레드 풀 종료 시 타임아웃 증가 필요
        try {
            scheduler.shutdown();
            if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {  // 5초에서 10초로 증가
                scheduler.shutdownNow();
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    Logger.error("[ChzzkWebsocket][" + chzzkUser.get("nickname") + 
                            "] 스케줄러가 정상적으로 종료되지 않았습니다.");
                }
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        try {
            this.closeBlocking();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        metricsCollector.shutdown();
    }

    // MetricsCollector 내부 클래스
    private static class MetricsCollector {
        private final String nickname;
        private final Map<String, Object> metrics = new ConcurrentHashMap<>();
        
        public MetricsCollector(String nickname) {
            this.nickname = nickname;
        }
        
        public void updateMetrics(String connectionState, int reconnectAttempts, 
                                long messageCount, int queueSize) {
            metrics.put("connectionState", connectionState);
            metrics.put("reconnectAttempts", reconnectAttempts);
            metrics.put("messageCount", messageCount);
            metrics.put("queueSize", queueSize);
            metrics.put("lastUpdated", System.currentTimeMillis());
        }
        
        public Map<String, Object> getMetrics() {
            return new HashMap<>(metrics);
        }
        
        public void shutdown() {
            metrics.clear();
        }
    }
}



