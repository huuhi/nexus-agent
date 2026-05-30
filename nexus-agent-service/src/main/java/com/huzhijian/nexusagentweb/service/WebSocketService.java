package com.huzhijian.nexusagentweb.service;

import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.PathParam;
import jakarta.websocket.server.ServerEndpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@ServerEndpoint("/ws/{userId}")
public class WebSocketService {
    private static final Logger log = LoggerFactory.getLogger(WebSocketService.class);
    private static final Map<String, Session> CLIENTS = new ConcurrentHashMap<>();
    
    @OnOpen
    public void onOpen(Session session, @PathParam("userId") String userId) {
        CLIENTS.put(userId, session);
        log.info("客户端连接: {}", userId);
    }
    
    @OnClose
    public void onClose(@PathParam("userId") String userId) {
        CLIENTS.remove(userId);
        log.info("客户端断开: {}", userId);
    }
    
    @OnError
    public void onError(Session session, Throwable error) {
        log.error("WebSocket错误", error);
    }
    
    /**
     * 发送消息给指定用户
     */
    public void sendToClient(String userId, String message) {
        Session session = CLIENTS.get(userId);
        if (session != null && session.isOpen()) {
            try {
                session.getBasicRemote().sendText(message);
                log.debug("发送消息给用户 {}: {}", userId, message);
            } catch (IOException e) {
                log.error("发送消息失败", e);
            }
        } else {
            log.warn("用户 {} 的WebSocket连接不存在或已关闭", userId);
        }
    }
    
    /**
     * 广播消息（保留原有功能）
     */
    public void sendToAllClient(String message) {
        CLIENTS.forEach((userId, session) -> {
            if (session.isOpen()) {
                try {
                    session.getBasicRemote().sendText(message);
                } catch (IOException e) {
                    log.error("广播消息失败", e);
                }
            }
        });
    }
}