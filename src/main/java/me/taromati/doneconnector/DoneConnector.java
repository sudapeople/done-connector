package me.taromati.doneconnector;

import me.taromati.doneconnector.soop.SoopApi;
import me.taromati.doneconnector.soop.SoopLiveInfo;
import me.taromati.doneconnector.soop.SoopWebSocket;
import me.taromati.doneconnector.chzzk.ChzzkApi;
import me.taromati.doneconnector.chzzk.ChzzkWebSocket;
import me.taromati.doneconnector.exception.DoneException;
import me.taromati.doneconnector.exception.ExceptionCode;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.java_websocket.drafts.Draft_6455;
import org.java_websocket.protocols.Protocol;
import org.jetbrains.annotations.NotNull;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CompletionException;


import java.util.*;
import java.util.stream.Collectors;

public final class DoneConnector extends JavaPlugin implements Listener {
    public static Plugin plugin;

    public static boolean debug;
    public static boolean random;
    public static boolean poong = false;

    private final Object chzzkLock = new Object();
    private final Object soopLock = new Object();
    private volatile boolean chzzkConnecting = false;
    private volatile boolean soopConnecting = false;
    public static boolean isReloading = false; // reload 여부 변수 추가

    private static final List<Map<String, String>> chzzkUserList = new ArrayList<>();
    private static final List<Map<String, String>> soopUserList = new ArrayList<>();
    private static final HashMap<Integer, List<String>> donationRewards = new HashMap<>();
    List<ChzzkWebSocket> chzzkWebSocketList = new ArrayList<>();
    List<SoopWebSocket> soopWebSocketList = new ArrayList<>();

    private final ScheduledExecutorService sharedScheduler = Executors.newScheduledThreadPool(
        Runtime.getRuntime().availableProcessors(),
        r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            t.setName("ChzzkWebSocket-Shared-Scheduler");
            return t;
        }
    );

    @Override
    public void onEnable() {
        plugin = this;
        Bukkit.getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(this.getCommand("done")).setExecutor(this);
        Objects.requireNonNull(this.getCommand("done")).setTabCompleter(this);

        try {
            loadConfig();
            connectChzzkList();
            connectSoopList();
            Logger.info(ChatColor.GREEN + "플러그인 활성화 완료.");
        } catch (Exception e) {
            Logger.error("플러그인 초기화 중 오류가 발생했습니다: " + e.getMessage());
            Bukkit.getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        try {
            // 웹소켓 연결 종료
            disconnectChzzkList();
            disconnectSoopList();
            
            // 공유 스케줄러 종료
            sharedScheduler.shutdown();
            if (!sharedScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                sharedScheduler.shutdownNow();
            }
            
            Logger.info(ChatColor.GREEN + "플러그인 비활성화 완료.");
        } catch (Exception e) {
            Logger.error("플러그인 종료 중 오류 발생: " + e.getMessage());
        }
    }



    private void clearConfig() {
        Logger.debug("설정 초기화 시작...");
        debug = false;
        random = false;
        chzzkUserList.clear();
        soopUserList.clear();
        donationRewards.clear();
        reloadConfig();
        Logger.debug("설정 초기화 완료");
    }

    private void loadConfig() throws DoneException {
        this.saveResource("config.yml", false);
        
        FileConfiguration config = this.getConfig();

        try {
            debug = config.getBoolean("디버그");
            random = config.getBoolean("랜덤 보상");
            if (config.contains("숲풍선갯수로출력")) {
                poong = config.getBoolean("숲풍선갯수로출력");
            }
        } catch (Exception e) {
            throw new DoneException(ExceptionCode.CONFIG_LOAD_ERROR);
        }

        try {
            Logger.info("치지직 아이디 로드 중...");
            Set<String> nicknameList = Objects.requireNonNull(config.getConfigurationSection("치지직"))
                    .getKeys(false);
            Logger.debug(nicknameList.toString());

            for (String nickname : nicknameList) {
                Logger.debug("치지직 닉네임: " + nickname);
                String id = config.getString("치지직." + nickname + ".식별자");
                Logger.debug("치지직 아이디: " + id);
                String tag = config.getString("치지직." + nickname + ".마크닉네임");
                Logger.debug("치지직 마크닉네임: " + tag);

                if (id == null || tag == null) {
                    throw new DoneException(ExceptionCode.ID_NOT_FOUND);
                }

                Map<String, String> userMap = new HashMap<>();
                userMap.put("nickname", nickname);
                userMap.put("id", id);
                userMap.put("tag", tag);
                chzzkUserList.add(userMap);

                Logger.debug("치지직 유저: " + userMap);
            }
        } catch (Exception e) {
            throw new DoneException(ExceptionCode.ID_NOT_FOUND);
        }

        Logger.info(ChatColor.GREEN + "치지직 아이디 " + chzzkUserList.size() + "개 로드 완료.");

        try {
            Logger.debug("숲 아이디 로드 중...");
            Set<String> nicknameList = new HashSet<>();
            if (this.getConfig().getConfigurationSection("숲") != null) {
                nicknameList.addAll(this.getConfig().getConfigurationSection("숲").getKeys(false));
            }
            if (this.getConfig().getConfigurationSection("아프리카") != null) {
                nicknameList.addAll(this.getConfig().getConfigurationSection("아프리카").getKeys(false));
            }
            Logger.debug(nicknameList.toString());

            for (String nickname : nicknameList) {
                Logger.debug("숲 닉네임: " + nickname);
                if (config.getString("숲." + nickname + ".식별자") != null) {
                    String id = config.getString("숲." + nickname + ".식별자");
                    Logger.debug("숲 아이디: " + id);
                    String tag = config.getString("숲." + nickname + ".마크닉네임");
                    Logger.debug("숲 마크닉네임: " + tag);

                    if (id == null || tag == null) {
                        throw new DoneException(ExceptionCode.ID_NOT_FOUND);
                    }

                    Map<String, String> userMap = new HashMap<>();
                    userMap.put("nickname", nickname);
                    userMap.put("id", id);
                    userMap.put("tag", tag);
                    soopUserList.add(userMap);
                    Logger.debug("숲 유저: " + userMap);
                } else if (config.getString("아프리카." + nickname + ".식별자") != null) {
                    // TODO: 하위호환용, 추후 제거.
                    String id = config.getString("아프리카." + nickname + ".식별자");
                    Logger.debug("아프리카 아이디: " + id);
                    String tag = config.getString("아프리카." + nickname + ".마크닉네임");
                    Logger.debug("아프리카 마크닉네임: " + tag);

                    if (id == null || tag == null) {
                        throw new DoneException(ExceptionCode.ID_NOT_FOUND);
                    }

                    Map<String, String> userMap = new HashMap<>();
                    userMap.put("nickname", nickname);
                    userMap.put("id", id);
                    userMap.put("tag", tag);
                    soopUserList.add(userMap);
                    Logger.debug("아프리카 유저: " + userMap);
                }

            }
        } catch (Exception e) {
            throw new DoneException(ExceptionCode.ID_NOT_FOUND);
        }

        Logger.info(ChatColor.GREEN + "숲 아이디 " + soopUserList.size() + "개 로드 완료.");

        if (chzzkUserList.isEmpty() && soopUserList.isEmpty()) {
            throw new DoneException(ExceptionCode.ID_NOT_FOUND);
        }

        try {
            for (String price : Objects.requireNonNull(config.getConfigurationSection("후원 보상")).getKeys(false)) {
                donationRewards.put(Integer.valueOf(price), this.getConfig().getStringList("후원 보상." + price));
            }
        } catch (Exception e) {
            throw new DoneException(ExceptionCode.REWARD_PARSE_ERROR);
        }

        if (donationRewards.keySet().isEmpty()) {
            throw new DoneException(ExceptionCode.REWARD_NOT_FOUND);
        }

        Logger.info(ChatColor.GREEN + "후원 보상 목록 " + donationRewards.keySet().size() + "개 로드 완료.");
    }

    private void safeReload() {
        isReloading = true; // reload 시작
        Logger.warn("후원 설정을 다시 불러옵니다.");
        CompletableFuture<Void> reloadFuture = CompletableFuture.runAsync(() -> {
            try {
                doReload();
            } catch (Exception e) {
                Logger.error("설정 리로드 중 오류 발생: " + e.getMessage());
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                throw new CompletionException(e);
            } finally {
                isReloading = false; // reload 완료
            }
        });

        try {
            reloadFuture.get(180, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            Logger.error("설정 리로드 작업 타임아웃");
            reloadFuture.cancel(true);
        } catch (InterruptedException e) {
            Logger.error("설정 리로드 작업이 중단되었습니다");
            Thread.currentThread().interrupt();
            reloadFuture.cancel(true);
        } catch (ExecutionException e) {
            Logger.error("설정 리로드 작업 실패: " + e.getMessage());
            if (e.getCause() != null) {
                Logger.error("원인: " + e.getCause().getMessage());
            }
        }
    }

    private void doReload() throws InterruptedException {
        // 1. 모든 웹소켓 연결 종료
        CountDownLatch disconnectLatch = new CountDownLatch(2);
        
        // 치지직 연결 종료
        CompletableFuture<Void> chzzkDisconnect = CompletableFuture.runAsync(() -> {
            try {
                Logger.debug("치지직 웹소켓 연결 종료 시작...");
                for (ChzzkWebSocket webSocket : new ArrayList<>(chzzkWebSocketList)) {
                    try {
                        webSocket.shutdown();
                    } catch (Exception e) {
                        Logger.error("[ChzzkWebsocket] 웹소켓 종료 중 오류: " + e.getMessage());
                    }
                }
                chzzkWebSocketList.clear();
                Logger.debug("치지직 웹소켓 연결 종료 완료");
            } finally {
                disconnectLatch.countDown();
            }
        });

        // 숲 연결 종료
        CompletableFuture<Void> soopDisconnect = CompletableFuture.runAsync(() -> {
            try {
                Logger.debug("숲 웹소켓 연결 종료 시작...");
                for (SoopWebSocket webSocket : new ArrayList<>(soopWebSocketList)) {
                    try {
                        webSocket.close();
                        webSocket.closeBlocking();
                    } catch (Exception e) {
                        Logger.error("숲 웹소켓 종료 중 오류: " + e.getMessage());
                    }
                }
                soopWebSocketList.clear();
                Logger.debug("숲 웹소켓 연결 종료 완료");
            } finally {
                disconnectLatch.countDown();
            }
        });

        // 최대 60초간 연결 종료 대기
        if (!disconnectLatch.await(60, TimeUnit.SECONDS)) {
            Logger.warn("일부 웹소켓 연결 종료 타임아웃 - 계속 진행합니다.");
        }

        // 2. 추가 5초 대기하여 소켓 정리 시간 확보
        Thread.sleep(5000);

        // 3. 설정 리로드
        clearConfig();
        loadConfig();

        // 4. 새로운 연결 시작
        reconnectAll();
    }

    private void reconnectAll() throws InterruptedException {
        CountDownLatch connectLatch = new CountDownLatch(2);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger totalConnections = new AtomicInteger(0);

        // 치지직 연결
        CompletableFuture<Void> chzzkConnect = CompletableFuture.runAsync(() -> {
            try {
                for (Map<String, String> chzzkUser : chzzkUserList) {
                    totalConnections.incrementAndGet();
                    if (connectChzzk(chzzkUser)) {
                        successCount.incrementAndGet();
                    }
                }
            } finally {
                connectLatch.countDown();
            }
        });

        // 숲 연결
        CompletableFuture<Void> soopConnect = CompletableFuture.runAsync(() -> {
            try {
                for (Map<String, String> soopUser : soopUserList) {
                    totalConnections.incrementAndGet();
                    if (connectSoop(soopUser)) {
                        successCount.incrementAndGet();
                    }
                }
            } finally {
                connectLatch.countDown();
            }
        });

        // 최대 60초간 연결 대기
        if (!connectLatch.await(60, TimeUnit.SECONDS)) {
            Logger.warn("일부 연결 시도 타임아웃");
        }

        // 5. 결과 로깅
        int total = totalConnections.get();
        int success = successCount.get();
        if (success == total) {
            Logger.info(ChatColor.GREEN + "모든 연결 재설정 완료 (" + success + "/" + total + ")");
        } else {
            Logger.warn("일부 연결 실패 (" + success + "/" + total + " 성공)");
        }
    }

    private void disconnectByNickName(String target) {
        chzzkWebSocketList = chzzkWebSocketList.stream()
                .filter(chzzkWebSocket -> {
                    if (Objects.equals(chzzkWebSocket.getChzzkUser().get("nickname"), target) || Objects.equals(chzzkWebSocket.getChzzkUser().get("tag"), target)) {
                        chzzkWebSocket.close();
                        return false;
                    }
                    return true;
                })
                .collect(Collectors.toList());
        soopWebSocketList = soopWebSocketList.stream()
                .filter(soopWebSocket -> {
                    if (Objects.equals(soopWebSocket.getSoopUser().get("nickname"), target) || Objects.equals(soopWebSocket.getSoopUser().get("tag"), target)) {
                        soopWebSocket.close();
                        return false;
                    }
                    return true;
                })
                .collect(Collectors.toList());
    }

    private boolean connectChzzk(Map<String, String> chzzkUser) {
        try {
            String chzzkId = chzzkUser.get("id");
            String chatChannelId = ChzzkApi.getChatChannelId(chzzkId);
            Logger.debug("채널 아이디 조회 완료: " + chatChannelId);

            String token = ChzzkApi.getAccessToken(chatChannelId);
            String accessToken = token.split(";")[0];
            String extraToken = token.split(";")[1];
            Logger.debug("액세스 토큰 조회 완료: " + accessToken + ", " + extraToken);

            ChzzkWebSocket webSocket = new ChzzkWebSocket(
                "wss://kr-ss1.chat.naver.com/chat", 
                chatChannelId,
                accessToken,
                extraToken,
                chzzkUser,
                donationRewards,
                sharedScheduler  // 공유 스케줄러 전달
            );

            CompletableFuture<Boolean> connectFuture = new CompletableFuture<>();
            
            Thread connectThread = new Thread(() -> {
                try {
                    webSocket.connectBlocking(5, TimeUnit.SECONDS);
                    chzzkWebSocketList.add(webSocket);
                    connectFuture.complete(true);
                } catch (Exception e) {
                    Logger.error("[ChzzkWebsocket][" + chzzkUser.get("nickname") + 
                            "] 연결 실패: " + e.getMessage());
                    connectFuture.complete(false);
                }
            });
            connectThread.setDaemon(true);
            connectThread.start();

            return connectFuture.get(10, TimeUnit.SECONDS);
            
        } catch (Exception e) {
            Logger.error("[ChzzkWebsocket][" + chzzkUser.get("nickname") + 
                    "] 연결 중 오류: " + e.getMessage());
            return false;
        }
    }

    private void connectChzzkList() {
        synchronized(chzzkLock) {
            if (chzzkConnecting) {
                Logger.warn("이미 치지직 연결 작업이 진행 중입니다.");
                return;
            }
            chzzkConnecting = true;
            try {
                disconnectChzzkList();
                Thread.sleep(2000); // 연결 종료 후 잠시 대기
                
                for (Map<String, String> chzzkUser : chzzkUserList) {
                    try {
                        connectChzzk(chzzkUser);
                    } catch (Exception e) {
                        Logger.error("[ChzzkWebsocket][" + chzzkUser.get("nickname") + 
                                "] 연결 중 오류: " + e.getMessage());
                    }
                }
            } catch (Exception e) {
                Logger.error("치지직 연결 중 오류 발생: " + e.getMessage());
            } finally {
                chzzkConnecting = false;
            }
        }
    }

    private void disconnectChzzkList() {
        Logger.debug("치지직 웹소켓 연결 종료 시작...");
        
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        
        for (ChzzkWebSocket webSocket : new ArrayList<>(chzzkWebSocketList)) {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    webSocket.shutdown();
                } catch (Exception e) {
                    Logger.error("[ChzzkWebsocket] 웹소켓 종료 중 오류: " + e.getMessage());
                }
            });
            futures.add(future);
        }
        
        try {
            // 비동기로 종료 작업 처리, 타임아웃 5초
            CompletableFuture<Void> allFutures = CompletableFuture.allOf(
                futures.toArray(new CompletableFuture[0])
            ).orTimeout(5, TimeUnit.SECONDS)
            .exceptionally(throwable -> {
                Logger.warn("일부 웹소켓 종료 타임아웃 - 강제 진행");
                return null;
            });
            
            // 비차단 방식으로 대기
            allFutures.thenRun(() -> {
                chzzkWebSocketList.clear();
                Logger.debug("치지직 웹소켓 연결 종료 완료");
            });
            
        } catch (Exception e) {
            Logger.error("웹소켓 종료 중 오류 발생: " + e.getMessage());
            chzzkWebSocketList.clear();
        }
    }

    private boolean connectSoop(Map<String, String> soopUser) {
        String soopId = soopUser.get("id");

        try {
            SoopLiveInfo liveInfo = SoopApi.getPlayerLive(soopId);
            Draft_6455 draft6455 = new Draft_6455(
                    Collections.emptyList(),
                    Collections.singletonList(new Protocol("chat"))
            );
            SoopWebSocket webSocket = new SoopWebSocket(
                "wss://" + liveInfo.CHDOMAIN() + ":" + liveInfo.CHPT() + "/Websocket/" + liveInfo.BJID(), 
                draft6455, 
                liveInfo, 
                soopUser, 
                donationRewards, 
                poong
            );
            
            // 연결 시도
            CompletableFuture<Boolean> connectFuture = new CompletableFuture<>();
            Thread connectThread = new Thread(() -> {
                try {
                    webSocket.connectBlocking(5, TimeUnit.SECONDS);
                    soopWebSocketList.add(webSocket);
                    connectFuture.complete(true);
                } catch (Exception e) {
                    Logger.error("[SoopWebsocket][" + soopUser.get("nickname") + 
                            "] 연결 실패: " + e.getMessage());
                    connectFuture.complete(false);
                }
            });
            connectThread.setDaemon(true);
            connectThread.start();

            return connectFuture.get(10, TimeUnit.SECONDS);

        } catch (TimeoutException e) {
            Logger.error("[SoopWebsocket][" + soopUser.get("nickname") + 
                    "] 연결 시도 타임아웃: " + e.getMessage());
            return false;
        } catch (ExecutionException e) {
            Logger.error("[SoopWebsocket][" + soopUser.get("nickname") + 
                    "] 연결 실행 중 오류: " + e.getMessage());
            return false;
        } catch (InterruptedException e) {
            Logger.error("[SoopWebsocket][" + soopUser.get("nickname") + 
                    "] 연결이 중단되었습니다: " + e.getMessage());
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            Logger.error("[SoopWebsocket][" + soopUser.get("nickname") + 
                    "] 숲 채팅에 연결 중 오류가 발생했습니다.");
            Logger.debug(e.getMessage());
            return false;
        }
    }

    private void connectSoopList() {
        synchronized(soopLock) {
            if (soopConnecting) {
                Logger.warn("이미 숲 연결 작업이 진행 중입니다.");
                return;
            }
            soopConnecting = true;
            try {
                disconnectSoopList();
                Thread.sleep(2000); // 연결 종료 후 잠시 대기
                
                for (Map<String, String> soopUser : soopUserList) {
                    try {
                        connectSoop(soopUser);
                    } catch (Exception e) {
                        Logger.error("[SoopWebsocket][" + soopUser.get("nickname") + 
                                "] 연결 중 오류: " + e.getMessage());
                    }
                }
            } catch (Exception e) {
                Logger.error("숲 연결 중 오류 발생: " + e.getMessage());
            } finally {
                soopConnecting = false;
            }
        }
    }

    private void disconnectSoopList() {
        Logger.debug("숲 웹소켓 연결 종료 시작...");
        for (SoopWebSocket webSocket : new ArrayList<>(soopWebSocketList)) {
            try {
                webSocket.close();
                webSocket.closeBlocking();
            } catch (Exception e) {
                Logger.error("숲 웹소켓 종료 중 오류: " + e.getMessage());
            }
        }
        soopWebSocketList.clear();
        Logger.debug("숲 웹소켓 연결 종료 완료");
    }

    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!label.equalsIgnoreCase("done")) {
            return false;
        } else if (!sender.isOp()) {
            sender.sendMessage(ChatColor.RED + "권한이 없습니다.");
            return false;
        } else if (args.length < 1) {
            return false;
        }

        try {
            String cmd = args[0];

            switch (cmd.toLowerCase()) {
                case "on":
                    Logger.warn("후원 기능을 활성화 합니다.");
                    try {
                        connectChzzkList();
                        connectSoopList();
                    } catch (Exception e) {
                        Logger.error("후원 기능 활성화 중 오류 발생: " + e.getMessage());
                        return false;
                    }
                    return true;
                    
                case "off":
                    Logger.warn("후원 기능을 비활성화 합니다.");
                    try {
                        disconnectChzzkList();
                        disconnectSoopList();
                    } catch (Exception e) {
                        Logger.error("후원 기능 비활성화 중 오류 발생: " + e.getMessage());
                        return false;
                    }
                    return true;
                    
                case "reconnect":
                    if (args.length < 2) {
                        Logger.warn("all 혹은 스트리머 닉네임을 입력해주세요.");
                        return false;
                    }
                    
                    String target = args[1];
                    reconnectHandling(target);
                    return true;
                    
                case "reload":
                    Logger.warn("후원 설정을 다시 불러옵니다.");
                    try {
                        safeReload();
                        return true;
                    } catch (Exception e) {
                        Logger.error("설정 리로드 중 오류 발생: " + e.getMessage());
                        e.printStackTrace();
                        return false;
                    }
                    
                case "add":
                    if (args.length < 5) {
                        Logger.error("옵션 누락. /done add <플랫폼> <방송닉> <방송ID> <마크닉>");
                        return false;
                    }
                    handleAddCommand(args);
                    return true;
                    
                default:
                    return false;
            }
        } catch (Exception e) {
            Logger.error("커맨드 수행 중 오류가 발생했습니다.");
            e.printStackTrace();
            return false;
        }
    }

    private void reconnectHandling(String target) {
        CompletableFuture.runAsync(() -> {
            try {
                if (target.equals("all")) {
                    disconnectChzzkList();
                    disconnectSoopList();
                    Thread.sleep(2000);
                    connectChzzkList();
                    connectSoopList();
                    Logger.info(ChatColor.GREEN + "후원 기능 재접속을 완료했습니다.");
                } else {
                    disconnectByNickName(target);
                    Thread.sleep(1000);
                    
                    AtomicInteger reconnectCount = new AtomicInteger(0);
                    
                    reconnectSpecificUser(target, reconnectCount);
                    
                    if (reconnectCount.get() <= 0) {
                        Logger.warn("닉네임을 찾을 수 없습니다.");
                    }
                }
            } catch (InterruptedException e) {
                Logger.error("재연결 작업이 중단되었습니다: " + e.getMessage());
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                Logger.error("재연결 중 오류 발생: " + e.getMessage());
            }
        });
    }

    private void reconnectSpecificUser(String target, AtomicInteger reconnectCount) {
        // 치지직 재연결
        chzzkUserList.stream()
            .filter(user -> user.get("nickname").equalsIgnoreCase(target) || 
                        user.get("tag").equalsIgnoreCase(target))
            .forEach(user -> {
                try {
                    if (connectChzzk(user)) {
                        reconnectCount.incrementAndGet();
                        Logger.info(ChatColor.GREEN + "[" + target + "] 재접속을 완료했습니다.");
                    }
                } catch (Exception e) {
                    Logger.error("[" + target + "] 채팅 연결 중 오류가 발생했습니다.");
                }
            });
        
        // 숲 재연결
        soopUserList.stream()
            .filter(user -> user.get("nickname").equalsIgnoreCase(target) || 
                        user.get("tag").equalsIgnoreCase(target))
            .forEach(user -> {
                try {
                    if (connectSoop(user)) {
                        reconnectCount.incrementAndGet();
                        Logger.info(ChatColor.GREEN + "[" + target + "] 재접속을 완료했습니다.");
                    }
                } catch (Exception e) {
                    Logger.error("[" + target + "] 채팅 연결 중 오류가 발생했습니다.");
                }
            });
    }

    private void handleAddCommand(String[] args) {
        String platform = args[1];
        String nickname = args[2];
        String id = args[3];
        String tag = args[4];

        Map<String, String> userMap = new HashMap<>();
        userMap.put("nickname", nickname);
        userMap.put("id", id);
        userMap.put("tag", tag);

        try {
            switch (platform) {
                case "치지직":
                    if (connectChzzk(userMap)) {
                        chzzkUserList.add(userMap);
                    }
                    break;
                case "숲":
                    if (connectSoop(userMap)) {
                        soopUserList.add(userMap);
                    }
                    break;
                default:
                    Logger.error("지원하지 않는 플랫폼입니다: " + platform);
            }
        } catch (Exception e) {
            Logger.error("사용자 추가 중 오류 발생: " + e.getMessage());
        }
    }

    public List<String> onTabComplete(CommandSender sender, @NotNull Command cmd, @NotNull String label, String[] args) {
        if (cmd.getName().equalsIgnoreCase("done") == false) {
            return Collections.emptyList();
        }

        if (sender.isOp() == false) {
            return Collections.emptyList();
        }

        if (args.length == 1) {
            List<String> commandList = new ArrayList<>(Arrays.asList("on", "off", "reconnect", "reload", "add"));

            if (args[0].isEmpty()) {
                return commandList;
            } else {
                return commandList.stream()
                        .filter((command) -> command.toLowerCase().startsWith(args[0].toLowerCase()))
                        .toList();
            }
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("reconnect")) {
            if (args[1].isEmpty()) {
                return new ArrayList<>(List.of("all"));
            } else {
                return Bukkit.getOnlinePlayers().stream()
                        .map(Player::getName)
                        .filter(name -> name.toLowerCase().startsWith(args[1].toLowerCase()))
                        .toList();
            }
        }

        return Collections.emptyList();
    }
}
