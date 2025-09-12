package me.suda.doneconnector;

import me.suda.doneconnector.soop.SoopApi;
import me.suda.doneconnector.soop.SoopLiveInfo;
import me.suda.doneconnector.soop.SoopWebSocket;
import me.suda.doneconnector.chzzk.ChzzkApi;
import me.suda.doneconnector.chzzk.ChzzkWebSocket;
import me.suda.doneconnector.exception.DoneException;
import me.suda.doneconnector.exception.ExceptionCode;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.java_websocket.drafts.Draft_6455;
import org.java_websocket.protocols.Protocol;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public final class DoneConnector extends JavaPlugin implements Listener {
    public static DoneConnector plugin;

    public static boolean debug;
    public static boolean random;
    public static boolean poong = false;
    public static boolean autoConnect = false; // 서버 시작 시 자동 연결 여부를 제어하는 플래그 추가

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

    // 추가할 필드 선언
    private final ExecutorService connectionExecutor = Executors.newFixedThreadPool(2);

    // sharedScheduler 수정
    private final ScheduledExecutorService sharedScheduler = Executors.newScheduledThreadPool(1,
        r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            t.setName("WebSocket-Scheduler");
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
            // data 디렉토리 생성
            createDataDirectory();
            
            loadConfig();
            
            // 자동 연결 설정이 활성화된 경우에만 서버 시작 시 모든 채널 연결
            if (autoConnect) {
                connectChzzkList();
                connectSoopList();
                Logger.info(ChatColor.GREEN + "전체 채널 자동 연결 완료.");
            } else {
                Logger.info(ChatColor.YELLOW + "자동 연결이 비활성화되어 있습니다. 플레이어 접속 시 개별 채널만 연결됩니다.");
            }
            
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

    /**
     * data 디렉토리 생성
     */
    private void createDataDirectory() {
        File dataDir = new File(getDataFolder(), "data");
        if (!dataDir.exists()) {
            boolean created = dataDir.mkdirs();
            if (created) {
                Logger.info(ChatColor.GREEN + "플레이어 데이터 디렉토리가 생성되었습니다: " + dataDir.getAbsolutePath());
            } else {
                Logger.warn("플레이어 데이터 디렉토리 생성에 실패했습니다.");
            }
        } else {
            Logger.debug("플레이어 데이터 디렉토리가 이미 존재합니다.");
        }
    }

    private void clearConfig() {
        Logger.debug("설정 초기화 시작...");
        debug = false;
        random = false;
        autoConnect = false; // 설정 초기화 시 자동 연결 플래그도 초기화
        chzzkUserList.clear();
        soopUserList.clear();
        donationRewards.clear();
        // reloadConfig() 제거 - loadConfig()에서 직접 파일을 읽음
        Logger.debug("설정 초기화 완료");
    }

    private void loadConfig() throws DoneException {
        // 파일 시스템의 config.yml을 직접 읽기
        File configFile = new File(getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            this.saveResource("config.yml", false);
        }
        
        // YamlConfiguration을 사용하여 파일을 직접 읽기
        FileConfiguration config = YamlConfiguration.loadConfiguration(configFile);

        try {
            debug = config.getBoolean("디버그");
            random = config.getBoolean("랜덤 보상");
            if (config.contains("숲풍선갯수로출력")) {
                poong = config.getBoolean("숲풍선갯수로출력");
            }
            
            // config.yml에 "자동연결" 설정이 있으면 해당 값을 읽어옴
            if (config.contains("자동연결")) {
                autoConnect = config.getBoolean("자동연결");
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
            if (config.getConfigurationSection("숲") != null) {
                nicknameList.addAll(config.getConfigurationSection("숲").getKeys(false));
            }
            if (config.getConfigurationSection("아프리카") != null) {
                nicknameList.addAll(config.getConfigurationSection("아프리카").getKeys(false));
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
                donationRewards.put(Integer.valueOf(price), config.getStringList("후원 보상." + price));
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

        // 4. 새로운 연결 시작 (자동 연결 설정이 켜져 있을 때만)
        if (autoConnect) {
            reconnectAll();
        } else {
            // 자동 연결이 꺼져 있으면 현재 접속 중인 플레이어들의 채널만 다시 연결
            reconnectOnlinePlayers();
        }
    }

    // 현재 접속 중인 플레이어들의 채널만 다시 연결하는 메소드
    private void reconnectOnlinePlayers() {
        Logger.info("현재 접속 중인 플레이어들의 채널만 재연결합니다.");
        
        for (Player player : Bukkit.getOnlinePlayers()) {
            String playerName = player.getName();
            
            try {
                boolean connected = false;
                
                // 치지직 채널 연결 시도
                connected |= connectChzzkForPlayer(playerName);
                
                // 숲 채널 연결 시도
                connected |= connectSoopForPlayer(playerName);
                
                if (connected) {
                    player.sendMessage(ChatColor.GREEN + "[SUDA] 설정 리로드 후 채널이 다시 연결되었습니다.");
                }
            } catch (Exception e) {
                Logger.error("[재연결] " + playerName + "님의 채널 연결 중 오류 발생: " + e.getMessage());
            }
        }
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

                case "add":
                    if (args.length < 5) {
                        Logger.error("옵션 누락. /done add <플랫폼> <방송닉> <방송ID> <마크닉>");
                        return false;
                    }
                    handleAddCommand(args);
                    return true;

                case "list":
                    Logger.info("=== 치지직 채널 목록 ===");
                    for (Map<String, String> user : chzzkUserList) {
                        Logger.info(user.get("nickname") + " (마크닉네임: " + user.get("tag") + ")");
                    }
                    Logger.info("=== 숲 채널 목록 ===");
                    for (Map<String, String> user : soopUserList) {
                        Logger.info(user.get("nickname") + " (마크닉네임: " + user.get("tag") + ")");
                    }
                    return true;

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

                case "connect":
                    handleConnectCommand(args);
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

                // autoconnect 명령어 추가 - 자동 연결 상태 토글
                case "autoconnect":
                    autoConnect = !autoConnect;
                    Logger.info(ChatColor.GREEN + "자동 연결이 " + (autoConnect ? "활성화" : "비활성화") + " 되었습니다.");
                    
                    // 설정 파일에도 상태 저장
                    FileConfiguration config = getConfig();
                    config.set("자동연결", autoConnect);
                    saveConfig();
                    
                    // 자동 연결 활성화로 전환 시 모든 채널 연결
                    if (autoConnect) {
                        Logger.info("모든 채널 연결을 시작합니다...");
                        connectChzzkList();
                        connectSoopList();
                    }
                    return true;

                case "test":
                    if (args.length < 4) {
                        sender.sendMessage(ChatColor.RED + "사용법: /done test <치지직/숲> <방송채널명> <후원금액>");
                        return false;
                    }
                    handleTestCommand(args, sender);
                    return true;

                case "ranking":
                    if (args.length < 2) {
                        sender.sendMessage(ChatColor.RED + "사용법: /done ranking <스트리머 플레이어> [페이지]");
                        return false;
                    }
                    handleRankingCommand(args, sender);
                    return true;

                case "stats":
                    if (args.length < 2) {
                        sender.sendMessage(ChatColor.RED + "사용법: /done stats <스트리머 플레이어> [주간/월간] [페이지]");
                        return false;
                    }
                    handleStatsCommand(args, sender);
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

    /**
     * 후원 테스트 명령어 처리
     */
    private void handleTestCommand(String[] args, CommandSender sender) {
        String platform = args[1];
        String channelName = args[2];
        String amountStr = args[3];
        
        // 후원금액 파싱
        int amount;
        try {
            amount = Integer.parseInt(amountStr);
        } catch (NumberFormatException e) {
            sender.sendMessage(ChatColor.RED + "후원금액은 숫자로 입력해주세요.");
            return;
        }
        
        // 플랫폼별 테스트 메시지 생성 및 후원 처리
        String testMessage;
        switch (platform.toLowerCase()) {
            case "치지직":
                testMessage = ChatColor.GREEN + "[치지직 테스트] " + 
                             ChatColor.WHITE + channelName + "님이 " + 
                             ChatColor.GOLD + amount + "원" + 
                             ChatColor.WHITE + "을 후원했습니다!";
                
                // 실제 후원 처리 로직 호출
                processTestDonation(channelName, amount, "", "치지직");
                break;
                
            case "숲":
                testMessage = ChatColor.BLUE + "[숲 테스트] " + 
                             ChatColor.WHITE + channelName + "님이 " + 
                             ChatColor.GOLD + amount + "원" + 
                             ChatColor.WHITE + "을 후원했습니다!";
                
                // 실제 후원 처리 로직 호출
                processTestDonation(channelName, amount, "", "숲");
                break;
                
            default:
                sender.sendMessage(ChatColor.RED + "지원하지 않는 플랫폼입니다. '치지직' 또는 '숲'을 입력해주세요.");
                return;
        }
        
        // 전체 서버에 테스트 메시지 전송
        Bukkit.broadcastMessage(testMessage);
        sender.sendMessage(ChatColor.GREEN + "후원 테스트가 완료되었습니다.");
        
        // 로그에도 기록
        Logger.info("후원 테스트 실행: " + platform + " - " + channelName + " - " + amount + "원");
    }

    /**
     * 테스트 후원 처리 - 기존 후원 로직과 동일하게 작동
     */
    private void processTestDonation(String nickname, int amount, String message, String platform) {
        // 기존 후원 처리와 동일한 로직
        Logger.info(ChatColor.YELLOW + nickname + ChatColor.WHITE + "님께서 " + 
                ChatColor.GREEN + amount + "원" + ChatColor.WHITE + "을 후원해주셨습니다.");
        
        // 플랫폼에 따른 태그 찾기
        String tag = findTagByNickname(nickname, platform);
        if (tag == null) {
            tag = nickname; // 태그를 찾을 수 없으면 닉네임 사용
        }
        
        // 플레이어 데이터 저장 (테스트 후원으로 구분)
        String streamerUuid = getPlayerUuid(tag);
        if (streamerUuid != null) {
            savePlayerData(streamerUuid, tag, nickname, amount, message, platform, true);
        }
        
        // 후원 보상 명령어 가져오기
        List<String> commands = donationRewards.get(amount);
        if (commands == null) {
            commands = donationRewards.get(0);
        }
        
        if (commands == null || commands.isEmpty()) {
            Logger.warn("후원 보상 명령어가 설정되지 않았습니다.");
            return;
        }
        
        // 명령어 실행
        if (random) {
            String command = commands.get(new Random().nextInt(commands.size()));
            executeTestCommand(tag, nickname, amount, message, command);
        } else {
            for (String command : commands) {
                executeTestCommand(tag, nickname, amount, message, command);
            }
        }
    }
    
    /**
     * 플랫폼과 닉네임으로 태그 찾기
     */
    private String findTagByNickname(String nickname, String platform) {
        if ("치지직".equalsIgnoreCase(platform)) {
            for (Map<String, String> user : chzzkUserList) {
                if (nickname.equalsIgnoreCase(user.get("nickname"))) {
                    return user.get("tag");
                }
            }
        } else if ("숲".equalsIgnoreCase(platform)) {
            for (Map<String, String> user : soopUserList) {
                if (nickname.equalsIgnoreCase(user.get("nickname"))) {
                    return user.get("tag");
                }
            }
        }
        return null;
    }
    
    /**
     * 테스트 후원 명령어 실행
     */
    private void executeTestCommand(String tag, String nickname, int amount, String message, String command) {
        String[] commandArray = command.split(";");
        for (String cmd : commandArray) {
            String finalCommand = cmd
                .replace("%tag%", tag)
                .replace("%name%", nickname)
                .replace("%amount%", String.valueOf(amount))
                .replace("%message%", message);

            try {
                // 비동기로 명령어 실행 (기존 후원 처리와 동일한 방식)
                Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                    return Bukkit.dispatchCommand(Bukkit.getConsoleSender(), finalCommand);
                });
            } catch (Exception e) {
                Logger.error("테스트 후원 명령어 실행 중 오류: " + e.getMessage());
            }
        }
    }

    /**
     * 랭킹 명령어 처리
     */
    private void handleRankingCommand(String[] args, CommandSender sender) {
        String playerName = args[1];
        int page = 1; // 기본 페이지는 1
        
        // 페이지 번호 파싱
        if (args.length > 2) {
            try {
                page = Integer.parseInt(args[2]);
                if (page < 1) {
                    sender.sendMessage(ChatColor.RED + "페이지 번호는 1 이상이어야 합니다.");
                    return;
                }
            } catch (NumberFormatException e) {
                sender.sendMessage(ChatColor.RED + "페이지 번호는 숫자로 입력해주세요.");
                return;
            }
        }
        
        // 플레이어 UUID 찾기
        String streamerUuid = getPlayerUuid(playerName);
        if (streamerUuid == null) {
            sender.sendMessage(ChatColor.RED + "플레이어 '" + playerName + "'를 찾을 수 없습니다. 서버에 접속한 적이 있는지 확인해주세요.");
            return;
        }
        
        // 랭킹 데이터 조회 (테스트 후원 제외)
        List<Map<String, Object>> ranking = getDonationRanking(streamerUuid, false);
        
        if (ranking.isEmpty()) {
            sender.sendMessage(ChatColor.YELLOW + playerName + "님의 후원 데이터가 없습니다.");
            return;
        }
        
        // 페이지네이션 처리
        int itemsPerPage = 10;
        int totalPages = (ranking.size() + itemsPerPage - 1) / itemsPerPage;
        
        if (page > totalPages) {
            sender.sendMessage(ChatColor.RED + "요청한 페이지(" + page + ")가 존재하지 않습니다. 총 " + totalPages + "페이지입니다.");
            return;
        }
        
        int startIndex = (page - 1) * itemsPerPage;
        int endIndex = Math.min(startIndex + itemsPerPage, ranking.size());
        
        // 랭킹 출력
        sender.sendMessage(ChatColor.GOLD + "=== " + playerName + "님의 후원 랭킹 (페이지 " + page + "/" + totalPages + ") ===");
        
        for (int i = startIndex; i < endIndex; i++) {
            Map<String, Object> donor = ranking.get(i);
            String donorName = (String) donor.get("donor_name");
            int totalAmount = (int) donor.get("total_amount");
            int totalCount = (int) donor.get("total_count");
            int rank = i + 1;
            
            String rankColor = getRankColor(rank);
            String amountFormatted = formatAmount(totalAmount);
            
            sender.sendMessage(rankColor + rank + "위. " + ChatColor.WHITE + donorName + 
                             ChatColor.GRAY + " - " + ChatColor.GREEN + amountFormatted + "원" + 
                             ChatColor.GRAY + " (" + totalCount + "회)");
        }
        
        if (page < totalPages) {
            sender.sendMessage(ChatColor.GRAY + "다음 페이지: /done ranking " + playerName + " " + (page + 1));
        }
    }
    
    /**
     * 순위에 따른 색상 반환
     */
    private String getRankColor(int rank) {
        switch (rank) {
            case 1: return ChatColor.GOLD + ""; // 금색
            case 2: return ChatColor.GRAY + ""; // 은색
            case 3: return ChatColor.YELLOW + ""; // 동색
            default: return ChatColor.WHITE + ""; // 흰색
        }
    }
    
    /**
     * 금액 포맷팅 (천 단위 콤마)
     */
    private String formatAmount(int amount) {
        return String.format("%,d", amount);
    }

    /**
     * 통계 명령어 처리
     */
    private void handleStatsCommand(String[] args, CommandSender sender) {
        String playerName = args[1];
        String period = "월간"; // 기본값은 월간
        int page = 1; // 기본 페이지는 1
        
        // 기간 옵션 파싱
        if (args.length > 2) {
            String arg2 = args[2].toLowerCase();
            if (arg2.equals("주간") || arg2.equals("weekly") || arg2.equals("week")) {
                period = "주간";
            } else if (arg2.equals("월간") || arg2.equals("monthly") || arg2.equals("month")) {
                period = "월간";
            } else {
                // 숫자로 시작하면 페이지 번호로 인식 (기존 호환성)
                try {
                    page = Integer.parseInt(args[2]);
                    if (page < 1) {
                        sender.sendMessage(ChatColor.RED + "페이지 번호는 1 이상이어야 합니다.");
                        return;
                    }
                } catch (NumberFormatException e) {
                    sender.sendMessage(ChatColor.RED + "올바른 기간을 입력해주세요. (주간/월간)");
                    return;
                }
            }
        }
        
        // 페이지 번호 파싱 (기간 옵션이 있으면 3번째 인수, 없으면 2번째 인수)
        if (args.length > 3) {
            try {
                page = Integer.parseInt(args[3]);
                if (page < 1) {
                    sender.sendMessage(ChatColor.RED + "페이지 번호는 1 이상이어야 합니다.");
                    return;
                }
            } catch (NumberFormatException e) {
                sender.sendMessage(ChatColor.RED + "페이지 번호는 숫자로 입력해주세요.");
                return;
            }
        }
        
        // 플레이어 UUID 찾기
        String streamerUuid = getPlayerUuid(playerName);
        if (streamerUuid == null) {
            sender.sendMessage(ChatColor.RED + "플레이어 '" + playerName + "'를 찾을 수 없습니다. 서버에 접속한 적이 있는지 확인해주세요.");
            return;
        }
        
        if (period.equals("주간")) {
            // 주간 데이터 조회
            WeeklyStats weeklyStats = getWeeklyStats(streamerUuid, page);
            
            if (weeklyStats == null || weeklyStats.donations.isEmpty()) {
                sender.sendMessage(ChatColor.YELLOW + playerName + "님의 " + weeklyStats.weekPeriod + " 후원 데이터가 없습니다.");
                return;
            }
            
            // 주간 요약 정보 표시
            sender.sendMessage(ChatColor.GOLD + "=== " + playerName + "님의 " + weeklyStats.weekPeriod + " 후원 통계 ===");
            sender.sendMessage(ChatColor.WHITE + "총 후원금액: " + ChatColor.GREEN + formatAmount(weeklyStats.totalAmount) + "원");
            sender.sendMessage(ChatColor.WHITE + "총 후원자 수: " + ChatColor.AQUA + weeklyStats.totalDonors + "명");
            sender.sendMessage(ChatColor.WHITE + "평균 후원금액: " + ChatColor.YELLOW + formatAmount(weeklyStats.averageAmount) + "원");
            sender.sendMessage("");
            
            // 주간 랭킹 표시
            sender.sendMessage(ChatColor.GOLD + "=== " + weeklyStats.weekPeriod + " 후원 랭킹 ===");
            
            for (int i = 0; i < weeklyStats.donations.size(); i++) {
                Map<String, Object> donor = weeklyStats.donations.get(i);
                String donorName = (String) donor.get("donor_name");
                int totalAmount = (int) donor.get("total_amount");
                int totalCount = (int) donor.get("total_count");
                int rank = i + 1;
                
                String rankColor = getRankColor(rank);
                String amountFormatted = formatAmount(totalAmount);
                
                sender.sendMessage(rankColor + rank + "위. " + ChatColor.WHITE + donorName + 
                                 ChatColor.GRAY + " - " + ChatColor.GREEN + amountFormatted + "원" + 
                                 ChatColor.GRAY + " (" + totalCount + "회)");
            }
            
            // 다음 페이지 안내
            WeeklyStats nextWeekStats = getWeeklyStats(streamerUuid, page + 1);
            if (nextWeekStats != null && !nextWeekStats.donations.isEmpty()) {
                sender.sendMessage(ChatColor.GRAY + "다음 페이지: /done stats " + playerName + " 주간 " + (page + 1) + " (" + nextWeekStats.weekPeriod + ")");
            }
            
        } else {
            // 월간 데이터 조회 (기존 로직)
            MonthlyStats monthlyStats = getMonthlyStats(streamerUuid, page);
            
            if (monthlyStats == null || monthlyStats.donations.isEmpty()) {
                sender.sendMessage(ChatColor.YELLOW + playerName + "님의 " + monthlyStats.monthYear + " 후원 데이터가 없습니다.");
                return;
            }
            
            // 월간 요약 정보 표시
            sender.sendMessage(ChatColor.GOLD + "=== " + playerName + "님의 " + monthlyStats.monthYear + " 후원 통계 ===");
            sender.sendMessage(ChatColor.WHITE + "총 후원금액: " + ChatColor.GREEN + formatAmount(monthlyStats.totalAmount) + "원");
            sender.sendMessage(ChatColor.WHITE + "총 후원자 수: " + ChatColor.AQUA + monthlyStats.totalDonors + "명");
            sender.sendMessage(ChatColor.WHITE + "평균 후원금액: " + ChatColor.YELLOW + formatAmount(monthlyStats.averageAmount) + "원");
            sender.sendMessage("");
            
            // 월간 랭킹 표시
            sender.sendMessage(ChatColor.GOLD + "=== " + monthlyStats.monthYear + " 후원 랭킹 ===");
            
            for (int i = 0; i < monthlyStats.donations.size(); i++) {
                Map<String, Object> donor = monthlyStats.donations.get(i);
                String donorName = (String) donor.get("donor_name");
                int totalAmount = (int) donor.get("total_amount");
                int totalCount = (int) donor.get("total_count");
                int rank = i + 1;
                
                String rankColor = getRankColor(rank);
                String amountFormatted = formatAmount(totalAmount);
                
                sender.sendMessage(rankColor + rank + "위. " + ChatColor.WHITE + donorName + 
                                 ChatColor.GRAY + " - " + ChatColor.GREEN + amountFormatted + "원" + 
                                 ChatColor.GRAY + " (" + totalCount + "회)");
            }
            
            // 다음 페이지 안내
            MonthlyStats nextMonthStats = getMonthlyStats(streamerUuid, page + 1);
            if (nextMonthStats != null && !nextMonthStats.donations.isEmpty()) {
                sender.sendMessage(ChatColor.GRAY + "다음 페이지: /done stats " + playerName + " 월간 " + (page + 1) + " (" + nextMonthStats.monthYear + ")");
            }
        }
    }
    
    /**
     * 월간 통계 데이터 클래스
     */
    private static class MonthlyStats {
        String monthYear;
        List<Map<String, Object>> donations;
        int totalAmount;
        int totalDonors;
        int averageAmount;
        
        MonthlyStats(String monthYear, List<Map<String, Object>> donations, int totalAmount, int totalDonors) {
            this.monthYear = monthYear;
            this.donations = donations;
            this.totalAmount = totalAmount;
            this.totalDonors = totalDonors;
            this.averageAmount = totalDonors > 0 ? totalAmount / totalDonors : 0;
        }
    }
    
    /**
     * 주간 통계 데이터 클래스
     */
    private static class WeeklyStats {
        String weekPeriod;
        List<Map<String, Object>> donations;
        int totalAmount;
        int totalDonors;
        int averageAmount;
        
        WeeklyStats(String weekPeriod, List<Map<String, Object>> donations, int totalAmount, int totalDonors) {
            this.weekPeriod = weekPeriod;
            this.donations = donations;
            this.totalAmount = totalAmount;
            this.totalDonors = totalDonors;
            this.averageAmount = totalDonors > 0 ? totalAmount / totalDonors : 0;
        }
    }
    
    /**
     * 월간 통계 조회
     */
    private MonthlyStats getMonthlyStats(String streamerUuid, int page) {
        try {
            FileConfiguration playerData = loadPlayerData(streamerUuid);
            if (playerData == null) {
                return null;
            }
            
            List<Map<String, Object>> allDonations = (List<Map<String, Object>>) playerData.getList("donations");
            if (allDonations == null || allDonations.isEmpty()) {
                return null;
            }
            
            // 테스트 후원 제외
            List<Map<String, Object>> realDonations = allDonations.stream()
                .filter(donation -> !(Boolean) donation.get("is_test"))
                .collect(Collectors.toList());
            
            if (realDonations.isEmpty()) {
                return null;
            }
            
            // 페이지에 해당하는 월 계산 (1페이지 = 이번 달, 2페이지 = 지난 달)
            LocalDateTime targetMonth = LocalDateTime.now().minusMonths(page - 1);
            int targetYear = targetMonth.getYear();
            int targetMonthValue = targetMonth.getMonthValue();
            
            // 해당 월의 후원 데이터 필터링
            List<Map<String, Object>> monthlyDonations = realDonations.stream()
                .filter(donation -> {
                    String timestamp = (String) donation.get("timestamp");
                    if (timestamp == null || timestamp.isEmpty()) {
                        return false;
                    }
                    
                    try {
                        LocalDateTime donationTime = LocalDateTime.parse(timestamp, 
                            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                        return donationTime.getYear() == targetYear && 
                               donationTime.getMonthValue() == targetMonthValue;
                    } catch (Exception e) {
                        return false;
                    }
                })
                .collect(Collectors.toList());
            
            if (monthlyDonations.isEmpty()) {
                return new MonthlyStats(
                    targetYear + "년 " + targetMonthValue + "월",
                    new ArrayList<>(),
                    0,
                    0
                );
            }
            
            // 후원자별로 그룹화하고 총 후원금액 계산
            Map<String, Map<String, Object>> donorStats = new HashMap<>();
            int totalAmount = 0;
            
            for (Map<String, Object> donation : monthlyDonations) {
                String donorName = (String) donation.get("donor_name");
                int amount = (int) donation.get("amount");
                totalAmount += amount;
                
                if (donorStats.containsKey(donorName)) {
                    Map<String, Object> stats = donorStats.get(donorName);
                    int donorTotalAmount = (int) stats.get("total_amount");
                    int donorTotalCount = (int) stats.get("total_count");
                    
                    stats.put("total_amount", donorTotalAmount + amount);
                    stats.put("total_count", donorTotalCount + 1);
                } else {
                    Map<String, Object> stats = new HashMap<>();
                    stats.put("donor_name", donorName);
                    stats.put("total_amount", amount);
                    stats.put("total_count", 1);
                    donorStats.put(donorName, stats);
                }
            }
            
            // 총 후원금액 순으로 정렬
            List<Map<String, Object>> sortedDonations = donorStats.values().stream()
                .sorted((a, b) -> Integer.compare((int) b.get("total_amount"), (int) a.get("total_amount")))
                .collect(Collectors.toList());
            
            return new MonthlyStats(
                targetYear + "년 " + targetMonthValue + "월",
                sortedDonations,
                totalAmount,
                donorStats.size()
            );
            
        } catch (Exception e) {
            Logger.error("월간 통계 조회 중 오류 발생: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * 주간 통계 조회
     */
    private WeeklyStats getWeeklyStats(String streamerUuid, int page) {
        try {
            FileConfiguration playerData = loadPlayerData(streamerUuid);
            if (playerData == null) {
                return null;
            }
            
            List<Map<String, Object>> allDonations = (List<Map<String, Object>>) playerData.getList("donations");
            if (allDonations == null || allDonations.isEmpty()) {
                return null;
            }
            
            // 테스트 후원 제외
            List<Map<String, Object>> realDonations = allDonations.stream()
                .filter(donation -> !(Boolean) donation.get("is_test"))
                .collect(Collectors.toList());
            
            if (realDonations.isEmpty()) {
                return null;
            }
            
            // 페이지에 해당하는 주 계산 (1페이지 = 이번 주, 2페이지 = 지난 주)
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime targetWeek = now.minusWeeks(page - 1);
            
            // 해당 주의 시작일 (월요일)과 종료일 (일요일) 계산
            LocalDateTime weekStart = targetWeek.minusDays(targetWeek.getDayOfWeek().getValue() - 1).withHour(0).withMinute(0).withSecond(0).withNano(0);
            LocalDateTime weekEnd = weekStart.plusDays(6).withHour(23).withMinute(59).withSecond(59).withNano(999999999);
            
            // 해당 주의 후원 데이터 필터링
            List<Map<String, Object>> weeklyDonations = realDonations.stream()
                .filter(donation -> {
                    String timestamp = (String) donation.get("timestamp");
                    if (timestamp == null || timestamp.isEmpty()) {
                        return false;
                    }
                    
                    try {
                        LocalDateTime donationTime = LocalDateTime.parse(timestamp, 
                            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                        return !donationTime.isBefore(weekStart) && !donationTime.isAfter(weekEnd);
                    } catch (Exception e) {
                        return false;
                    }
                })
                .collect(Collectors.toList());
            
            String weekPeriod = weekStart.format(DateTimeFormatter.ofPattern("yyyy년 M월 d일")) + " ~ " + 
                              weekEnd.format(DateTimeFormatter.ofPattern("M월 d일"));
            
            if (weeklyDonations.isEmpty()) {
                return new WeeklyStats(
                    weekPeriod,
                    new ArrayList<>(),
                    0,
                    0
                );
            }
            
            // 후원자별로 그룹화하고 총 후원금액 계산
            Map<String, Map<String, Object>> donorStats = new HashMap<>();
            int totalAmount = 0;
            
            for (Map<String, Object> donation : weeklyDonations) {
                String donorName = (String) donation.get("donor_name");
                int amount = (int) donation.get("amount");
                totalAmount += amount;
                
                if (donorStats.containsKey(donorName)) {
                    Map<String, Object> stats = donorStats.get(donorName);
                    int donorTotalAmount = (int) stats.get("total_amount");
                    int donorTotalCount = (int) stats.get("total_count");
                    
                    stats.put("total_amount", donorTotalAmount + amount);
                    stats.put("total_count", donorTotalCount + 1);
                } else {
                    Map<String, Object> stats = new HashMap<>();
                    stats.put("donor_name", donorName);
                    stats.put("total_amount", amount);
                    stats.put("total_count", 1);
                    donorStats.put(donorName, stats);
                }
            }
            
            // 총 후원금액 순으로 정렬
            List<Map<String, Object>> sortedDonations = donorStats.values().stream()
                .sorted((a, b) -> Integer.compare((int) b.get("total_amount"), (int) a.get("total_amount")))
                .collect(Collectors.toList());
            
            return new WeeklyStats(
                weekPeriod,
                sortedDonations,
                totalAmount,
                donorStats.size()
            );
            
        } catch (Exception e) {
            Logger.error("주간 통계 조회 중 오류 발생: " + e.getMessage());
            return null;
        }
    }

    private boolean connectChzzkForPlayer(String playerName) {
        for (Map<String, String> chzzkUser : chzzkUserList) {
            if (playerName.equalsIgnoreCase(chzzkUser.get("tag"))) {
                synchronized (chzzkLock) {
                    // 이미 연결된 웹소켓이 있는지 재확인
                    boolean alreadyConnected = chzzkWebSocketList.stream()
                        .anyMatch(ws -> ws.getChzzkUser().get("tag").equalsIgnoreCase(playerName));
                    
                    if (!alreadyConnected) {
                        Logger.info(ChatColor.GREEN + "[자동연결] " + playerName + 
                                "님의 치지직 채널을 연결합니다.");
                        boolean success = connectChzzk(chzzkUser);
                        
                        if (!success) {
                            // 실패 시 플레이어에게 알림
                            Bukkit.getScheduler().runTask(plugin, () -> {
                                Player player = Bukkit.getPlayer(playerName);
                                if (player != null && player.isOnline()) {
                                    player.sendMessage(ChatColor.YELLOW + 
                                        "[SUDA] 현재 방송 중이 아니어서 치지직 채널에 연결할 수 없습니다.");
                                }
                            });
                        }
                        return success;
                    } else {
                        Logger.debug("[자동연결] " + playerName + 
                                "님의 치지직 채널은 이미 연결되어 있습니다.");
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * 방송인 플레이어 개별 채널 연결 시도
     */
    private void handleConnectCommand(String[] args) {
        if (args.length < 2) {
            Logger.warn("채널명을 입력해주세요. 사용법: /done connect <치지직/숲> <닉네임>");
            return;
        }

        String platform = args[1];
        String targetNickname = args.length > 2 ? args[2] : null;

        if (targetNickname == null) {
            Logger.warn("닉네임을 입력해주세요.");
            return;
        }

        CompletableFuture.runAsync(() -> {
            try {
                boolean channelFound = false;
                
                if ("치지직".equals(platform)) {
                    for (Map<String, String> user : chzzkUserList) {
                        if (targetNickname.equalsIgnoreCase(user.get("nickname")) || 
                            targetNickname.equalsIgnoreCase(user.get("tag"))) {
                            Logger.info(ChatColor.GREEN + "치지직 채널 [" + user.get("nickname") + "] 연결을 시도합니다...");
                            if (connectChzzk(user)) {
                                Logger.info(ChatColor.GREEN + "채널 연결 성공!");
                                channelFound = true;
                            }
                            break;
                        }
                    }
                } else if ("숲".equals(platform)) {
                    for (Map<String, String> user : soopUserList) {
                        if (targetNickname.equalsIgnoreCase(user.get("nickname")) || 
                            targetNickname.equalsIgnoreCase(user.get("tag"))) {
                            Logger.info(ChatColor.GREEN + "숲 채널 [" + user.get("nickname") + "] 연결을 시도합니다...");
                            if (connectSoop(user)) {
                                Logger.info(ChatColor.GREEN + "채널 연결 성공!");
                                channelFound = true;
                            }
                            break;
                        }
                    }
                } else {
                    Logger.error("알 수 없는 플랫폼입니다. '치지직' 또는 '숲'을 입력해주세요.");
                    return;
                }

                if (!channelFound) {
                    Logger.warn("해당 닉네임의 채널을 찾을 수 없습니다.");
                }
                
            } catch (Exception e) {
                Logger.error("채널 연결 중 오류 발생: " + e.getMessage());
            }
        });
    }

    /**
     * 플레이어 접속 시 채널 자동 연결
     */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerJoin(PlayerJoinEvent event) {
        // 리로딩 중일 때는 자동 연결 건너뛰기
        if (isReloading) {
            Logger.debug("[자동연결] 현재 리로딩 중이므로 자동 연결을 건너뜁니다.");
            return;
        }
        
        Player player = event.getPlayer();
        String playerName = player.getName();
        
        CompletableFuture.runAsync(() -> {
            try {
                // 이미 연결된 웹소켓이 있는지 확인
                boolean hasExistingConnection = false;
                synchronized (chzzkLock) {
                    hasExistingConnection = chzzkWebSocketList.stream()
                        .anyMatch(ws -> ws.getChzzkUser().get("tag").equalsIgnoreCase(playerName));
                }
                
                if (!hasExistingConnection) {
                    boolean channelFound = false;
                    
                    // 치지직 채널 연결 시도
                    channelFound |= connectChzzkForPlayer(playerName);
                    
                    // 숲 채널 연결 시도
                    channelFound |= connectSoopForPlayer(playerName);
                    
                    if (channelFound) {
                        // 연결 성공 메시지를 메인 스레드에서 전송
                        Bukkit.getScheduler().runTask(plugin, () -> 
                            player.sendMessage(ChatColor.GREEN + "[SUDA] 채널이 자동으로 연결되었습니다.")
                        );
                    }
                }
            } catch (Exception e) {
                Logger.error("[자동연결] " + playerName + "님의 채널 연결 중 오류 발생: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    /**
     * 플레이어 퇴장 시 채널 자동 해제
     */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (isReloading) {
            Logger.debug("[자동해제] 현재 리로딩 중이므로 자동 해제를 건너뜁니다.");
            return;
        }

        Player player = event.getPlayer();
        String playerName = player.getName();

        CompletableFuture.runAsync(() -> {
            try {
                boolean channelFound = false;
                
                // 치지직 채널 해제
                synchronized (chzzkLock) {
                    List<ChzzkWebSocket> toDisconnect = chzzkWebSocketList.stream()
                        .filter(ws -> playerName.equalsIgnoreCase(ws.getChzzkUser().get("tag")))
                        .collect(Collectors.toList());
                    
                    if (!toDisconnect.isEmpty()) {
                        channelFound = true;
                        for (ChzzkWebSocket webSocket : toDisconnect) {
                            try {
                                Logger.info(ChatColor.YELLOW + "[자동해제] " + playerName + 
                                          "님의 치지직 채널 연결을 해제합니다.");
                                webSocket.shutdown();
                                chzzkWebSocketList.remove(webSocket);
                            } catch (Exception e) {
                                Logger.error("[자동해제] 치지직 채널 해제 중 오류: " + e.getMessage());
                            }
                        }
                    }
                }
                
                // 숲 채널 해제
                synchronized (soopLock) {
                    List<SoopWebSocket> toDisconnect = soopWebSocketList.stream()
                        .filter(ws -> playerName.equalsIgnoreCase(ws.getSoopUser().get("tag")))
                        .collect(Collectors.toList());
                    
                    if (!toDisconnect.isEmpty()) {
                        channelFound = true;
                        for (SoopWebSocket webSocket : toDisconnect) {
                            try {
                                Logger.info(ChatColor.YELLOW + "[자동해제] " + playerName + 
                                          "님의 숲 채널 연결을 해제합니다.");
                                webSocket.close();
                                soopWebSocketList.remove(webSocket);
                            } catch (Exception e) {
                                Logger.error("[자동해제] 숲 채널 해제 중 오류: " + e.getMessage());
                            }
                        }
                    }
                }
                
                if (channelFound) {
                    Logger.info(ChatColor.GREEN + "[자동해제] " + playerName + 
                              "님의 채널 연결이 해제되었습니다.");
                }
                
            } catch (Exception e) {
                Logger.error("[자동해제] " + playerName + "님의 채널 해제 중 오류 발생: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    /**
     * 플레이어의 숲 채널 연결 처리
     */
    private boolean connectSoopForPlayer(String playerName) {
        for (Map<String, String> soopUser : soopUserList) {
            if (playerName.equalsIgnoreCase(soopUser.get("tag"))) {
                synchronized (soopLock) {
                    boolean alreadyConnected = soopWebSocketList.stream()
                        .anyMatch(ws -> ws.getSoopUser().get("tag").equalsIgnoreCase(playerName));
                    
                    if (!alreadyConnected) {
                        Logger.info(ChatColor.GREEN + "[자동연결] " + playerName + 
                                  "님의 숲 채널을 연결합니다.");
                        return connectSoop(soopUser);
                    } else {
                        Logger.debug("[자동연결] " + playerName + 
                                   "님의 숲 채널은 이미 연결되어 있습니다.");
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public List<String> onTabComplete(CommandSender sender, @NotNull Command cmd, @NotNull String label, String[] args) {
        if (cmd.getName().equalsIgnoreCase("done") == false) {
            return Collections.emptyList();
        }

        if (sender.isOp() == false) {
            return Collections.emptyList();
        }

        if (args.length == 1) {
            List<String> commandList = new ArrayList<>(Arrays.asList("on", "off", "reconnect", "reload", "add", "connect", "list", "autoconnect", "test", "ranking", "stats"));

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

        if (args.length == 2 && args[0].equalsIgnoreCase("connect")) {
            return Arrays.asList("치지직", "숲");
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("test")) {
            return Arrays.asList("치지직", "숲");
        }

        if (args.length == 3 && args[0].equalsIgnoreCase("connect")) {
            List<String> nicknames = new ArrayList<>();
            if ("치지직".equalsIgnoreCase(args[1])) {
                chzzkUserList.forEach(user -> nicknames.add(user.get("nickname")));
            } else if ("숲".equalsIgnoreCase(args[1])) {
                soopUserList.forEach(user -> nicknames.add(user.get("nickname")));
            }
            return nicknames.stream()
                    .filter(name -> name.toLowerCase().startsWith(args[2].toLowerCase()))
                    .collect(Collectors.toList());
        }

        if (args.length == 3 && args[0].equalsIgnoreCase("test")) {
            List<String> nicknames = new ArrayList<>();
            if ("치지직".equalsIgnoreCase(args[1])) {
                chzzkUserList.forEach(user -> nicknames.add(user.get("nickname")));
            } else if ("숲".equalsIgnoreCase(args[1])) {
                soopUserList.forEach(user -> nicknames.add(user.get("nickname")));
            }
            return nicknames.stream()
                    .filter(name -> name.toLowerCase().startsWith(args[2].toLowerCase()))
                    .collect(Collectors.toList());
        }

        if (args.length == 4 && args[0].equalsIgnoreCase("test")) {
            List<String> amounts = Arrays.asList("1000", "3000", "5000", "10000", "50000", "100000");
            if (args[3].isEmpty()) {
                return amounts;
            } else {
                return amounts.stream()
                        .filter(amount -> amount.startsWith(args[3]))
                        .collect(Collectors.toList());
            }
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("ranking")) {
            if (args[1].isEmpty()) {
                return Bukkit.getOnlinePlayers().stream()
                        .map(Player::getName)
                        .collect(Collectors.toList());
            } else {
                return Bukkit.getOnlinePlayers().stream()
                        .map(Player::getName)
                        .filter(name -> name.toLowerCase().startsWith(args[1].toLowerCase()))
                        .collect(Collectors.toList());
            }
        }

        if (args.length == 3 && args[0].equalsIgnoreCase("ranking")) {
            List<String> pageNumbers = new ArrayList<>();
            for (int i = 1; i <= 10; i++) {
                pageNumbers.add(String.valueOf(i));
            }
            if (args[2].isEmpty()) {
                return pageNumbers;
            } else {
                return pageNumbers.stream()
                        .filter(page -> page.startsWith(args[2]))
                        .collect(Collectors.toList());
            }
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("stats")) {
            if (args[1].isEmpty()) {
                return Bukkit.getOnlinePlayers().stream()
                        .map(Player::getName)
                        .collect(Collectors.toList());
            } else {
                return Bukkit.getOnlinePlayers().stream()
                        .map(Player::getName)
                        .filter(name -> name.toLowerCase().startsWith(args[1].toLowerCase()))
                        .collect(Collectors.toList());
            }
        }

        if (args.length == 3 && args[0].equalsIgnoreCase("stats")) {
            // 기간 옵션 자동완성
            List<String> periods = Arrays.asList("주간", "월간", "weekly", "monthly");
            if (args[2].isEmpty()) {
                return periods;
            } else {
                return periods.stream()
                        .filter(period -> period.toLowerCase().startsWith(args[2].toLowerCase()))
                        .collect(Collectors.toList());
            }
        }

        if (args.length == 4 && args[0].equalsIgnoreCase("stats")) {
            List<String> pageNumbers = new ArrayList<>();
            for (int i = 1; i <= 12; i++) { // 최대 12개월/주 (1년)
                pageNumbers.add(String.valueOf(i));
            }
            if (args[3].isEmpty()) {
                return pageNumbers;
            } else {
                return pageNumbers.stream()
                        .filter(page -> page.startsWith(args[3]))
                        .collect(Collectors.toList());
            }
        }

        return Collections.emptyList();
    }

    /**
     * 플레이어 데이터 저장
     */
    public void savePlayerData(String streamerUuid, String streamerName, String donorName, int amount, String message, String platform, boolean isTest) {
        try {
            // 입력값 검증
            if (streamerUuid == null || streamerUuid.trim().isEmpty()) {
                Logger.warn("스트리머 UUID가 유효하지 않습니다: " + streamerUuid);
                return;
            }
            
            if (streamerName == null || streamerName.trim().isEmpty()) {
                Logger.warn("스트리머 이름이 유효하지 않습니다: " + streamerName);
                return;
            }
            
            if (donorName == null || donorName.trim().isEmpty()) {
                Logger.warn("후원자 이름이 유효하지 않습니다: " + donorName);
                return;
            }
            
            if (amount <= 0) {
                Logger.warn("후원 금액이 유효하지 않습니다: " + amount);
                return;
            }
            
            File dataDir = new File(getDataFolder(), "data");
            if (!dataDir.exists()) {
                boolean created = dataDir.mkdirs();
                if (!created) {
                    Logger.error("데이터 디렉토리 생성에 실패했습니다: " + dataDir.getAbsolutePath());
                    return;
                }
            }

            File playerFile = new File(dataDir, streamerUuid + ".yml");
            FileConfiguration playerConfig = YamlConfiguration.loadConfiguration(playerFile);

            // 플레이어 기본 정보 저장 (최초 1회만)
            if (!playerConfig.contains("streamer_name")) {
                playerConfig.set("streamer_name", streamerName);
                playerConfig.set("streamer_uuid", streamerUuid);
                playerConfig.set("created_at", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            }

            // 후원 데이터 저장
            List<Map<String, Object>> donations = new ArrayList<>();
            if (playerConfig.contains("donations")) {
                Object donationsObj = playerConfig.get("donations");
                if (donationsObj instanceof List) {
                    donations = (List<Map<String, Object>>) donationsObj;
                }
            }

            Map<String, Object> donation = new HashMap<>();
            donation.put("donor_name", donorName);
            donation.put("amount", amount);
            donation.put("message", message != null ? message : "");
            donation.put("platform", platform);
            donation.put("is_test", isTest);
            donation.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));

            donations.add(donation);
            playerConfig.set("donations", donations);

            // 통계 업데이트
            updatePlayerStats(playerConfig, amount, isTest);

            playerConfig.save(playerFile);
            Logger.debug("플레이어 데이터 저장 완료: " + streamerName + " - " + donorName + " - " + amount + "원");

        } catch (Exception e) {
            Logger.error("플레이어 데이터 저장 중 오류 발생: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 플레이어 통계 업데이트
     */
    private void updatePlayerStats(FileConfiguration config, int amount, boolean isTest) {
        String statsKey = isTest ? "test_stats" : "real_stats";
        
        Map<String, Object> stats = new HashMap<>();
        if (config.contains(statsKey)) {
            stats = (Map<String, Object>) config.getConfigurationSection(statsKey).getValues(false);
        }

        int totalDonations = (int) stats.getOrDefault("total_donations", 0);
        int totalAmount = (int) stats.getOrDefault("total_amount", 0);

        stats.put("total_donations", totalDonations + 1);
        stats.put("total_amount", totalAmount + amount);
        stats.put("last_updated", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));

        config.set(statsKey, stats);
    }

    /**
     * 플레이어 UUID 찾기 (온라인/오프라인 모두 지원)
     */
    public String getPlayerUuid(String playerName) {
        // 먼저 온라인 플레이어에서 찾기
        Player player = Bukkit.getPlayer(playerName);
        if (player != null) {
            return player.getUniqueId().toString();
        }
        
        // 오프라인 플레이어에서 찾기
        try {
            // Bukkit.getOfflinePlayer()를 사용하여 오프라인 플레이어도 조회 가능
            org.bukkit.OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(playerName);
            if (offlinePlayer.hasPlayedBefore()) {
                return offlinePlayer.getUniqueId().toString();
            }
        } catch (Exception e) {
            Logger.debug("오프라인 플레이어 조회 중 오류: " + e.getMessage());
        }
        
        return null;
    }

    /**
     * 플레이어 데이터 로드
     */
    public FileConfiguration loadPlayerData(String streamerUuid) {
        try {
            File dataDir = new File(getDataFolder(), "data");
            File playerFile = new File(dataDir, streamerUuid + ".yml");
            
            if (!playerFile.exists()) {
                return null;
            }
            
            return YamlConfiguration.loadConfiguration(playerFile);
        } catch (Exception e) {
            Logger.error("플레이어 데이터 로드 중 오류 발생: " + e.getMessage());
            return null;
        }
    }

    /**
     * 후원 랭킹 조회
     */
    public List<Map<String, Object>> getDonationRanking(String streamerUuid, boolean includeTest) {
        FileConfiguration playerData = loadPlayerData(streamerUuid);
        if (playerData == null) {
            return new ArrayList<>();
        }

        List<Map<String, Object>> donations = (List<Map<String, Object>>) playerData.getList("donations");
        if (donations == null || donations.isEmpty()) {
            return new ArrayList<>();
        }

        // 테스트 후원 포함 여부에 따라 필터링
        if (!includeTest) {
            donations = donations.stream()
                .filter(donation -> !(Boolean) donation.get("is_test"))
                .collect(Collectors.toList());
        }

        // 후원자별로 그룹화하고 총 후원금액 계산
        Map<String, Map<String, Object>> donorStats = new HashMap<>();
        
        for (Map<String, Object> donation : donations) {
            String donorName = (String) donation.get("donor_name");
            int amount = (int) donation.get("amount");
            
            if (donorStats.containsKey(donorName)) {
                Map<String, Object> stats = donorStats.get(donorName);
                int totalAmount = (int) stats.get("total_amount");
                int totalCount = (int) stats.get("total_count");
                
                stats.put("total_amount", totalAmount + amount);
                stats.put("total_count", totalCount + 1);
            } else {
                Map<String, Object> stats = new HashMap<>();
                stats.put("donor_name", donorName);
                stats.put("total_amount", amount);
                stats.put("total_count", 1);
                donorStats.put(donorName, stats);
            }
        }

        // 총 후원금액 순으로 정렬
        return donorStats.values().stream()
            .sorted((a, b) -> Integer.compare((int) b.get("total_amount"), (int) a.get("total_amount")))
            .collect(Collectors.toList());
    }
}
















