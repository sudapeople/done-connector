package me.suda.doneconnector.chzzk;

import lombok.Getter;
import me.suda.doneconnector.DoneConnector;
import me.suda.doneconnector.Logger;
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

public class ChzzkWebSocket extends WebSocketClient {
    @Getter
    private final Map<String, String> chzzkUser;
    private static final int CONNECTION_TIMEOUT_MS = 30000;
    private static final int COMMAND_EXECUTION_TIMEOUT = 5000;

    private final String chatChannelId;
    private final String accessToken;
    private final String extraToken;
    @Getter
    private final Map<Integer, List<String>> donationRewards;
    
    private final ScheduledExecutorService scheduler;
    private final Random random;
    private final Object connectionLock;
    
    private volatile ConnectionState connectionState;
    private ScheduledFuture<?> pingSchedule;

    private static final int CHZZK_CHAT_CMD_PING = 0;
    private static final int CHZZK_CHAT_CMD_PONG = 10000;
    private static final int CHZZK_CHAT_CMD_CONNECT = 100;
    private static final int CHZZK_CHAT_CMD_CONNECT_RES = 10100;
    private static final int CHZZK_CHAT_CMD_CHAT = 93101;
    private static final int CHZZK_CHAT_CMD_DONATION = 93102;

    private final JSONParser jsonParser = new JSONParser();

    private volatile boolean isShuttingDown = false;
    private final Set<Future<?>> pendingTasks = Collections.newSetFromMap(new ConcurrentHashMap<>());

    enum ConnectionState {
        CONNECTED, DISCONNECTED
    }

    @SuppressWarnings("unchecked")
    public ChzzkWebSocket(String serverUri, String chatChannelId, String accessToken, 
                         String extraToken, Map<String, String> chzzkUser,
                         Map<Integer, List<String>> donationRewards,
                         ScheduledExecutorService sharedScheduler) {
        super(URI.create(serverUri));
        this.chzzkUser = new ConcurrentHashMap<>(chzzkUser);
        this.donationRewards = new ConcurrentHashMap<>(donationRewards);
        
        this.chatChannelId = chatChannelId;
        this.accessToken = accessToken;
        this.extraToken = extraToken;
        
        this.scheduler = sharedScheduler;
        this.random = new Random();
        this.connectionLock = new Object();
        this.connectionState = ConnectionState.DISCONNECTED;

        this.setConnectionLostTimeout(CONNECTION_TIMEOUT_MS / 1000);
    }

    private void attemptReconnect() {
        synchronized (connectionLock) {
            if (!DoneConnector.isReloading || 
                connectionState != ConnectionState.DISCONNECTED) {
                return;
            }

            try {
                if (reconnectBlocking()) {
                    connectionState = ConnectionState.CONNECTED;
                    Logger.info(ChatColor.GREEN + "[ChzzkWebsocket][" + 
                        chzzkUser.get("nickname") + "] 재연결 성공");
                } else {
                    connectionState = ConnectionState.DISCONNECTED;
                    Logger.warn("[ChzzkWebsocket][" + chzzkUser.get("nickname") + 
                        "] 재연결 실패");
                }
            } catch (Exception e) {
                Logger.error("[ChzzkWebsocket][" + chzzkUser.get("nickname") + 
                    "] 재연결 시도 중 오류 발생: " + e.getMessage());
                connectionState = ConnectionState.DISCONNECTED;
            }
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

    private void sendAuthenticationMessage() {
        try {
            JSONObject baseObject = new JSONObject();
            baseObject.put("ver", "2");
            baseObject.put("svcid", "game");
            baseObject.put("cid", this.chatChannelId);
            
            JSONObject sendObject = new JSONObject();
            sendObject.putAll(baseObject);
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

    @Override
    public void onOpen(ServerHandshake handshakedata) {
        synchronized (connectionLock) {
            connectionState = ConnectionState.CONNECTED;
            
            Logger.info(String.format(ChatColor.GREEN + "[ChzzkWebsocket][%s] 치지직 웹소켓 연결이 성공했습니다.",
                       chzzkUser.get("nickname")));
            
            sendAuthenticationMessage();
            startPingThread();
        }
    }

    @Override
    public void onMessage(String message) {
        if (isShuttingDown) {
            return;
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
                    Logger.debug("[ChzzkWebsocket][" + chzzkUser.get("nickname") + 
                               "] 알 수 없는 명령어: " + cmd);
            }
        } catch (Exception e) {
            Logger.error("[ChzzkWebsocket][" + chzzkUser.get("nickname") + 
                        "] 메시지 처리 중 오류 발생: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @SuppressWarnings("unchecked")
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
            
            // 플레이어 데이터 저장 (실제 후원으로 구분)
            String streamerUuid = DoneConnector.plugin.getPlayerUuid(chzzkUser.get("tag"));
            if (streamerUuid != null) {
                DoneConnector.plugin.savePlayerData(streamerUuid, chzzkUser.get("tag"), nickname, payAmount, msg, "치지직", false);
            }
            
            if (DoneConnector.random) {
                String command = commands.get(random.nextInt(commands.size()));
                executeCommand(chzzkUser.get("tag"), nickname, payAmount, msg, command);
            } else {
                for (String command : commands) {
                    executeCommand(chzzkUser.get("tag"), nickname, payAmount, msg, command);
                }
            }
            
        } catch (Exception e) {
            Logger.error("[ChzzkWebsocket][" + chzzkUser.get("nickname") + 
                      "] 후원 처리 중 오류 발생: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void startPingThread() {
        if (pingSchedule != null && !pingSchedule.isDone()) {
            pingSchedule.cancel(false);
        }

        pingSchedule = scheduler.scheduleAtFixedRate(() -> {
            if (isOpen() && connectionState == ConnectionState.CONNECTED) {
                try {
                    JSONObject pongObject = new JSONObject();
                    pongObject.put("cmd", CHZZK_CHAT_CMD_PONG);
                    pongObject.put("ver", 2);
                    send(pongObject.toJSONString());
                } catch (Exception e) {
                    Logger.error("[ChzzkWebsocket][" + chzzkUser.get("nickname") + 
                            "] 핑 메시지 전송 중 오류 발생: " + e.getMessage());
                }
            }
        }, 10, 20, TimeUnit.SECONDS);
    }

    private void executeCommand(String tag, String nickname, int payAmount, 
                              String msg, String command) {
        if (isShuttingDown) {
            return;
        }

        String[] commandArray = command.split(";");
        for (String cmd : commandArray) {
            if (isShuttingDown) break;

            String finalCommand = cmd
                .replace("%tag%", tag)
                .replace("%name%", nickname)
                .replace("%amount%", String.valueOf(payAmount))
                .replace("%message%", msg);

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

    private void handlePing() {
        JSONObject pongObject = new JSONObject();
        pongObject.put("cmd", CHZZK_CHAT_CMD_PONG);
        pongObject.put("ver", 2);
        send(pongObject.toJSONString());
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        synchronized (connectionLock) {
            Logger.info(String.format(ChatColor.RED + "[ChzzkWebsocket][%s] 웹소켓 연결이 종료되었습니다. (코드: %d, 사유: %s)",
                    chzzkUser.get("nickname"), code, reason));
            connectionState = ConnectionState.DISCONNECTED;
        }
    }

    @Override
    public void onError(Exception ex) {
        Logger.error(String.format("[ChzzkWebsocket][%s] 웹소켓 오류 발생: %s",
                    chzzkUser.get("nickname"), ex.getMessage()));

        synchronized (connectionLock) {
            if (connectionState == ConnectionState.CONNECTED) {
                connectionState = ConnectionState.DISCONNECTED;
            } else {
                connectionState = ConnectionState.DISCONNECTED;
                attemptReconnect();
            }
        }
    }

    public void shutdown() {
        synchronized (connectionLock) {
            if (isShuttingDown) {
                return;
            }
            isShuttingDown = true;

            if (pingSchedule != null) {
                pingSchedule.cancel(false);
                pingSchedule = null;
            }

            try {
                pendingTasks.forEach(task -> task.cancel(true));
                pendingTasks.clear();
                
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
                
                if (!closeLatch.await(5, TimeUnit.SECONDS)) {
                    Logger.warn("[ChzzkWebsocket][" + chzzkUser.get("nickname") + 
                            "] 웹소켓 종료 타임아웃");
                }
                
            } catch (Exception e) {
                Logger.error("[ChzzkWebsocket][" + chzzkUser.get("nickname") + 
                        "] 종료 중 오류: " + e.getMessage());
            } finally {
                connectionState = ConnectionState.DISCONNECTED;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void handleChat(JSONObject messageObject) {
        try {
            JSONObject bdyObject = (JSONObject) ((JSONArray) messageObject.get("bdy")).get(0);
            String uid = (String) bdyObject.get("uid");
            String message = (String) bdyObject.get("msg");
            
            if (message == null || message.trim().isEmpty()) {
                return;
            }

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
            
            String streamerNickname = chzzkUser.get("nickname");
            String markNickname = chzzkUser.get("tag");
            String channelId = chzzkUser.get("id");

            Logger.debug("[ChzzkWebsocket] 채팅 정보 - 채널ID: " + channelId + 
                        ", 스트리머: " + streamerNickname + 
                        ", 마크닉네임: " + markNickname);

            if (markNickname != null) {
                String gameMessage = ChatColor.GRAY + "[치지직 | " + streamerNickname + "] " +
                                nickColor + senderNickname + ChatColor.WHITE + 
                                (userRole.equals("일반") ? "" : " (" + userRole + ")") + 
                                ": " + displayMessage;

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
}