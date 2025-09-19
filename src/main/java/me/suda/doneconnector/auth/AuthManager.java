package me.suda.doneconnector.auth;

import me.suda.doneconnector.DoneConnector;
import me.suda.doneconnector.Logger;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.*;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 인증 관리자 클래스
 * 웹서버와의 인증 통신 및 로컬 인증키 관리를 담당
 */
public class AuthManager {
    
    private final DoneConnector plugin;
    private final AuthConfig authConfig;
    private final AuthWebClient webClient;
    private final File authKeyFile;
    private final File authStatusFile;
    
    private volatile boolean isAuthenticated = false;
    private volatile boolean isAuthenticationInProgress = false;
    private volatile String currentAuthKey = null;
    private volatile String currentServerInfo = null;
    private volatile Map<String, Object> currentServerInfoMap = null;
    
    // 자동 인증 스케줄러
    private BukkitTask dailyAuthTask = null;
    private BukkitTask periodicAuthTask = null;
    
    public AuthManager(DoneConnector plugin) {
        this.plugin = plugin;
        this.authConfig = new AuthConfig(plugin);
        this.webClient = new AuthWebClient(plugin, authConfig);
        
        // 인증 파일 경로 설정
        this.authKeyFile = new File(plugin.getDataFolder(), "auth.key");
        this.authStatusFile = new File(plugin.getDataFolder(), "auth_status.json");
        
        // 인증 상태 파일 초기화
        initializeAuthFiles();
    }
    
    /**
     * 인증 시스템 초기화
     */
    public void initialize() {
        Logger.info(ChatColor.YELLOW + "인증 시스템 초기화 중...");
        
        // 1. 설정 로드
        authConfig.loadConfig();
        
        // 2. 로컬 인증키 생성/로드
        loadOrGenerateAuthKey();
        
        // 3. 서버 정보 생성
        generateServerInfo();
        
        // 4. 플러그인 사용 알림 전송 (인증 여부와 상관없이)
        sendPluginUsageNotification();
        
        // 5. 초기 인증 시도 (실패 시 자동 등록)
        if (!performAuthentication()) {
            Logger.info(ChatColor.YELLOW + "인증 실패 - 자동으로 서버 등록을 시도합니다...");
            if (performRegistration()) {
                Logger.info(ChatColor.GREEN + "서버 자동 등록 완료 - 웹 대시보드에서 승인을 기다려주세요");
                Logger.info(ChatColor.YELLOW + "승인 후 '/done auth' 명령어로 인증을 시도하세요");
            } else {
                Logger.warn(ChatColor.RED + "서버 자동 등록 실패 - 네트워크 연결을 확인하세요");
            }
        }
        
        // 6. 자동 인증 스케줄러 시작
        startAuthSchedulers();
        
        Logger.info(ChatColor.GREEN + "인증 시스템 초기화 완료");
    }
    
    /**
     * 인증 파일들 초기화
     */
    private void initializeAuthFiles() {
        try {
            // auth.key 파일이 없으면 생성
            if (!authKeyFile.exists()) {
                authKeyFile.createNewFile();
                Logger.debug("auth.key 파일이 생성되었습니다.");
            }
            
            // auth_status.json 파일이 없으면 생성
            if (!authStatusFile.exists()) {
                createDefaultAuthStatus();
                Logger.debug("auth_status.json 파일이 생성되었습니다.");
            }
            
        } catch (IOException e) {
            Logger.error("인증 파일 초기화 중 오류 발생: " + e.getMessage());
        }
    }
    
    /**
     * 기본 인증 상태 파일 생성
     */
    private void createDefaultAuthStatus() {
        try {
            Map<String, Object> status = new HashMap<>();
            status.put("authenticated", false);
            status.put("last_auth_attempt", null);
            status.put("auth_failure_count", 0);
            status.put("server_info", null);
            status.put("file_hash", null);
            
            // JSON 파일로 저장
            String jsonContent = convertMapToJson(status);
            Files.write(authStatusFile.toPath(), jsonContent.getBytes(StandardCharsets.UTF_8));
            
        } catch (Exception e) {
            Logger.error("기본 인증 상태 파일 생성 중 오류: " + e.getMessage());
        }
    }
    
    /**
     * 로컬 인증키 로드 또는 생성
     */
    private void loadOrGenerateAuthKey() {
        try {
            if (authKeyFile.exists() && authKeyFile.length() > 0) {
                // 기존 키 로드
                currentAuthKey = Files.readString(authKeyFile.toPath(), StandardCharsets.UTF_8).trim();
                if (currentAuthKey.isEmpty()) {
                    generateNewAuthKey();
                } else {
                    Logger.info(ChatColor.GREEN + "기존 인증키를 로드했습니다.");
                }
            } else {
                // 새 키 생성
                generateNewAuthKey();
            }
        } catch (IOException e) {
            Logger.error("인증키 로드 중 오류 발생: " + e.getMessage());
            generateNewAuthKey();
        }
    }
    
    /**
     * 새로운 인증키 생성
     */
    private void generateNewAuthKey() {
        try {
            // UUID 기반 인증키 생성
            String uuid = UUID.randomUUID().toString().replace("-", "");
            String timestamp = String.valueOf(System.currentTimeMillis());
            String serverName = getServerName();
            
            // 복합 키 생성: UUID + 타임스탬프 + 서버명 해시
            String combined = uuid + timestamp + serverName;
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(combined.getBytes(StandardCharsets.UTF_8));
            
            // 32자리 키 생성
            StringBuilder keyBuilder = new StringBuilder();
            for (byte b : hash) {
                keyBuilder.append(String.format("%02x", b));
            }
            
            currentAuthKey = keyBuilder.toString().substring(0, 32);
            
            // 파일에 저장
            Files.write(authKeyFile.toPath(), currentAuthKey.getBytes(StandardCharsets.UTF_8));
            
            Logger.info(ChatColor.GREEN + "새로운 인증키가 생성되었습니다.");
            
        } catch (Exception e) {
            Logger.error("인증키 생성 중 오류 발생: " + e.getMessage());
            // 기본 UUID 사용
            currentAuthKey = UUID.randomUUID().toString().replace("-", "");
        }
    }
    
    /**
     * 서버 정보 생성
     */
    private void generateServerInfo() {
        try {
            Map<String, Object> serverInfo = new HashMap<>();
            
            // 서버명
            serverInfo.put("server_name", getServerName());
            
            // 외부 IP 주소 (필수) - 실패 시 예외 발생
            String externalIp = getExternalIP();
            serverInfo.put("server_ip", externalIp);
            
            // 서버 포트
            int port = plugin.getServer().getPort();
            serverInfo.put("server_port", port);
            
            // 서버 버전 (더 정확한 정보)
            String serverVersion = plugin.getServer().getVersion();
            if (serverVersion == null || serverVersion.trim().isEmpty() || serverVersion.equals("null")) {
                serverVersion = plugin.getServer().getBukkitVersion();
                if (serverVersion == null || serverVersion.trim().isEmpty()) {
                    serverVersion = "Unknown";
                }
            }
            serverInfo.put("server_version", serverVersion);
            
            // 플러그인 버전
            serverInfo.put("plugin_version", plugin.getDescription().getVersion());
            
            // 생성 시간
            serverInfo.put("created_at", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            
            // 운영자 정보 (선택사항)
            serverInfo.put("operator_name", "Server Admin");
            
            // 서버 정보를 Map으로 저장 (JSON 파싱 오류 방지)
            currentServerInfoMap = new HashMap<>(serverInfo);
            
            // JSON 문자열로도 변환 (호환성)
            org.json.simple.JSONObject jsonObj = new org.json.simple.JSONObject();
            for (Map.Entry<String, Object> entry : serverInfo.entrySet()) {
                jsonObj.put(entry.getKey(), entry.getValue());
            }
            currentServerInfo = jsonObj.toJSONString();
            
            Logger.info(ChatColor.GREEN + "서버 정보가 생성되었습니다: " + serverInfo.get("server_name") + " (" + externalIp + ")");
            
        } catch (Exception e) {
            Logger.error(ChatColor.RED + "서버 정보 생성 중 오류 발생: " + e.getMessage());
            throw new RuntimeException("외부 IP 조회 실패로 인한 서버 정보 생성 실패", e);
        }
    }
    
    /**
     * 외부 IP 주소 조회 (내부 IP 차단) - 병렬 처리로 최적화
     */
    private String getExternalIP() {
        Logger.info(ChatColor.YELLOW + "외부 IP 주소를 조회합니다...");
        
        try {
            // 안정적이고 믿을만한 외부 IP 조회 서비스들 (오랫동안 운영 중)
            String[] services = {
                "https://api.ipify.org/",           // Ipify - 2013년부터 운영, 매우 안정적
                "http://checkip.amazonaws.com/",    // Amazon AWS - 2006년부터 운영
                "http://icanhazip.com/",           // Major League Baseball - 2009년부터 운영
                "https://ipv4.icanhazip.com/",     // IPv4 전용 버전
                "http://ident.me/"                 // 2010년부터 운영
            };
            
            // 병렬 처리를 위한 CompletableFuture 리스트
            List<CompletableFuture<String>> futures = new ArrayList<>();
            
            for (String service : services) {
                CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
                    try {
                        Logger.debug("IP 조회 서비스 시도: " + service);
                        
                        java.net.URL url = new java.net.URL(service);
                        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                        conn.setConnectTimeout(5000); // 타임아웃 단축 (병렬이므로)
                        conn.setReadTimeout(5000);
                        conn.setRequestMethod("GET");
                        conn.setRequestProperty("User-Agent", "DoneConnector/" + plugin.getDescription().getVersion());
                        
                        try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                            String ip = reader.readLine().trim();
                            
                            // IP 유효성 및 내부 IP 차단 검사
                            if (isValidExternalIP(ip)) {
                                // 외부 IP 조회 성공 (디버그 출력 제거)
                                return ip;
                            } else {
                                Logger.debug("내부 IP 차단됨: " + ip + " (서비스: " + service + ")");
                                throw new RuntimeException("내부 IP 차단");
                            }
                        }
                    } catch (Exception e) {
                        Logger.debug("IP 조회 서비스 실패: " + service + " - " + e.getMessage());
                        throw new RuntimeException("서비스 실패: " + e.getMessage());
                    }
                });
                
                futures.add(future);
            }
            
            // 첫 번째 성공한 결과 반환 (anyOf 사용)
            try {
                CompletableFuture<Object> anyOf = CompletableFuture.anyOf(futures.toArray(new CompletableFuture[0]));
                String result = (String) anyOf.get(15, TimeUnit.SECONDS); // 15초 타임아웃
                
                // 나머지 작업들 취소 (리소스 절약)
                futures.forEach(f -> f.cancel(true));
                
                return result;
                
            } catch (Exception e) {
                // 병렬 처리 실패 시 기존 방식으로 폴백
                Logger.warn("병렬 IP 조회 실패, 순차 조회로 전환...");
                
                for (String service : services) {
                    try {
                        java.net.URL url = new java.net.URL(service);
                        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                        conn.setConnectTimeout(3000);
                        conn.setReadTimeout(3000);
                        conn.setRequestMethod("GET");
                        conn.setRequestProperty("User-Agent", "DoneConnector/" + plugin.getDescription().getVersion());
                        
                        try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                            String ip = reader.readLine().trim();
                            if (isValidExternalIP(ip)) {
                                Logger.info(ChatColor.GREEN + "외부 IP 조회 성공: " + ip);
                                return ip;
                            }
                        }
                    } catch (Exception ex) {
                        Logger.debug("순차 IP 조회 실패: " + service);
                    }
                }
            }
            
            // 모든 서비스 실패 시 오류 발생
            Logger.error(ChatColor.RED + "외부 IP 조회에 실패했습니다!");
            Logger.error(ChatColor.RED + "내부 IP(192.168.x.x)는 사용할 수 없습니다.");
            Logger.error(ChatColor.YELLOW + "인터넷 연결을 확인하고 다시 시도해주세요.");
            
            throw new RuntimeException("외부 IP 조회 실패 - 모든 IP 조회 서비스가 실패했습니다.");
            
        } catch (Exception e) {
            Logger.error("IP 주소 조회 중 오류 발생: " + e.getMessage());
            throw new RuntimeException("외부 IP 조회 중 오류: " + e.getMessage());
        }
    }
    
    /**
     * IP 주소 유효성 검증 (기본)
     */
    private boolean isValidIP(String ip) {
        if (ip == null || ip.trim().isEmpty()) {
            return false;
        }
        
        // IPv4 패턴 검증
        String[] parts = ip.split("\\.");
        if (parts.length != 4) {
            return false;
        }
        
        try {
            for (String part : parts) {
                int num = Integer.parseInt(part);
                if (num < 0 || num > 255) {
                    return false;
                }
            }
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
    
    /**
     * 외부 IP 유효성 검증 (내부 IP 차단)
     */
    private boolean isValidExternalIP(String ip) {
        if (!isValidIP(ip)) {
            return false;
        }
        
        // 내부 IP 대역 차단
        if (isPrivateIP(ip)) {
            Logger.warn(ChatColor.RED + "내부 IP가 감지되었습니다: " + ip);
            Logger.warn(ChatColor.YELLOW + "내부 IP는 서버 등록에 사용할 수 없습니다.");
            return false;
        }
        
        // 루프백 주소 차단
        if (ip.equals("127.0.0.1") || ip.equals("localhost")) {
            Logger.warn(ChatColor.RED + "루프백 주소가 감지되었습니다: " + ip);
            return false;
        }
        
        return true;
    }
    
    /**
     * 사설 IP 대역 확인
     */
    private boolean isPrivateIP(String ip) {
        try {
            String[] parts = ip.split("\\.");
            if (parts.length != 4) {
                return false;
            }
            
            int first = Integer.parseInt(parts[0]);
            int second = Integer.parseInt(parts[1]);
            
            // 192.168.x.x 대역
            if (first == 192 && second == 168) {
                return true;
            }
            
            // 10.x.x.x 대역
            if (first == 10) {
                return true;
            }
            
            // 172.16.x.x ~ 172.31.x.x 대역
            if (first == 172 && second >= 16 && second <= 31) {
                return true;
            }
            
            // 169.254.x.x (링크 로컬) 대역
            if (first == 169 && second == 254) {
                return true;
            }
            
            return false;
            
        } catch (NumberFormatException e) {
            return false;
        }
    }
    
    /**
     * 서버명 조회 - server.properties motd 강제 사용
     */
    private String getServerName() {
        try {
            String serverName = null;
            
            // server.properties에서 motd 강제로 가져오기
            try {
                File serverProperties = new File("server.properties");
                if (serverProperties.exists()) {
                    List<String> lines = Files.readAllLines(serverProperties.toPath(), StandardCharsets.UTF_8);
                    for (String line : lines) {
                        if (line.startsWith("motd=")) {
                            serverName = line.substring(5).trim();
                            // 색상 코드 및 특수 문자 제거
                            serverName = serverName.replaceAll("§[0-9a-fk-or]", "")
                                                 .replaceAll("\\\\n", " ")
                                                 .replaceAll("[\\r\\n]", " ")
                                                 .trim();
                            if (!serverName.isEmpty()) {
                                break;
                            }
                        }
                    }
                } else {
                    Logger.warn(ChatColor.YELLOW + "server.properties 파일이 없습니다.");
                }
            } catch (Exception e) {
                Logger.error("server.properties 읽기 실패: " + e.getMessage());
            }
            
            // motd가 없거나 비어있으면 오류 발생
            if (serverName == null || serverName.trim().isEmpty()) {
                Logger.error(ChatColor.RED + "server.properties에 motd가 설정되지 않았습니다!");
                Logger.error(ChatColor.YELLOW + "server.properties에 다음과 같이 설정해주세요:");
                Logger.error(ChatColor.WHITE + "motd=Your Server Name");
                throw new RuntimeException("server.properties motd 설정 필요");
            }
            
            // 최종 검증 및 정리
            serverName = serverName.trim();
            if (serverName.length() > 50) {
                serverName = serverName.substring(0, 50) + "...";
            }
            
            return serverName;
            
        } catch (Exception e) {
            Logger.error("서버명 조회 중 오류 발생: " + e.getMessage());
            throw new RuntimeException("서버명 조회 실패: " + e.getMessage());
        }
    }
    
    /**
     * 인증 수행 (동기) - 단순한 성공/실패 처리
     */
    public boolean performAuthentication() {
        if (isAuthenticationInProgress) {
            Logger.warn("인증이 이미 진행 중입니다.");
            return isAuthenticated; // 현재 상태 반환
        }
        
        isAuthenticationInProgress = true;
        
        try {
            Logger.debug("웹서버 인증을 시도합니다...");
            
            // 웹서버에 인증 요청
            AuthWebClient.AuthResult result = webClient.validateAuthentication(currentAuthKey, currentServerInfo);
            
            // 단순한 성공/실패 처리
            if (result.isSuccess()) {
                // 인증 성공 -> 플러그인 기능 활성화
                isAuthenticated = true;
                updateAuthStatus(true, "인증 성공");
                return true;
            } else {
                // 인증 실패 -> 플러그인 기능 비활성화
                isAuthenticated = false;
                updateAuthStatus(false, "인증 실패");
                return false;
            }
            
        } catch (Exception e) {
            // 오류 발생 -> 플러그인 기능 비활성화
            isAuthenticated = false;
            updateAuthStatus(false, "인증 오류");
            Logger.debug("인증 수행 중 오류 발생: " + e.getMessage());
            return false;
        } finally {
            isAuthenticationInProgress = false;
        }
    }
    
    /**
     * 인증 수행 (비동기)
     */
    public CompletableFuture<Boolean> performAuthenticationAsync() {
        return CompletableFuture.supplyAsync(() -> {
            return performAuthentication();
        }, plugin.getServer().getScheduler().getMainThreadExecutor(plugin));
    }
    
    /**
     * 인증 상태 업데이트
     */
    private void updateAuthStatus(boolean authenticated, String message) {
        try {
            Map<String, Object> status = new HashMap<>();
            status.put("authenticated", authenticated);
            status.put("last_auth_attempt", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            status.put("auth_failure_count", authenticated ? 0 : getAuthFailureCount() + 1);
            status.put("server_info", currentServerInfo);
            
            // 파일 무결성 해시 생성
            String fileHash = generateFileHash();
            status.put("file_hash", fileHash);
            
            // JSON 파일로 저장
            String jsonContent = convertMapToJson(status);
            Files.write(authStatusFile.toPath(), jsonContent.getBytes(StandardCharsets.UTF_8));
            
            Logger.debug("인증 상태가 업데이트되었습니다: " + (authenticated ? "성공" : "실패"));
            
        } catch (Exception e) {
            Logger.error("인증 상태 업데이트 중 오류 발생: " + e.getMessage());
        }
    }
    
    /**
     * 인증 실패 횟수 조회
     */
    private int getAuthFailureCount() {
        try {
            if (authStatusFile.exists()) {
                String content = Files.readString(authStatusFile.toPath(), StandardCharsets.UTF_8);
                Map<String, Object> status = convertJsonToMap(content);
                return (int) status.getOrDefault("auth_failure_count", 0);
            }
        } catch (Exception e) {
            Logger.debug("인증 실패 횟수 조회 중 오류: " + e.getMessage());
        }
        return 0;
    }
    
    /**
     * 파일 무결성 해시 생성
     */
    private String generateFileHash() {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            String content = currentAuthKey + currentServerInfo + System.currentTimeMillis();
            byte[] hash = md.digest(content.getBytes(StandardCharsets.UTF_8));
            
            StringBuilder hashBuilder = new StringBuilder();
            for (byte b : hash) {
                hashBuilder.append(String.format("%02x", b));
            }
            
            return hashBuilder.toString();
            
        } catch (NoSuchAlgorithmException e) {
            Logger.error("해시 생성 중 오류 발생: " + e.getMessage());
            return "unknown";
        }
    }
    
    /**
     * 자동 인증 스케줄러 시작
     */
    private void startAuthSchedulers() {
        // 1. 일일 인증 (00:00 KST)
        startDailyAuthScheduler();
        
        // 2. 주기적 인증 (설정된 간격)
        startPeriodicAuthScheduler();
    }
    
    /**
     * 일일 인증 스케줄러 시작
     */
    private void startDailyAuthScheduler() {
        if (dailyAuthTask != null) {
            dailyAuthTask.cancel();
        }
        
        // 다음 00:00 KST까지의 시간 계산
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Seoul"));
        LocalDateTime nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay();
        long delay = java.time.Duration.between(now, nextMidnight).getSeconds() * 20; // 틱으로 변환
        
        dailyAuthTask = new BukkitRunnable() {
            @Override
            public void run() {
                Logger.info(ChatColor.YELLOW + "매일 0시 인증 확인을 시작합니다...");
                performAuthenticationAsync().thenAccept(success -> {
                    if (success) {
                        Logger.info(ChatColor.GREEN + "매일 0시 인증 확인 완료");
                    } else {
                        Logger.warn(ChatColor.RED + "매일 0시 인증 확인 실패 - 플러그인 기능 비활성화");
                        isAuthenticated = false;
                    }
                });
            }
        }.runTaskTimer(plugin, delay, 20 * 60 * 60 * 24); // 24시간마다 반복
        
        Logger.debug("일일 인증 스케줄러가 시작되었습니다. 다음 실행: " + nextMidnight.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
    }
    
    /**
     * 주기적 인증 스케줄러 시작 (1시간마다)
     */
    private void startPeriodicAuthScheduler() {
        if (periodicAuthTask != null) {
            periodicAuthTask.cancel();
        }
        
        // 1시간마다 인증 확인 (고정값)
        long ticks = 60 * 60 * 20; // 1시간을 틱으로 변환
        
        periodicAuthTask = new BukkitRunnable() {
            @Override
            public void run() {
                Logger.info(ChatColor.YELLOW + "1시간마다 인증 확인 중...");
                performAuthenticationAsync().thenAccept(success -> {
                    if (success) {
                        Logger.info(ChatColor.GREEN + "정기 인증 확인 완료");
                    } else {
                        Logger.warn(ChatColor.RED + "정기 인증 확인 실패 - 플러그인 기능 비활성화");
                        isAuthenticated = false;
                    }
                });
            }
        }.runTaskTimer(plugin, ticks, ticks);
        
        Logger.info(ChatColor.GREEN + "1시간마다 정기 인증 스케줄러가 시작되었습니다.");
    }
    
    /**
     * 인증 시스템 종료
     */
    public void shutdown() {
        if (dailyAuthTask != null) {
            dailyAuthTask.cancel();
            dailyAuthTask = null;
        }
        
        if (periodicAuthTask != null) {
            periodicAuthTask.cancel();
            periodicAuthTask = null;
        }
        
        Logger.info(ChatColor.YELLOW + "인증 시스템이 종료되었습니다.");
    }
    
    // Getter 메서드들
    public boolean isAuthenticated() {
        return isAuthenticated;
    }
    
    public boolean isAuthenticationInProgress() {
        return isAuthenticationInProgress;
    }
    
    public String getCurrentAuthKey() {
        return currentAuthKey;
    }
    
    public String getCurrentServerInfo() {
        return currentServerInfo;
    }
    
    /**
     * Map을 JSON 문자열로 변환
     */
    private String convertMapToJson(Map<String, Object> map) {
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        
        boolean first = true;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (!first) {
                json.append(",\n");
            }
            first = false;
            
            json.append("  \"").append(entry.getKey()).append("\": ");
            
            Object value = entry.getValue();
            if (value instanceof String) {
                json.append("\"").append(value).append("\"");
            } else if (value instanceof Boolean || value instanceof Number) {
                json.append(value);
            } else if (value == null) {
                json.append("null");
            } else {
                json.append("\"").append(value.toString()).append("\"");
            }
        }
        
        json.append("\n}");
        return json.toString();
    }
    
    /**
     * JSON 문자열을 Map으로 변환 (간단한 구현)
     */
    private Map<String, Object> convertJsonToMap(String json) {
        Map<String, Object> map = new HashMap<>();
        
        try {
            // 간단한 JSON 파싱 (실제로는 JSON 라이브러리 사용 권장)
            json = json.trim().replaceAll("^\\{|\\}$", "");
            String[] pairs = json.split(",");
            
            for (String pair : pairs) {
                String[] keyValue = pair.split(":", 2);
                if (keyValue.length == 2) {
                    String key = keyValue[0].trim().replaceAll("^\"|\"$", "");
                    String value = keyValue[1].trim();
                    
                    if (value.startsWith("\"") && value.endsWith("\"")) {
                        map.put(key, value.substring(1, value.length() - 1));
                    } else if ("true".equals(value) || "false".equals(value)) {
                        map.put(key, Boolean.valueOf(value));
                    } else if ("null".equals(value)) {
                        map.put(key, null);
                    } else {
                        try {
                            map.put(key, Integer.valueOf(value));
                        } catch (NumberFormatException e) {
                            map.put(key, value);
                        }
                    }
                }
            }
        } catch (Exception e) {
            Logger.error("JSON 파싱 중 오류 발생: " + e.getMessage());
        }
        
        return map;
    }
    
    /**
     * 서버 등록 수행 - 단순한 성공/실패 처리
     */
    public boolean performRegistration() {
        if (isAuthenticationInProgress) {
            Logger.warn("인증이 이미 진행 중입니다.");
            return false;
        }
        
        isAuthenticationInProgress = true;
        
        try {
            Logger.info(ChatColor.YELLOW + "웹서버에 서버 등록을 시도합니다...");
            
            // 웹서버에 서버 등록 요청
            AuthWebClient.AuthResult result = webClient.registerServer(currentAuthKey, currentServerInfo);
            
            // 단순한 성공/실패 처리
            if (result.isSuccess()) {
                // 등록 성공
                updateAuthStatus(false, "서버 등록 완료");
                Logger.info(ChatColor.GREEN + "서버 등록 성공 - 웹 대시보드에서 승인을 기다려주세요");
                return true;
            } else {
                // 등록 실패
                updateAuthStatus(false, "서버 등록 실패");
                Logger.warn(ChatColor.RED + "서버 등록 실패");
                return false;
            }
            
        } catch (Exception e) {
            updateAuthStatus(false, "서버 등록 오류");
            Logger.error("서버 등록 중 오류 발생: " + e.getMessage());
            return false;
        } finally {
            isAuthenticationInProgress = false;
        }
    }
    
    /**
     * 플러그인 사용 알림 전송
     */
    private void sendPluginUsageNotification() {
        try {
            Logger.info(ChatColor.YELLOW + "웹서버에 플러그인 사용 정보를 전송합니다...");
            
            // 비동기로 플러그인 사용 알림 전송 (인증 여부와 상관없이)
            CompletableFuture.runAsync(() -> {
                try {
                    AuthWebClient.AuthResult result = webClient.sendPluginUsageNotification(currentAuthKey, currentServerInfoMap, "plugin_loaded");
                    
                    if (result.isSuccess()) {
                        Logger.info(ChatColor.GREEN + "플러그인 사용 정보 전송 성공");
                    } else {
                        Logger.warn(ChatColor.YELLOW + "플러그인 사용 정보 전송 실패: " + result.getMessage());
                    }
                } catch (Exception e) {
                    Logger.error("플러그인 사용 정보 전송 중 오류: " + e.getMessage());
                }
            });
            
        } catch (Exception e) {
            Logger.error("플러그인 사용 알림 전송 중 오류 발생: " + e.getMessage());
        }
    }
    
    /**
     * AuthConfig 반환
     */
    public AuthConfig getAuthConfig() {
        return authConfig;
    }
}
