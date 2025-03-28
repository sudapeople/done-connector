package me.suda.doneconnector.soop;

import lombok.Getter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Getter  // Lombok annotation 추가
public class SoopPacket {
    private final String command;
    private final List<String> dataList;
    private final LocalDateTime receivedTime;
    
    public SoopPacket(String[] args) {
        this.dataList = new ArrayList<>(Arrays.asList(args));
        String cmd = dataList.remove(0);
        this.command = cmd.substring(0, 4);
        this.receivedTime = LocalDateTime.now();
    }
}


