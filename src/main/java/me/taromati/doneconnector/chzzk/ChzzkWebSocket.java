package me.taromati.doneconnector.chzzk;

import lombok.Getter;
import me.taromati.doneconnector.DoneConnector;
import me.taromati.doneconnector.Logger;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class ChzzkWebSocket extends WebSocketClient {
    private static final int MAX_RECONNECT_ATTEMPTS = 5;
    private static final int RECONNECT_DELAY_MS = 5000;
    private static final int INITIAL_BACKOFF_MS = 1000;
    private static final int MAX_BACKOFF_MS = 30000;
    private static final int CONNECTION_TIMEOUT_MS = 30000;
    private static final int COMMAND_EXECUTION_TIMEOUT = 5000;

    private final String chatChannelId;
    private final String accessToken;
    private final String extraToken;
    @Getter
    private final Map<String, String> chzzkUser;
    private final HashMap<Integer, List<String>> donationRewards;
    private final MetricsCollector metricsCollector;
    
    private final ScheduledExecutorService scheduler;
    private final AtomicInteger reconnectAttempts;
    private final Random random;
    private final Object connectionLock;
    
    private volatile ConnectionState connectionState;
    private volatile boolean isAlive;
    private Thread pingThread;

    private static final int CHZZK_CHAT_CMD_PING = 0;
    private static final int CHZZK_CHAT_CMD_PONG = 10000;
    private static final int CHZZK_CHAT_CMD_CONNECT = 100;
    private static final int CHZZK_CHAT_CMD_CONNECT_RES = 10100;
    private static final int CHZZK_CHAT_CMD_CHAT = 93101;
    private static final int CHZZK_CHAT_CMD_DONATION = 93102;

    private final JSONParser jsonParser = new JSONParser();

    private volatile boolean isShuttingDown = false;
    private final Set<Future<?>> pendingTasks = ConcurrentHashMap.newKeySet();

    enum ConnectionState {
        CONNECTED, DISCONNECTED, CONNECTING, RECONNECTING
    }

    public ChzzkWebSocket(String serverUri, String chatChannelId, String accessToken, 
                        String extraToken, Map<String, String> chzzkUser,
                        HashMap<Integer, List<String>> donationRewards,
                        ScheduledExecutorService sharedScheduler) {
        super(URI.create(serverUri));
        
        this.chatChannelId = chatChannelId;
        this.accessToken = accessToken;
        this.extraToken = extraToken;
        this.chzzkUser = chzzkUser;
        this.donationRewards = donationRewards;
        
        this.scheduler = sharedScheduler;  // 공유 스케줄러 사용
        this.reconnectAttempts = new AtomicInteger(0);
        this.random = new Random();
        this.connectionLock = new Object();
        this.connectionState = ConnectionState.DISCONNECTED;
        this.isAlive = true;
        this.metricsCollector = new MetricsCollector(chzzkUser.get("nickname"));

        // 웹소켓 설정
        this.setConnectionLostTimeout(CONNECTION_TIMEOUT_MS / 1000);
        
        // 모니터링 시작
        startConnectionMonitoring();
    }

    private void startConnectionMonitoring() {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                if (connectionState == ConnectionState.DISCONNECTED) {
                    attemptReconnect();
                }
                metricsCollector.updateMetrics(
                    connectionState.toString(),
                    reconnectAttempts.get()
                );
            } catch (Exception e) {
                Logger.error("[ChzzkWebsocket][" + chzzkUser.get("nickname") + 
                           "] 모니터링 중 오류 발생: " + e.getMessage());
            }
        }, 30, 30, TimeUnit.SECONDS);
    }

    private void attemptReconnect() {
        synchronized (connectionLock) {
            if (connectionState!= ConnectionState.DISCONNECTED ||
                    reconnectAttempts.get() >= MAX_RECONNECT_ATTEMPTS) {
                return;
            }

            // reload 시에만 재연결 시도
            if (!DoneConnector.isReloading) {
                return;
            }

            connectionState = ConnectionState.RECONNECTING;
            int attempts = reconnectAttempts.incrementAndGet();

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
        } catch (Exception e) {
            Logger.error("[ChzzkWebsocket][" + chzzkUser.get("nickname") + 
                        "] 인증 메시지 전송 실패: " + e.getMessage());
            synchronized (connectionLock) {
                connectionState = ConnectionState.DISCONNECTED;
                attemptReconnect();
            }
        }
    }

    // onMessage 메소드 수정
    @Override
    public void onMessage(String message) {
        if (isShuttingDown) {
            return;  // 종료 중이면 메시지 처리 방지
        }
        try {
            JSONObject messageObject = (JSONObject) jsonParser.parse(message);
            int cmd = Integer.parseInt(messageObject.get("cmd").toString());

            switch (cmd) {
                case CHZZK_CHAT_CMD_PING:
                    handlePing();
                    break;
                case CHZZK_CHAT_CMD_PONG:
                    // pong 로그 제거
                    break;
                case CHZZK_CHAT_CMD_CONNECT_RES:
                    // 연결 응답 로그 제거
                    break;
                case CHZZK_CHAT_CMD_DONATION:
                    handleDonation(messageObject);
                    break;
                case CHZZK_CHAT_CMD_CHAT:
                    handleChat(messageObject);
                    break;
                default:
                    Logger.debug("[ChzzkWebsocket][" + chzzkUser.get("nickname") + "] 알 수 없는 명령어: " + cmd);
            }
        } catch (Exception e) {
            Logger.error("[ChzzkWebsocket][" + chzzkUser.get("nickname") + "] 메시지 처리 중 오류 발생: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // handleDonation 메소드 수정
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
            
            Object payAmountObj = extraObject.get("payAmount");
            if (payAmountObj == null) {
                return;
            }
            
            int payAmount = Integer.parseInt(payAmountObj.toString());
            
            List<String> commands = donationRewards.get(payAmount);
            if (commands == null) {
                commands = donationRewards.get(0);
            }
            
            if (commands == null || commands.isEmpty()) {
                return;
            }

            Logger.info(ChatColor.YELLOW + nickname + ChatColor.WHITE + "님께서 " + 
                    ChatColor.GREEN + payAmount + "원" + ChatColor.WHITE + "을 후원해주셨습니다.");
            
            if (DoneConnector.random) {
                String command = commands.get(random.nextInt(commands.size()));
                executeCommand(chzzkUser.get("tag"), nickname, payAmount, msg, command);
            } else {
                for (String command : commands) {
                    executeCommand(chzzkUser.get("tag"), nickname, payAmount, msg, command);
                }
            }
            
        } catch (Exception e) {
            Logger.error("[ChzzkWebsocket][" + chzzkUser.get("nickname") + "] 후원 처리 중 오류 발생: " + e.getMessage());
            e.printStackTrace();
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

            // 채팅 보낸 사람의 프로필 정보 파싱 
            String senderNickname = "익명";
            String userRole = "일반";
            String nickColorCode = "#FFFFFF";
            
            if (!Objects.equals(uid, "anonymous")) {
                Object profileObj = bdyObject.get("profile");
                JSONObject profileObject;
                
                if (profileObj instanceof String) {
                    profileObject = (JSONObject) jsonParser.parse((String) profileObj);
                } else if (profileObj instanceof JSONObject) {
                    profileObject = (JSONObject) profileObj;
                } else {
                    Logger.error("[ChzzkWebsocket] 알 수 없는 프로필 형식: " + profileObj);
                    return;
                }
                
                senderNickname = (String) profileObject.get("nickname");
                
                // 역할 및 색상 처리
                String userRoleCode = (String) profileObject.get("userRoleCode");
                Object streamingPropertyObj = profileObject.get("streamingProperty");
                
                if (userRoleCode != null) {
                    userRole = switch (userRoleCode) {
                        case "streaming_chat_manager" -> "매니저";
                        case "streamer" -> "스트리머";
                        default -> "시청자";
                    };
                }
                
                if (streamingPropertyObj != null) {
                    JSONObject streamingProperty;
                    if (streamingPropertyObj instanceof String) {
                        streamingProperty = (JSONObject) jsonParser.parse((String) streamingPropertyObj);
                    } else {
                        streamingProperty = (JSONObject) streamingPropertyObj;
                    }
                    
                    Object nicknameColor = streamingProperty.get("nicknameColor");
                    if (nicknameColor != null) {
                        if (nicknameColor instanceof String) {
                            JSONObject nicknameColorJson = (JSONObject) jsonParser.parse((String) nicknameColor);
                            nickColorCode = "#" + (String) nicknameColorJson.get("colorCode");
                        } else if (nicknameColor instanceof JSONObject) {
                            nickColorCode = "#" + (String) ((JSONObject) nicknameColor).get("colorCode");
                        }
                    }
                }
            }

            // 이모티콘 처리
            Object extrasObj = bdyObject.get("extras");
            String displayMessage = message;
            
            if (extrasObj != null) {
                JSONObject extraObject;
                if (extrasObj instanceof String) {
                    extraObject = (JSONObject) jsonParser.parse((String) extrasObj);
                } else {
                    extraObject = (JSONObject) extrasObj;
                }
                
                Object emojisObj = extraObject.get("emojis");
                if (emojisObj instanceof JSONObject) {
                    JSONObject emojis = (JSONObject) emojisObj;
                    for (Object key : emojis.keySet()) {
                        String emojiKey = (String) key;
                        String emojiPattern = "\\{:" + emojiKey + ":\\}+";
                        displayMessage = displayMessage.replaceAll(emojiPattern, ":" + emojiKey + ":");
                    }
                }
            }

            ChatColor nickColor = getChatColorFromHex(nickColorCode);
            
            // config.yml과 매핑된 값 가져오기
            String streamerNickname = chzzkUser.get("nickname");     // 스트리머 채널명
            String markNickname = chzzkUser.get("tag");             // 마크닉네임 (마인크래프트 ID)
            String channelId = chzzkUser.get("id");                 // 채널 식별자

            // 디버그용 정보 출력
            Logger.debug("[ChzzkWebsocket] 채팅 정보 - 채널ID: " + channelId + 
                        ", 스트리머: " + streamerNickname + 
                        ", 마크닉네임: " + markNickname);

            if (markNickname != null) {
                // 게임 내 채팅 메시지 구성
                String gameMessage = ChatColor.GRAY + "[치지직 | " + streamerNickname + "] " +
                                nickColor + senderNickname + ChatColor.WHITE + 
                                (userRole.equals("일반") ? "" : " (" + userRole + ")") + 
                                ": " + displayMessage;

                // 온라인 플레이어 중 마크닉네임이 일치하는 플레이어에게 메시지 전송
                boolean messageDelivered = false;
                for (Player player : Bukkit.getOnlinePlayers()) {
                    String playerName = player.getName();
                    
                    if (playerName.equalsIgnoreCase(markNickname)) {
                        player.sendMessage(gameMessage);
                        Logger.debug("[ChzzkWebsocket] 채팅 전송 성공 -> " + markNickname);
                        messageDelivered = true;
                        break;
                    }
                }

                if (!messageDelivered) {
                    Logger.debug("[ChzzkWebsocket] 대상 플레이어가 오프라인 상태: " + markNickname);
                }

                // 디스코드 브로드캐스트
                String discordMessage = "[치지직 | " + streamerNickname + 
                                    (userRole.equals("일반") ? "" : " | " + userRole) + "] " +
                                    senderNickname + ": " + displayMessage;
                
                Bukkit.getScheduler().runTask(DoneConnector.plugin, () -> 
                    Bukkit.dispatchCommand(
                        Bukkit.getConsoleSender(),
                        "discord broadcast " + discordMessage
                    )
                );
            } else {
                Logger.error("[ChzzkWebsocket] 마크닉네임 설정을 찾을 수 없음");
                Logger.error(" - 채널 식별자: " + channelId);
                Logger.error(" - 스트리머: " + streamerNickname);
            }

        } catch (Exception e) {
            Logger.error("[ChzzkWebsocket] 채팅 처리 중 오류 발생");
            Logger.error(" - 메시지: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private ChatColor getChatColorFromHex(String hexColor) {
        try {
            return switch (hexColor.toUpperCase()) {
                case "#CC000" -> ChatColor.RED;
                case "#0000CC" -> ChatColor.BLUE;
                case "#00CC00" -> ChatColor.GREEN;
                default -> ChatColor.WHITE;
            };
        } catch (Exception e) {
            return ChatColor.WHITE;
        }
    }

    // executeCommand 메소드 개선
    private void executeCommand(String tag, String nickname, int payAmount, 
                              String msg, String command) {
        if (isShuttingDown) {
            return;  // 종료 중이면 새로운 명령 실행 방지
        }

        String[] commandArray = command.split(";");
        for (String cmd : commandArray) {
            if (isShuttingDown) break;  // 실행 중 종료 감지

            String finalCommand = cmd
                .replaceAll("%tag%", tag)
                .replaceAll("%name%", nickname)
                .replaceAll("%amount%", String.valueOf(payAmount))
                .replaceAll("%message%", msg);

            try {
                Future<?> task = Bukkit.getScheduler()
                    .callSyncMethod(DoneConnector.plugin, () -> {
                        if (!isShuttingDown) {
                            return Bukkit.dispatchCommand(
                                Bukkit.getConsoleSender(), 
                                finalCommand
                            );
                        }
                        return false;
                    });
                
                pendingTasks.add(task);
                
                try {
                    task.get(COMMAND_EXECUTION_TIMEOUT, TimeUnit.MILLISECONDS);
                } finally {
                    pendingTasks.remove(task);
                }
                
            } catch (TimeoutException e) {
                Logger.error("[ChzzkWebsocket][" + chzzkUser.get("nickname") + 
                           "] 명령어 실행 시간 초과: " + finalCommand);
            } catch (Exception e) {
                Logger.error("[ChzzkWebsocket][" + chzzkUser.get("nickname") + 
                           "] 명령어 실행 중 오류 발생: " + e.getMessage());
            }
        }
    }

    // handlePing 메소드 수정
    private void handlePing() {
        JSONObject pongObject = new JSONObject();
        pongObject.put("cmd", CHZZK_CHAT_CMD_PONG);
        pongObject.put("ver", 2);
        send(pongObject.toJSONString());
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

            if (pingThread!= null) {
                pingThread.interrupt();
                pingThread = null;
            }

            // 자동 재연결 로직 복원
            if (isAlive) {
                attemptReconnect();
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
            } else if (connectionState == ConnectionState.CONNECTING) {
                // 첫 번째 연결 시도 실패 시에만 재연결 시도
                connectionState = ConnectionState.DISCONNECTED;
                attemptReconnect();
            }
        }
    }

    // shutdown 메소드 개선
    public void shutdown() {
        synchronized (connectionLock) {
            if (isShuttingDown) {
                return;
            }
            isShuttingDown = true;
            isAlive = false;
            
            try {
                // 1. 연결 중인 작업들 취소
                pendingTasks.forEach(task -> task.cancel(true));
                pendingTasks.clear();
                
                // 2. 핑 스레드 종료
                if (pingThread != null) {
                    pingThread.interrupt();
                    try {
                        pingThread.join(2000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    pingThread = null;
                }
                
                // 3. 웹소켓 종료
                this.close(1000, "Shutdown requested");
                CountDownLatch closeLatch = new CountDownLatch(1);
                
                Thread closeThread = new Thread(() -> {
                    try {
                        if (this.isOpen()) {
                            this.closeBlocking();
                        }
                    } catch (Exception e) {
                        Logger.error("[ChzzkWebsocket][" + chzzkUser.get("nickname") + 
                                "] 종료 중 오류: " + e.getMessage());
                    } finally {
                        closeLatch.countDown();
                    }
                });
                closeThread.setDaemon(true);
                closeThread.start();
                
                // 5초 타임아웃으로 종료 대기
                if (!closeLatch.await(5, TimeUnit.SECONDS)) {
                    Logger.warn("[ChzzkWebsocket][" + chzzkUser.get("nickname") + 
                            "] 웹소켓 종료 타임아웃");
                }
                
            } catch (Exception e) {
                Logger.error("[ChzzkWebsocket][" + chzzkUser.get("nickname") + 
                        "] 종료 중 오류: " + e.getMessage());
            } finally {
                connectionState = ConnectionState.DISCONNECTED;
                metricsCollector.shutdown();
            }
        }
    }

    // MetricsCollector 클래스 개선
    private static class MetricsCollector {
        private final String nickname;
        private final Map<String, Object> metrics = new ConcurrentHashMap<>();
        private volatile boolean isShutdown = false;
        
        public MetricsCollector(String nickname) {
            this.nickname = nickname;
        }
        
        public void updateMetrics(String connectionState, int reconnectAttempts) {
            if (!isShutdown) {
                metrics.put("connectionState", connectionState);
                metrics.put("reconnectAttempts", reconnectAttempts);
                metrics.put("lastUpdated", System.currentTimeMillis());
            }
        }
        
        public Map<String, Object> getMetrics() {
            return new HashMap<>(metrics);
        }
        
        public void shutdown() {
            isShutdown = true;
            metrics.clear();
        }
    }
}