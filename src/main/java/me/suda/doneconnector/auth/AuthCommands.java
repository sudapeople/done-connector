package me.suda.doneconnector.auth;

import me.suda.doneconnector.DoneConnector;
import me.suda.doneconnector.Logger;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 인증 관련 명령어 처리 클래스
 * /doneconnector 명령어들을 처리
 */
public class AuthCommands implements CommandExecutor, TabCompleter {
    
    private final DoneConnector plugin;
    private final AuthManager authManager;
    
    public AuthCommands(DoneConnector plugin, AuthManager authManager) {
        this.plugin = plugin;
        this.authManager = authManager;
    }
    
    /**
     * 인증 명령어 실행
     */
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("doneconnector")) {
            return false;
        }
        
        // 권한 확인
        if (!sender.hasPermission("doneconnector.admin")) {
            sender.sendMessage(ChatColor.RED + "이 명령어를 사용할 권한이 없습니다.");
            return true;
        }
        
        if (args.length == 0) {
            showHelp(sender);
            return true;
        }
        
        String subCommand = args[0].toLowerCase();
        
        switch (subCommand) {
            case "auth":
                return handleAuthCommand(sender, args);
            case "reload":
                return handleReloadCommand(sender, args);
            case "status":
                return handleStatusCommand(sender, args);
            case "register":
                return handleRegisterCommand(sender, args);
            case "test":
                return handleTestCommand(sender, args);
            case "help":
                showHelp(sender);
                return true;
            default:
                sender.sendMessage(ChatColor.RED + "알 수 없는 명령어입니다. /doneconnector help를 참조하세요.");
                return true;
        }
    }
    
    /**
     * 인증 명령어 처리
     */
    private boolean handleAuthCommand(CommandSender sender, String[] args) {
        if (args.length > 1) {
            sender.sendMessage(ChatColor.RED + "사용법: /doneconnector auth");
            return true;
        }
        
        sender.sendMessage(ChatColor.YELLOW + "웹서버 인증을 시도합니다...");
        
        // 비동기로 인증 수행
        CompletableFuture.supplyAsync(() -> {
            return authManager.performAuthentication();
        }).thenAccept(success -> {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (success) {
                    sender.sendMessage(ChatColor.GREEN + "인증이 성공했습니다!");
                    Logger.info(sender.getName() + "님이 수동 인증을 성공했습니다.");
                } else {
                    sender.sendMessage(ChatColor.RED + "인증이 실패했습니다. 웹서버에 등록되지 않은 서버일 수 있습니다.");
                    Logger.warn(sender.getName() + "님이 수동 인증을 시도했지만 실패했습니다.");
                }
            });
        }).exceptionally(throwable -> {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                sender.sendMessage(ChatColor.RED + "인증 중 오류가 발생했습니다: " + throwable.getMessage());
                Logger.error("인증 명령어 실행 중 오류 발생: " + throwable.getMessage());
            });
            return null;
        });
        
        return true;
    }
    
    /**
     * 리로드 명령어 처리
     */
    private boolean handleReloadCommand(CommandSender sender, String[] args) {
        if (args.length > 1) {
            sender.sendMessage(ChatColor.RED + "사용법: /doneconnector reload");
            return true;
        }
        
        sender.sendMessage(ChatColor.YELLOW + "플러그인 설정을 다시 불러오고 인증을 시도합니다...");
        
        // 비동기로 리로드 및 인증 수행
        CompletableFuture.runAsync(() -> {
            try {
                // 1. 플러그인 설정 리로드
                plugin.reloadConfig();
                
                // 2. 인증 시스템 재초기화
                authManager.shutdown();
                authManager.initialize();
                
                // 3. 인증 시도
                boolean authSuccess = authManager.performAuthentication();
                
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (authSuccess) {
                        sender.sendMessage(ChatColor.GREEN + "리로드 및 인증이 완료되었습니다!");
                        Logger.info(sender.getName() + "님이 리로드 명령어를 실행했습니다.");
                    } else {
                        sender.sendMessage(ChatColor.RED + "리로드는 완료되었지만 인증이 실패했습니다.");
                        Logger.warn(sender.getName() + "님이 리로드를 실행했지만 인증에 실패했습니다.");
                    }
                });
                
            } catch (Exception e) {
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    sender.sendMessage(ChatColor.RED + "리로드 중 오류가 발생했습니다: " + e.getMessage());
                    Logger.error("리로드 명령어 실행 중 오류 발생: " + e.getMessage());
                });
            }
        });
        
        return true;
    }
    
    /**
     * 상태 명령어 처리
     */
    private boolean handleStatusCommand(CommandSender sender, String[] args) {
        if (args.length > 1) {
            sender.sendMessage(ChatColor.RED + "사용법: /doneconnector status");
            return true;
        }
        
        // 인증 상태 표시
        sender.sendMessage(ChatColor.GOLD + "=== 인증 시스템 상태 ===");
        
        boolean isAuthenticated = authManager.isAuthenticated();
        boolean isAuthInProgress = authManager.isAuthenticationInProgress();
        
        sender.sendMessage(ChatColor.WHITE + "인증 상태: " + 
            (isAuthenticated ? ChatColor.GREEN + "인증됨" : ChatColor.RED + "인증 안됨"));
        
        if (isAuthInProgress) {
            sender.sendMessage(ChatColor.YELLOW + "인증 진행 중...");
        }
        
        // 인증키 정보 (일부만 표시)
        String authKey = authManager.getCurrentAuthKey();
        if (authKey != null) {
            String maskedKey = authKey.substring(0, Math.min(8, authKey.length())) + "...";
            sender.sendMessage(ChatColor.WHITE + "인증키: " + ChatColor.GRAY + maskedKey);
        }
        
        // 서버 정보
        String serverInfo = authManager.getCurrentServerInfo();
        if (serverInfo != null) {
            sender.sendMessage(ChatColor.WHITE + "서버 정보: " + ChatColor.GRAY + "등록됨");
        }
        
        // 플러그인 기능 상태
        sender.sendMessage(ChatColor.WHITE + "플러그인 기능: " + 
            (isAuthenticated ? ChatColor.GREEN + "활성화" : ChatColor.RED + "비활성화"));
        
        return true;
    }
    
    /**
     * 등록 명령어 처리
     */
    private boolean handleRegisterCommand(CommandSender sender, String[] args) {
        if (args.length > 1) {
            sender.sendMessage(ChatColor.RED + "사용법: /doneconnector register");
            return true;
        }
        
        sender.sendMessage(ChatColor.YELLOW + "웹서버에 서버 등록을 시도합니다...");
        
        // 비동기로 서버 등록 수행
        CompletableFuture.supplyAsync(() -> {
            return authManager.performRegistration();
        }).thenAccept(success -> {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (success) {
                    sender.sendMessage(ChatColor.GREEN + "서버 등록이 완료되었습니다! 웹 대시보드에서 승인을 기다려주세요.");
                    Logger.info(sender.getName() + "님이 서버 등록을 완료했습니다.");
                } else {
                    sender.sendMessage(ChatColor.RED + "서버 등록이 실패했습니다. 다시 시도해주세요.");
                    Logger.warn(sender.getName() + "님이 서버 등록을 시도했지만 실패했습니다.");
                }
            });
        }).exceptionally(throwable -> {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                sender.sendMessage(ChatColor.RED + "서버 등록 중 오류가 발생했습니다: " + throwable.getMessage());
                Logger.error("서버 등록 명령어 실행 중 오류 발생: " + throwable.getMessage());
            });
            return null;
        });
        
        return true;
    }
    
    /**
     * 테스트 명령어 처리
     */
    private boolean handleTestCommand(CommandSender sender, String[] args) {
        if (args.length > 1) {
            sender.sendMessage(ChatColor.RED + "사용법: /doneconnector test");
            return true;
        }
        
        sender.sendMessage(ChatColor.YELLOW + "인증 시스템 연결을 테스트합니다...");
        
        // 비동기로 연결 테스트 수행
        CompletableFuture.runAsync(() -> {
            try {
                // 웹서버 연결 테스트
                boolean connectionTest = testWebServerConnection();
                
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (connectionTest) {
                        sender.sendMessage(ChatColor.GREEN + "웹서버 연결 테스트 성공!");
                        
                        // 추가 인증 테스트
                        boolean authTest = authManager.performAuthentication();
                        if (authTest) {
                            sender.sendMessage(ChatColor.GREEN + "인증 테스트도 성공했습니다!");
                        } else {
                            sender.sendMessage(ChatColor.YELLOW + "웹서버 연결은 되지만 인증은 실패했습니다.");
                        }
                    } else {
                        sender.sendMessage(ChatColor.RED + "웹서버 연결 테스트 실패!");
                    }
                });
                
            } catch (Exception e) {
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    sender.sendMessage(ChatColor.RED + "연결 테스트 중 오류가 발생했습니다: " + e.getMessage());
                    Logger.error("연결 테스트 명령어 실행 중 오류 발생: " + e.getMessage());
                });
            }
        });
        
        return true;
    }
    
    /**
     * 웹서버 연결 테스트
     */
    private boolean testWebServerConnection() {
        try {
            // 간단한 HTTP 연결 테스트
            String testUrl = authManager.getAuthConfig().getWebServerUrl();
            java.net.URL url = new java.net.URL(testUrl);
            java.net.HttpURLConnection connection = (java.net.HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setRequestMethod("GET");
            
            int responseCode = connection.getResponseCode();
            connection.disconnect();
            
            return responseCode >= 200 && responseCode < 300;
            
        } catch (Exception e) {
            Logger.debug("웹서버 연결 테스트 실패: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * 도움말 표시
     */
    private void showHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "=== DoneConnector 인증 명령어 도움말 ===");
        sender.sendMessage(ChatColor.WHITE + "/doneconnector auth" + ChatColor.GRAY + " - 웹서버 인증 시도");
        sender.sendMessage(ChatColor.WHITE + "/doneconnector reload" + ChatColor.GRAY + " - 설정 리로드 및 인증");
        sender.sendMessage(ChatColor.WHITE + "/doneconnector status" + ChatColor.GRAY + " - 인증 상태 확인");
        sender.sendMessage(ChatColor.WHITE + "/doneconnector register" + ChatColor.GRAY + " - 웹서버에 서버 등록");
        sender.sendMessage(ChatColor.WHITE + "/doneconnector test" + ChatColor.GRAY + " - 연결 테스트");
        sender.sendMessage(ChatColor.WHITE + "/doneconnector help" + ChatColor.GRAY + " - 이 도움말 표시");
        sender.sendMessage(ChatColor.GRAY + "모든 명령어는 OP 권한이 필요합니다.");
    }
    
    /**
     * 탭 완성 처리
     */
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("doneconnector")) {
            return Collections.emptyList();
        }
        
        if (!sender.hasPermission("doneconnector.admin")) {
            return Collections.emptyList();
        }
        
        if (args.length == 1) {
            List<String> subCommands = Arrays.asList("auth", "reload", "status", "register", "test", "help");
            
            if (args[0].isEmpty()) {
                return subCommands;
            } else {
                return subCommands.stream()
                    .filter(cmd -> cmd.toLowerCase().startsWith(args[0].toLowerCase()))
                    .collect(java.util.stream.Collectors.toList());
            }
        }
        
        return Collections.emptyList();
    }
}
