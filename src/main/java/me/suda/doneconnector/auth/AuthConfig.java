package me.suda.doneconnector.auth;

import me.suda.doneconnector.DoneConnector;
import me.suda.doneconnector.Logger;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;

/**
 * 인증 설정 관리 클래스
 * config.yml에서 인증 관련 설정을 로드하고 관리
 */
public class AuthConfig {
    
    private final DoneConnector plugin;
    private FileConfiguration config;
    
    // 기본 설정값들
    private static final String WEB_SERVER_URL = "https://gameboy.kr";
    private static final String TIMEZONE = "Asia/Seoul";
    private static final int AUTO_CHECK_TIME = 0; // 00:00
    private static final int PERIODIC_CHECK_INTERVAL = 60; // 60분
    private static final int GRACE_PERIOD = 30; // 30분
    private static final boolean STRICT_MODE = true;
    private static final int RETRY_ATTEMPTS = 3;
    private static final int RETRY_DELAY = 5; // 5초
    private static final boolean DEBUG_MODE = false;
    private static final boolean ENABLE_SSL_VERIFICATION = true;
    private static final boolean ENABLE_RATE_LIMITING = true;
    private static final boolean ENABLE_FILE_INTEGRITY_CHECK = true;
    
    // 설정 키들
    private static final String AUTH_ENABLED_KEY = "인증.활성화";
    private static final String WEB_SERVER_URL_KEY = "인증.웹서버_주소";
    private static final String TIMEZONE_KEY = "인증.시간대";
    private static final String AUTO_CHECK_TIME_KEY = "인증.자동_확인_시간";
    private static final String PERIODIC_CHECK_INTERVAL_KEY = "인증.주기적_확인_간격";
    private static final String GRACE_PERIOD_KEY = "인증.유예_기간";
    private static final String STRICT_MODE_KEY = "인증.엄격_모드";
    private static final String RETRY_ATTEMPTS_KEY = "인증.재시도_횟수";
    private static final String RETRY_DELAY_KEY = "인증.재시도_지연";
    private static final String DEBUG_MODE_KEY = "인증.디버그_모드";
    private static final String ENABLE_SSL_VERIFICATION_KEY = "인증.SSL_검증_활성화";
    private static final String ENABLE_RATE_LIMITING_KEY = "인증.속도_제한_활성화";
    private static final String ENABLE_FILE_INTEGRITY_CHECK_KEY = "인증.파일_무결성_검사_활성화";
    
    // 설정값 캐시
    private boolean authEnabled;
    private String webServerUrl;
    private String timezone;
    private int autoCheckTime;
    private int periodicCheckInterval;
    private int gracePeriod;
    private boolean strictMode;
    private int retryAttempts;
    private int retryDelay;
    private boolean debugMode;
    private boolean enableSSLVerification;
    private boolean enableRateLimiting;
    private boolean enableFileIntegrityCheck;
    
    public AuthConfig(DoneConnector plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfig();
    }
    
    /**
     * 설정 로드
     */
    public void loadConfig() {
        Logger.info("인증 설정을 로드합니다...");
        
        try {
            // config.yml 파일 확인 및 생성
            File configFile = new File(plugin.getDataFolder(), "config.yml");
            if (!configFile.exists()) {
                createDefaultConfig();
            }
            
            // 설정 리로드
            plugin.reloadConfig();
            config = plugin.getConfig();
            
            // 인증 설정 섹션 추가 (기존 설정이 없는 경우)
            addAuthSectionIfNotExists();
            
            // 설정값 로드
            loadAuthSettings();
            
            // 설정 저장
            saveConfig();
            
            Logger.info("인증 설정 로드 완료");
            
        } catch (Exception e) {
            Logger.error("인증 설정 로드 중 오류 발생: " + e.getMessage());
            e.printStackTrace();
            
            // 기본값 사용
            loadDefaultSettings();
        }
    }
    
    /**
     * 기본 설정 파일 생성
     */
    private void createDefaultConfig() {
        try {
            plugin.getDataFolder().mkdirs();
            plugin.saveDefaultConfig();
            Logger.info("기본 config.yml 파일이 생성되었습니다.");
        } catch (Exception e) {
            Logger.error("기본 설정 파일 생성 중 오류 발생: " + e.getMessage());
        }
    }
    
    /**
     * 인증 설정 섹션 추가 (기존 설정이 없는 경우)
     */
    private void addAuthSectionIfNotExists() {
        boolean needsSave = false;
        
        // 인증 섹션이 없으면 추가
        if (!config.contains("인증")) {
            config.createSection("인증");
            needsSave = true;
        }
        
        // 각 설정값이 없으면 기본값으로 추가
        if (!config.contains(AUTH_ENABLED_KEY)) {
            config.set(AUTH_ENABLED_KEY, true);
            needsSave = true;
        }
        
        if (!config.contains(WEB_SERVER_URL_KEY)) {
            config.set(WEB_SERVER_URL_KEY, WEB_SERVER_URL);
            needsSave = true;
        }
        
        if (!config.contains(TIMEZONE_KEY)) {
            config.set(TIMEZONE_KEY, TIMEZONE);
            needsSave = true;
        }
        
        if (!config.contains(AUTO_CHECK_TIME_KEY)) {
            config.set(AUTO_CHECK_TIME_KEY, AUTO_CHECK_TIME);
            needsSave = true;
        }
        
        if (!config.contains(PERIODIC_CHECK_INTERVAL_KEY)) {
            config.set(PERIODIC_CHECK_INTERVAL_KEY, PERIODIC_CHECK_INTERVAL);
            needsSave = true;
        }
        
        if (!config.contains(GRACE_PERIOD_KEY)) {
            config.set(GRACE_PERIOD_KEY, GRACE_PERIOD);
            needsSave = true;
        }
        
        if (!config.contains(STRICT_MODE_KEY)) {
            config.set(STRICT_MODE_KEY, STRICT_MODE);
            needsSave = true;
        }
        
        if (!config.contains(RETRY_ATTEMPTS_KEY)) {
            config.set(RETRY_ATTEMPTS_KEY, RETRY_ATTEMPTS);
            needsSave = true;
        }
        
        if (!config.contains(RETRY_DELAY_KEY)) {
            config.set(RETRY_DELAY_KEY, RETRY_DELAY);
            needsSave = true;
        }
        
        if (!config.contains(DEBUG_MODE_KEY)) {
            config.set(DEBUG_MODE_KEY, DEBUG_MODE);
            needsSave = true;
        }
        
        if (!config.contains(ENABLE_SSL_VERIFICATION_KEY)) {
            config.set(ENABLE_SSL_VERIFICATION_KEY, ENABLE_SSL_VERIFICATION);
            needsSave = true;
        }
        
        if (!config.contains(ENABLE_RATE_LIMITING_KEY)) {
            config.set(ENABLE_RATE_LIMITING_KEY, ENABLE_RATE_LIMITING);
            needsSave = true;
        }
        
        if (!config.contains(ENABLE_FILE_INTEGRITY_CHECK_KEY)) {
            config.set(ENABLE_FILE_INTEGRITY_CHECK_KEY, ENABLE_FILE_INTEGRITY_CHECK);
            needsSave = true;
        }
        
        if (needsSave) {
            saveConfig();
        }
    }
    
    /**
     * 인증 설정값 로드
     */
    private void loadAuthSettings() {
        authEnabled = config.getBoolean(AUTH_ENABLED_KEY, true);
        webServerUrl = config.getString(WEB_SERVER_URL_KEY, WEB_SERVER_URL);
        timezone = config.getString(TIMEZONE_KEY, TIMEZONE);
        autoCheckTime = config.getInt(AUTO_CHECK_TIME_KEY, AUTO_CHECK_TIME);
        periodicCheckInterval = config.getInt(PERIODIC_CHECK_INTERVAL_KEY, PERIODIC_CHECK_INTERVAL);
        gracePeriod = config.getInt(GRACE_PERIOD_KEY, GRACE_PERIOD);
        strictMode = config.getBoolean(STRICT_MODE_KEY, STRICT_MODE);
        retryAttempts = config.getInt(RETRY_ATTEMPTS_KEY, RETRY_ATTEMPTS);
        retryDelay = config.getInt(RETRY_DELAY_KEY, RETRY_DELAY);
        debugMode = config.getBoolean(DEBUG_MODE_KEY, DEBUG_MODE);
        enableSSLVerification = config.getBoolean(ENABLE_SSL_VERIFICATION_KEY, ENABLE_SSL_VERIFICATION);
        enableRateLimiting = config.getBoolean(ENABLE_RATE_LIMITING_KEY, ENABLE_RATE_LIMITING);
        enableFileIntegrityCheck = config.getBoolean(ENABLE_FILE_INTEGRITY_CHECK_KEY, ENABLE_FILE_INTEGRITY_CHECK);
        
        // 설정값 검증
        validateSettings();
        
        Logger.debug("인증 설정 로드 완료:");
        Logger.debug("  - 인증 활성화: " + authEnabled);
        Logger.debug("  - 웹서버 주소: " + webServerUrl);
        Logger.debug("  - 시간대: " + timezone);
        Logger.debug("  - 자동 확인 시간: " + autoCheckTime + "시");
        Logger.debug("  - 주기적 확인 간격: " + periodicCheckInterval + "분");
        Logger.debug("  - 유예 기간: " + gracePeriod + "분");
        Logger.debug("  - 엄격 모드: " + strictMode);
        Logger.debug("  - 재시도 횟수: " + retryAttempts);
        Logger.debug("  - 재시도 지연: " + retryDelay + "초");
        Logger.debug("  - 디버그 모드: " + debugMode);
        Logger.debug("  - SSL 검증: " + enableSSLVerification);
        Logger.debug("  - 속도 제한: " + enableRateLimiting);
        Logger.debug("  - 파일 무결성 검사: " + enableFileIntegrityCheck);
    }
    
    /**
     * 기본 설정값 로드
     */
    private void loadDefaultSettings() {
        authEnabled = true;
        webServerUrl = WEB_SERVER_URL;
        timezone = TIMEZONE;
        autoCheckTime = AUTO_CHECK_TIME;
        periodicCheckInterval = PERIODIC_CHECK_INTERVAL;
        gracePeriod = GRACE_PERIOD;
        strictMode = STRICT_MODE;
        retryAttempts = RETRY_ATTEMPTS;
        retryDelay = RETRY_DELAY;
        debugMode = DEBUG_MODE;
        enableSSLVerification = ENABLE_SSL_VERIFICATION;
        enableRateLimiting = ENABLE_RATE_LIMITING;
        enableFileIntegrityCheck = ENABLE_FILE_INTEGRITY_CHECK;
        
        Logger.warn("기본 인증 설정값을 사용합니다.");
    }
    
    /**
     * 설정값 검증
     */
    private void validateSettings() {
        // 웹서버 URL 검증
        if (webServerUrl == null || webServerUrl.trim().isEmpty()) {
            Logger.warn("웹서버 URL이 설정되지 않았습니다. 기본값을 사용합니다.");
            webServerUrl = WEB_SERVER_URL;
        }
        
        // 시간대 검증
        if (timezone == null || timezone.trim().isEmpty()) {
            Logger.warn("시간대가 설정되지 않았습니다. 기본값을 사용합니다.");
            timezone = TIMEZONE;
        }
        
        // 자동 확인 시간 검증 (0-23)
        if (autoCheckTime < 0 || autoCheckTime > 23) {
            Logger.warn("자동 확인 시간이 유효하지 않습니다 (0-23). 기본값을 사용합니다.");
            autoCheckTime = AUTO_CHECK_TIME;
        }
        
        // 주기적 확인 간격 검증 (1분 이상)
        if (periodicCheckInterval < 1) {
            Logger.warn("주기적 확인 간격이 유효하지 않습니다 (1분 이상). 기본값을 사용합니다.");
            periodicCheckInterval = PERIODIC_CHECK_INTERVAL;
        }
        
        // 유예 기간 검증 (0분 이상)
        if (gracePeriod < 0) {
            Logger.warn("유예 기간이 유효하지 않습니다 (0분 이상). 기본값을 사용합니다.");
            gracePeriod = GRACE_PERIOD;
        }
        
        // 재시도 횟수 검증 (0-10)
        if (retryAttempts < 0 || retryAttempts > 10) {
            Logger.warn("재시도 횟수가 유효하지 않습니다 (0-10). 기본값을 사용합니다.");
            retryAttempts = RETRY_ATTEMPTS;
        }
        
        // 재시도 지연 검증 (1초 이상)
        if (retryDelay < 1) {
            Logger.warn("재시도 지연이 유효하지 않습니다 (1초 이상). 기본값을 사용합니다.");
            retryDelay = RETRY_DELAY;
        }
    }
    
    /**
     * 설정 저장
     */
    private void saveConfig() {
        try {
            plugin.saveConfig();
        } catch (Exception e) {
            Logger.error("설정 저장 중 오류 발생: " + e.getMessage());
        }
    }
    
    /**
     * 설정값 업데이트
     */
    public void updateSetting(String key, Object value) {
        try {
            config.set(key, value);
            saveConfig();
            
            // 캐시된 값도 업데이트
            switch (key) {
                case AUTH_ENABLED_KEY:
                    authEnabled = (Boolean) value;
                    break;
                case WEB_SERVER_URL_KEY:
                    webServerUrl = (String) value;
                    break;
                case TIMEZONE_KEY:
                    timezone = (String) value;
                    break;
                case AUTO_CHECK_TIME_KEY:
                    autoCheckTime = (Integer) value;
                    break;
                case PERIODIC_CHECK_INTERVAL_KEY:
                    periodicCheckInterval = (Integer) value;
                    break;
                case GRACE_PERIOD_KEY:
                    gracePeriod = (Integer) value;
                    break;
                case STRICT_MODE_KEY:
                    strictMode = (Boolean) value;
                    break;
                case RETRY_ATTEMPTS_KEY:
                    retryAttempts = (Integer) value;
                    break;
                case RETRY_DELAY_KEY:
                    retryDelay = (Integer) value;
                    break;
                case DEBUG_MODE_KEY:
                    debugMode = (Boolean) value;
                    break;
                case ENABLE_SSL_VERIFICATION_KEY:
                    enableSSLVerification = (Boolean) value;
                    break;
                case ENABLE_RATE_LIMITING_KEY:
                    enableRateLimiting = (Boolean) value;
                    break;
                case ENABLE_FILE_INTEGRITY_CHECK_KEY:
                    enableFileIntegrityCheck = (Boolean) value;
                    break;
            }
            
            Logger.info("설정이 업데이트되었습니다: " + key + " = " + value);
            
        } catch (Exception e) {
            Logger.error("설정 업데이트 중 오류 발생: " + e.getMessage());
        }
    }
    
    // Getter 메서드들
    public boolean isAuthEnabled() {
        return authEnabled;
    }
    
    public String getWebServerUrl() {
        return webServerUrl;
    }
    
    public String getTimezone() {
        return timezone;
    }
    
    public int getAutoCheckTime() {
        return autoCheckTime;
    }
    
    public int getPeriodicCheckInterval() {
        return periodicCheckInterval;
    }
    
    public int getGracePeriod() {
        return gracePeriod;
    }
    
    public boolean isStrictMode() {
        return strictMode;
    }
    
    public int getRetryAttempts() {
        return retryAttempts;
    }
    
    public int getRetryDelay() {
        return retryDelay;
    }
    
    public boolean isDebugMode() {
        return debugMode;
    }
    
    public boolean isEnableSSLVerification() {
        return enableSSLVerification;
    }
    
    public boolean isEnableRateLimiting() {
        return enableRateLimiting;
    }
    
    public boolean isEnableFileIntegrityCheck() {
        return enableFileIntegrityCheck;
    }
    
    // API 엔드포인트 URL 생성
    public String getValidateApiUrl() {
        return webServerUrl + "/api/auth/validate";
    }
    
    public String getRegisterApiUrl() {
        return webServerUrl + "/api/auth/register";
    }
    
    public String getStatusApiUrl() {
        return webServerUrl + "/api/auth/status";
    }
}
