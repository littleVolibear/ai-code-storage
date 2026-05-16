package com.example.plandeduce.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
/**
 * WebSocket 推送入口。
 * 当前协议约定：HTTP 负责控制命令，WebSocket 负责向指定 sessionId 推送状态和数据。
 * 这里是传输层组件，只处理连接管理和底层发送，不再承载业务消息组装逻辑。
 */
public class PlanDeduceWebSocketHandler extends TextWebSocketHandler {
    private final Map<String, WebSocketSession> sessionMap = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    /**
     * 建连时按 URL 中的 sessionId 记录连接，供后续定向推送使用。
     */
    public void afterConnectionEstablished(WebSocketSession session) {
        String sessionId = parseSessionId(session.getUri());
        // 同一个 sessionId 重连时，新的连接会覆盖旧映射，后续推送始终命中最新连接。
        sessionMap.put(sessionId, session);
    }

    @Override
    /**
     * 断连时移除会话映射，避免把消息发给已关闭连接。
     */
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String sessionId = parseSessionId(session.getUri());
        sessionMap.remove(sessionId);
    }

    /**
     * 向指定 sessionId 推送消息。
     * 如果前端未连接或连接已关闭，这里会静默丢弃，避免播放线程被推送异常中断。
     */
    public void sendToSession(String sessionId, Object payload) {
        WebSocketSession session = sessionMap.get(sessionId);
        if (session == null || !session.isOpen()) {
            return;
        }
        try {
            // 同一连接上的消息串行发送，避免并发 write 破坏消息顺序。
            synchronized (session) {
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(payload)));
            }
        } catch (IOException e) {
            // 发送失败一般意味着连接不可用，直接清理映射，等待前端重连。
            sessionMap.remove(sessionId);
        }
    }

    /**
     * 从 WebSocket URL 里解析 sessionId。
     * 前端没有显式传 sessionId 时，会回退到 default。
     */
    private String parseSessionId(URI uri) {
        if (uri == null || uri.getQuery() == null) {
            return "default";
        }
        String[] params = uri.getQuery().split("&");
        for (String param : params) {
            String[] kv = param.split("=", 2);
            if (kv.length == 2 && "sessionId".equals(kv[0])) {
                return kv[1];
            }
        }
        return "default";
    }

}
