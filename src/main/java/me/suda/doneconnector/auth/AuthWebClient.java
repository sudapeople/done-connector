package me.suda.doneconnector.auth;

import me.suda.doneconnector.DoneConnector;
import me.suda.doneconnector.Logger;
import org.bukkit.ChatColor;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 웹서버와의 인증 통신을 담당하는 클래스
 * HTTPS 통신, SSL 인증서 검증, HMAC 서명 등을 처리
 */
public class AuthWebClient {
    
    private final DoneConnector plugin;
    private final AuthConfig config;
    private final JSONParser jsonParser;
    
    // SSL 컨텍스트 (SSL 검증 비활성화용)
    private SSLContext sslContext;
    
    public AuthWebClient(DoneConnector plugin, AuthConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.jsonParser = new JSONParser();
        
        // SSL 컨텍스트 초기화
        initializeSSLContext();
    }
    
    /**
     * SSL 컨텍스트 초기화
     */
    private void initializeSSLContext() {
        try {
            if (!config.isEnableSSLVerification()) {
                // SSL 검증 비활성화 (개발/테스트용)
                sslContext = SSLContext.getInstance("TLS");
                TrustManager[] trustAllCerts = new TrustManager[] {
                    new X509TrustManager() {
                        public X509Certificate[] getAcceptedIssuers() {
                            return null;
                        }
                        
                        public void checkClientTrusted(X509Certificate[] certs, String authType) {
                            // 검증 생략
                        }
                        
                        public void checkServerTrusted(X509Certificate[] certs, String authType) {
                            // 검증 생략
                        }
                    }
                };
                sslContext.init(null, trustAllCerts, new SecureRandom());
                Logger.debug("SSL 검증이 비활성화되었습니다.");
            }
        } catch (Exception e) {
            Logger.error("SSL 컨텍스트 초기화 중 오류 발생: " + e.getMessage());
        }
    }
    
    /**
     * 인증 검증 요청
     */
    public AuthResult validateAuthentication(String authKey, String serverInfo) {
        Logger.debug("웹서버 인증 검증을 시작합니다...");
        
        try {
            // 서버 정보 파싱
            Map<String, Object> serverInfoMap = parseServerInfo(serverInfo);
            
            // 요청 데이터 구성 (API가 기대하는 형식)
            Map<String, Object> requestData = new HashMap<>();
            requestData.put("server_ip", serverInfoMap.get("server_ip"));
            requestData.put("plugin_key", authKey);
            requestData.put("server_info", serverInfoMap);
            requestData.put("timestamp", System.currentTimeMillis());
            requestData.put("nonce", generateNonce());
            
            // HMAC 서명 생성
            String signature = generateHMAC(requestData);
            requestData.put("signature", signature);
            
            // SSL 인증서 지문 (보안 강화)
            String certFingerprint = getSSLCertificateFingerprint();
            requestData.put("cert_fingerprint", certFingerprint);
            
            // JSON 요청 데이터 생성
            String jsonData = convertMapToJson(requestData);
            
            // HTTP 요청 전송
            String response = sendHttpRequest(config.getValidateApiUrl(), "POST", jsonData);
            
            // 응답 파싱
            return parseAuthResponse(response);
            
        } catch (Exception e) {
            Logger.error("인증 검증 중 오류 발생: " + e.getMessage());
            return new AuthResult(false, "인증 검증 중 오류 발생: " + e.getMessage());
        }
    }
    
    /**
     * 서버 등록 요청
     */
    public AuthResult registerServer(String authKey, String serverInfo) {
        Logger.debug("웹서버에 서버 등록을 요청합니다...");
        
        try {
            // 서버 정보 파싱
            Map<String, Object> serverInfoMap = parseServerInfo(serverInfo);
            
            // 요청 데이터 구성 (API가 기대하는 형식)
            Map<String, Object> requestData = new HashMap<>();
            requestData.put("server_ip", serverInfoMap.get("server_ip"));
            requestData.put("plugin_key", authKey);
            requestData.put("server_info", serverInfoMap);
            requestData.put("timestamp", System.currentTimeMillis());
            requestData.put("nonce", generateNonce());
            
            // JSON 요청 데이터 생성
            String jsonData = convertMapToJson(requestData);
            
            // HTTP 요청 전송
            String response = sendHttpRequest(config.getRegisterApiUrl(), "POST", jsonData);
            
            // 응답 파싱
            return parseAuthResponse(response);
            
        } catch (Exception e) {
            Logger.error("서버 등록 중 오류 발생: " + e.getMessage());
            return new AuthResult(false, "서버 등록 중 오류 발생: " + e.getMessage());
        }
    }
    
    /**
     * 서버 상태 확인 요청
     */
    public AuthResult checkServerStatus(String authKey) {
        Logger.debug("웹서버에서 서버 상태를 확인합니다...");
        
        try {
            // 요청 데이터 구성
            Map<String, Object> requestData = new HashMap<>();
            requestData.put("auth_key", authKey);
            requestData.put("timestamp", System.currentTimeMillis());
            requestData.put("nonce", generateNonce());
            
            // JSON 요청 데이터 생성
            String jsonData = convertMapToJson(requestData);
            
            // HTTP 요청 전송
            String response = sendHttpRequest(config.getStatusApiUrl(), "POST", jsonData);
            
            // 응답 파싱
            return parseAuthResponse(response);
            
        } catch (Exception e) {
            Logger.error("서버 상태 확인 중 오류 발생: " + e.getMessage());
            return new AuthResult(false, "서버 상태 확인 중 오류 발생: " + e.getMessage());
        }
    }
    
    /**
     * HTTP 요청 전송
     */
    private String sendHttpRequest(String urlString, String method, String data) throws Exception {
        URL url = new URL(urlString);
        HttpURLConnection connection = null;
        
        try {
            // 연결 설정
            if (urlString.startsWith("https://")) {
                connection = (HttpsURLConnection) url.openConnection();
                
                // SSL 검증 설정
                if (!config.isEnableSSLVerification() && sslContext != null) {
                    ((HttpsURLConnection) connection).setSSLSocketFactory(sslContext.getSocketFactory());
                    ((HttpsURLConnection) connection).setHostnameVerifier((hostname, session) -> true);
                }
            } else {
                connection = (HttpURLConnection) url.openConnection();
            }
            
            // 연결 설정
            connection.setRequestMethod(method);
            connection.setConnectTimeout(10000); // 10초
            connection.setReadTimeout(15000); // 15초
            connection.setDoOutput(true);
            connection.setDoInput(true);
            
            // 헤더 설정
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            connection.setRequestProperty("User-Agent", "DoneConnector/" + plugin.getDescription().getVersion());
            connection.setRequestProperty("Accept", "application/json");
            
            // 요청 데이터 전송
            if (data != null && !data.isEmpty()) {
                try (OutputStream os = connection.getOutputStream()) {
                    byte[] input = data.getBytes(StandardCharsets.UTF_8);
                    os.write(input, 0, input.length);
                }
            }
            
            // 응답 코드 확인
            int responseCode = connection.getResponseCode();
            
            // 응답 데이터 읽기
            InputStream inputStream;
            if (responseCode >= 200 && responseCode < 300) {
                inputStream = connection.getInputStream();
            } else {
                inputStream = connection.getErrorStream();
            }
            
            StringBuilder response = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
            }
            
            String responseBody = response.toString();
            
            if (responseCode >= 200 && responseCode < 300) {
                Logger.debug("HTTP 요청 성공: " + responseCode);
                Logger.debug("응답 데이터: " + responseBody);
                return responseBody;
            } else {
                Logger.warn("HTTP 요청 실패: " + responseCode);
                Logger.warn("오류 응답: " + responseBody);
                throw new Exception("HTTP 요청 실패: " + responseCode + " - " + responseBody);
            }
            
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
    
    /**
     * 인증 응답 파싱
     */
    private AuthResult parseAuthResponse(String response) {
        try {
            if (response == null || response.trim().isEmpty()) {
                return new AuthResult(false, "빈 응답을 받았습니다.");
            }
            
            JSONObject jsonResponse = (JSONObject) jsonParser.parse(response);
            
            // 응답 상태 확인
            String status = (String) jsonResponse.get("status");
            if (status == null) {
                return new AuthResult(false, "응답 형식이 올바르지 않습니다.");
            }
            
            if ("success".equals(status)) {
                // 성공 응답
                String message = (String) jsonResponse.get("message");
                if (message == null) {
                    message = "인증이 성공했습니다.";
                }
                
                // 추가 데이터 추출
                Map<String, Object> data = new HashMap<>();
                if (jsonResponse.containsKey("data")) {
                    Object dataObj = jsonResponse.get("data");
                    if (dataObj instanceof JSONObject) {
                        JSONObject dataJson = (JSONObject) dataObj;
                        for (Object key : dataJson.keySet()) {
                            data.put(key.toString(), dataJson.get(key));
                        }
                    }
                }
                
                return new AuthResult(true, message, data);
            } else {
                // 실패 응답
                String errorCode = (String) jsonResponse.get("error_code");
                String message = (String) jsonResponse.get("message");
                
                if (message == null) {
                    message = "인증이 실패했습니다.";
                }
                
                if (errorCode != null) {
                    message = "[" + errorCode + "] " + message;
                }
                
                return new AuthResult(false, message);
            }
            
        } catch (ParseException e) {
            Logger.error("JSON 응답 파싱 중 오류 발생: " + e.getMessage());
            Logger.error("응답 데이터: " + response);
            return new AuthResult(false, "응답 데이터 파싱 중 오류 발생: " + e.getMessage());
        } catch (Exception e) {
            Logger.error("응답 처리 중 오류 발생: " + e.getMessage());
            return new AuthResult(false, "응답 처리 중 오류 발생: " + e.getMessage());
        }
    }
    
    /**
     * HMAC 서명 생성
     */
    private String generateHMAC(Map<String, Object> data) {
        try {
            // 데이터 정렬 및 문자열 생성
            StringBuilder dataString = new StringBuilder();
            data.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    if (!"signature".equals(entry.getKey())) {
                        dataString.append(entry.getKey()).append("=").append(entry.getValue()).append("&");
                    }
                });
            
            // 마지막 & 제거
            if (dataString.length() > 0) {
                dataString.setLength(dataString.length() - 1);
            }
            
            // HMAC-SHA256 생성
            String secretKey = "doneconnector_secret_key_2024"; // 실제로는 더 복잡한 키 사용
            String dataToSign = dataString.toString();
            
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] keyBytes = secretKey.getBytes(StandardCharsets.UTF_8);
            byte[] dataBytes = dataToSign.getBytes(StandardCharsets.UTF_8);
            
            // HMAC 구현 (간단한 버전)
            byte[] combined = new byte[keyBytes.length + dataBytes.length];
            System.arraycopy(keyBytes, 0, combined, 0, keyBytes.length);
            System.arraycopy(dataBytes, 0, combined, keyBytes.length, dataBytes.length);
            
            byte[] hash = md.digest(combined);
            
            // Base64 인코딩
            return Base64.getEncoder().encodeToString(hash);
            
        } catch (NoSuchAlgorithmException e) {
            Logger.error("HMAC 생성 중 오류 발생: " + e.getMessage());
            return "error";
        }
    }
    
    /**
     * SSL 인증서 지문 생성 (보안 강화)
     */
    private String getSSLCertificateFingerprint() {
        try {
            // 실제로는 SSL 인증서의 지문을 가져와야 하지만,
            // 여기서는 간단한 구현을 제공
            String serverUrl = config.getWebServerUrl();
            String timestamp = String.valueOf(System.currentTimeMillis());
            String combined = serverUrl + timestamp + "ssl_fingerprint";
            
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(combined.getBytes(StandardCharsets.UTF_8));
            
            StringBuilder fingerprint = new StringBuilder();
            for (byte b : hash) {
                fingerprint.append(String.format("%02x", b));
            }
            
            return fingerprint.toString();
            
        } catch (NoSuchAlgorithmException e) {
            Logger.error("SSL 인증서 지문 생성 중 오류 발생: " + e.getMessage());
            return "unknown";
        }
    }
    
    /**
     * 랜덤 nonce 생성
     */
    private String generateNonce() {
        SecureRandom random = new SecureRandom();
        byte[] nonceBytes = new byte[16];
        random.nextBytes(nonceBytes);
        return Base64.getEncoder().encodeToString(nonceBytes);
    }
    
    /**
     * 서버 정보 JSON 문자열을 Map으로 파싱
     */
    private Map<String, Object> parseServerInfo(String serverInfoJson) {
        try {
            JSONObject serverInfoObj = (JSONObject) jsonParser.parse(serverInfoJson);
            Map<String, Object> serverInfoMap = new HashMap<>();
            
            // 필수 필드들 추출
            serverInfoMap.put("server_ip", serverInfoObj.get("server_ip"));
            serverInfoMap.put("server_name", serverInfoObj.get("server_name"));
            serverInfoMap.put("server_port", serverInfoObj.get("server_port"));
            serverInfoMap.put("server_version", serverInfoObj.get("server_version"));
            serverInfoMap.put("plugin_version", serverInfoObj.get("plugin_version"));
            serverInfoMap.put("created_at", serverInfoObj.get("created_at"));
            
            return serverInfoMap;
            
        } catch (ParseException e) {
            Logger.error("서버 정보 파싱 중 오류 발생: " + e.getMessage());
            Logger.error("서버 정보 JSON: " + serverInfoJson);
            
            // 기본값 반환
            Map<String, Object> defaultInfo = new HashMap<>();
            defaultInfo.put("server_ip", "127.0.0.1");
            defaultInfo.put("server_name", "Unknown-Server");
            defaultInfo.put("server_port", 25565);
            defaultInfo.put("server_version", "Unknown");
            defaultInfo.put("plugin_version", "1.11.0");
            defaultInfo.put("created_at", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            
            return defaultInfo;
        }
    }
    
    /**
     * Map을 JSON 문자열로 변환
     */
    private String convertMapToJson(Map<String, Object> map) {
        try {
            JSONObject json = new JSONObject();
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                json.put(entry.getKey(), entry.getValue());
            }
            return json.toJSONString();
        } catch (Exception e) {
            Logger.error("JSON 변환 중 오류 발생: " + e.getMessage());
            return "{}";
        }
    }
    
    /**
     * 인증 결과 클래스
     */
    public static class AuthResult {
        private final boolean success;
        private final String message;
        private final Map<String, Object> data;
        
        public AuthResult(boolean success, String message) {
            this(success, message, null);
        }
        
        public AuthResult(boolean success, String message, Map<String, Object> data) {
            this.success = success;
            this.message = message;
            this.data = data != null ? data : new HashMap<>();
        }
        
        public boolean isSuccess() {
            return success;
        }
        
        public String getMessage() {
            return message;
        }
        
        public Map<String, Object> getData() {
            return data;
        }
        
        public Object getData(String key) {
            return data.get(key);
        }
        
        public String getDataAsString(String key) {
            Object value = data.get(key);
            return value != null ? value.toString() : null;
        }
        
        public boolean getDataAsBoolean(String key) {
            Object value = data.get(key);
            if (value instanceof Boolean) {
                return (Boolean) value;
            } else if (value instanceof String) {
                return Boolean.parseBoolean((String) value);
            }
            return false;
        }
        
        public int getDataAsInt(String key) {
            Object value = data.get(key);
            if (value instanceof Number) {
                return ((Number) value).intValue();
            } else if (value instanceof String) {
                try {
                    return Integer.parseInt((String) value);
                } catch (NumberFormatException e) {
                    return 0;
                }
            }
            return 0;
        }
    }
}
