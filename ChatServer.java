import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ChatServer extends WebSocketServer {
    private static final Logger logger = LoggerFactory.getLogger(ChatServer.class);
    private Map<WebSocket, String> usernames;
    private Map<String, Set<String>> calls;
    private Map<String, String> callInitiators;
    private Map<String, String> userAvatars;
    private Map<String, Set<WebSocket>> typingUsers;
    private Map<Long, Set<String>> messageReadBy;

    public ChatServer(InetSocketAddress address) {
        super(address);
        usernames = new ConcurrentHashMap<>();
        calls = new ConcurrentHashMap<>();
        callInitiators = new ConcurrentHashMap<>();
        userAvatars = new ConcurrentHashMap<>();
        typingUsers = new ConcurrentHashMap<>();
        messageReadBy = new ConcurrentHashMap<>();
        logger.info("ChatServer initialized on {}", address);
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        logger.info("New connection from {}", conn.getRemoteSocketAddress());
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        String username = usernames.get(conn);
        if (username != null) {
            logger.info("User {} disconnected: code={}, reason={}", username, code, reason);
            for (Map.Entry<String, Set<String>> entry : calls.entrySet()) {
                String callId = entry.getKey();
                Set<String> participants = entry.getValue();
                if (participants.contains(username)) {
                    participants.remove(username);
                    if (participants.isEmpty()) {
                        calls.remove(callId);
                        callInitiators.remove(callId);
                    } else {
                        broadcastCallUpdate(participants, username, callId);
                    }
                }
            }
            broadcastMessage("leave", username, null, null);
            usernames.remove(conn);
            userAvatars.remove(username);
            typingUsers.remove(username);
            broadcastUserListUpdate();
        }
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        try {
            JSONObject jsonMessage = new JSONObject(message);
            String type = jsonMessage.getString("type");
            String username = jsonMessage.optString("username", "");

            logger.debug("Received message from {}: type={}", username, type);

            switch (type) {
                case "join":
                    handleJoin(conn, username, jsonMessage.optString("avatar", ""));
                    break;
                case "message":
                    handleChatMessage(username, jsonMessage.getString("message"), jsonMessage.getLong("messageId"));
                    break;
                case "file":
                    handleFileMessage(username, jsonMessage.getString("fileUrl"), jsonMessage.getBoolean("isImage"),
                            jsonMessage.getLong("messageId"));
                    break;
                case "leave":
                    handleLeave(conn, username);
                    break;
                case "location":
                    handleLocationMessage(username, jsonMessage.getDouble("latitude"),
                            jsonMessage.getDouble("longitude"));
                    break;
                case "delete":
                    handleDeleteMessage(username, jsonMessage.getLong("messageId"));
                    break;
                case "edit":
                    handleEditMessage(username, jsonMessage.getLong("messageId"), jsonMessage.getString("newMessage"));
                    break;
                case "call-initiate":
                    handleCallInitiate(username, jsonMessage.getString("callId"));
                    break;
                case "call-accept":
                    handleCallAccept(username, jsonMessage.getString("callId"));
                    break;
                case "call-reject":
                    handleCallReject(username, jsonMessage.getString("callId"));
                    break;
                case "call-end":
                    handleCallEnd(username, jsonMessage.getString("callId"));
                    break;
                case "call-signal":
                    handleCallSignal(username, jsonMessage.getString("callId"), jsonMessage.getString("target"),
                            jsonMessage.getJSONObject("signal"));
                    break;
                case "typing":
                    handleTyping(username, jsonMessage.getBoolean("isTyping"));
                    break;
                case "reaction":
                    handleReaction(username, jsonMessage.getLong("messageId"), jsonMessage.getString("emoji"));
                    break;
                case "read":
                    handleReadReceipt(username, jsonMessage.getLong("messageId"));
                    break;
                default:
                    logger.warn("Unknown message type: {}", type);
                    break;
            }
        } catch (Exception e) {
            logger.error("Error processing message: {}", message, e);
            sendError(conn, "Invalid message format");
        }
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        logger.error("WebSocket error on connection {}: {}", conn != null ? conn.getRemoteSocketAddress() : "unknown",
                ex.getMessage(), ex);
        if (conn != null) {
            sendError(conn, "Server error occurred");
        }
    }

    public void onStart() {
        logger.info("WebSocket server started successfully");
    }

    private void handleJoin(WebSocket conn, String username, String avatar) {
        if (username.isEmpty()) {
            sendError(conn, "Username cannot be empty");
            return;
        }
        usernames.put(conn, username);
        userAvatars.put(username, avatar);
        logger.info("User {} joined with avatar: {}", username, avatar);
        broadcastMessage("join", username, null, null);
        broadcastUserListUpdate();
        for (Map.Entry<String, Set<String>> entry : calls.entrySet()) {
            String callId = entry.getKey();
            Set<String> participants = entry.getValue();
            JSONObject callMessage = new JSONObject();
            callMessage.put("type", "call-info");
            callMessage.put("callId", callId);
            callMessage.put("initiator", callInitiators.get(callId));
            callMessage.put("participants", new JSONArray(participants));
            conn.send(callMessage.toString());
        }
    }

    private void handleChatMessage(String username, String message, long messageId) {
        logger.debug("Broadcasting message from {}: {}", username, message);
        broadcastMessage("message", username, message, null, messageId);
    }

    private void handleFileMessage(String username, String fileUrl, boolean isImage, long messageId) {
        logger.debug("Broadcasting file from {}: {}", username, fileUrl);
        broadcastMessage("file", username, null, fileUrl, isImage, messageId);
    }

    private void handleLeave(WebSocket conn, String username) {
        logger.info("User {} left", username);
        usernames.remove(conn);
        userAvatars.remove(username);
        typingUsers.remove(username);
        broadcastMessage("leave", username, null, null);
        broadcastUserListUpdate();
    }

    private void handleLocationMessage(String username, double latitude, double longitude) {
        logger.debug("Broadcasting location from {}: lat={}, lon={}", username, latitude, longitude);
        broadcastMessage("location", username, null, null, latitude, longitude);
    }

    private void handleDeleteMessage(String username, long messageId) {
        logger.debug("Deleting message {} by {}", messageId, username);
        JSONObject jsonMessage = new JSONObject();
        jsonMessage.put("type", "delete");
        jsonMessage.put("username", username);
        jsonMessage.put("messageId", messageId);
        customBroadcast(jsonMessage.toString());
        messageReadBy.remove(messageId);
    }

    private void handleEditMessage(String username, long messageId, String newMessage) {
        logger.debug("Editing message {} by {}: {}", messageId, username, newMessage);
        JSONObject jsonMessage = new JSONObject();
        jsonMessage.put("type", "edit");
        jsonMessage.put("username", username);
        jsonMessage.put("messageId", messageId);
        jsonMessage.put("newMessage", newMessage);
        customBroadcast(jsonMessage.toString());
    }

    private void handleCallInitiate(String username, String callId) {
        Set<String> participants = ConcurrentHashMap.newKeySet();
        participants.add(username);
        calls.put(callId, participants);
        callInitiators.put(callId, username);
        logger.info("Call initiated by {} with callId: {}", username, callId);
        JSONObject jsonMessage = new JSONObject();
        jsonMessage.put("type", "call-initiate");
        jsonMessage.put("username", username);
        jsonMessage.put("callId", callId);
        broadcastToOthers(username, jsonMessage.toString());
    }

    private void handleCallAccept(String username, String callId) {
        Set<String> participants = calls.get(callId);
        if (participants != null) {
            participants.add(username);
            logger.info("User {} accepted call {}", username, callId);
            JSONObject jsonMessage = new JSONObject();
            jsonMessage.put("type", "call-accept");
            jsonMessage.put("username", username);
            jsonMessage.put("callId", callId);
            jsonMessage.put("participants", new JSONArray(participants));
            customBroadcast(jsonMessage.toString());
        } else {
            logger.warn("Call {} not found for user {}", callId, username);
        }
    }

    private void handleCallReject(String username, String callId) {
        logger.info("User {} rejected call {}", username, callId);
        JSONObject jsonMessage = new JSONObject();
        jsonMessage.put("type", "call-reject");
        jsonMessage.put("username", username);
        jsonMessage.put("callId", callId);
        customBroadcast(jsonMessage.toString());
    }

    private void handleCallEnd(String username, String callId) {
        Set<String> participants = calls.get(callId);
        String initiator = callInitiators.get(callId);
        if (participants != null) {
            if (username.equals(initiator)) {
                calls.remove(callId);
                callInitiators.remove(callId);
                logger.info("Call {} ended by initiator {}", callId, username);
                JSONObject jsonMessage = new JSONObject();
                jsonMessage.put("type", "call-end");
                jsonMessage.put("username", username);
                jsonMessage.put("callId", callId);
                customBroadcast(jsonMessage.toString());
            } else {
                participants.remove(username);
                logger.info("User {} left call {}", username, callId);
                if (participants.isEmpty()) {
                    calls.remove(callId);
                    callInitiators.remove(callId);
                } else {
                    broadcastCallUpdate(participants, username, callId);
                }
            }
        } else {
            logger.warn("Call {} not found to end by {}", callId, username);
        }
    }

    private void handleCallSignal(String username, String callId, String target, JSONObject signal) {
        logger.debug("Handling call signal from {} to {} for call {}: type={}", username, target, callId,
                signal.optString("type"));
        JSONObject jsonMessage = new JSONObject();
        jsonMessage.put("type", "call-signal");
        jsonMessage.put("username", username);
        jsonMessage.put("callId", callId);
        jsonMessage.put("target", target);
        jsonMessage.put("signal", signal);
        for (Map.Entry<WebSocket, String> entry : usernames.entrySet()) {
            if (entry.getValue().equals(target)) {
                entry.getKey().send(jsonMessage.toString());
                logger.debug("Sent call signal to {}", target);
                break;
            }
        }
    }

    private void handleTyping(String username, boolean isTyping) {
        if (isTyping) {
            typingUsers.computeIfAbsent(username, k -> ConcurrentHashMap.newKeySet());
            logger.debug("User {} is typing", username);
        } else {
            typingUsers.remove(username);
            logger.debug("User {} stopped typing", username);
        }
        broadcastTypingUpdate();
    }

    private void handleReaction(String username, long messageId, String emoji) {
        logger.debug("User {} reacted to message {} with {}", username, messageId, emoji);
        JSONObject jsonMessage = new JSONObject();
        jsonMessage.put("type", "reaction");
        jsonMessage.put("username", username);
        jsonMessage.put("messageId", messageId);
        jsonMessage.put("emoji", emoji);
        customBroadcast(jsonMessage.toString());
    }

    private void handleReadReceipt(String username, long messageId) {
        messageReadBy.computeIfAbsent(messageId, k -> ConcurrentHashMap.newKeySet()).add(username);
        logger.debug("User {} read message {}", username, messageId);
        broadcastReadReceiptUpdate(messageId);
    }

    private void broadcastCallUpdate(Set<String> participants, String leftUser, String callId) {
        logger.info("Broadcasting call update for user {} leaving call {}", leftUser, callId);
        JSONObject jsonMessage = new JSONObject();
        jsonMessage.put("type", "call-user-left");
        jsonMessage.put("username", leftUser);
        jsonMessage.put("participants", new JSONArray(participants));
        jsonMessage.put("callId", callId);
        customBroadcast(jsonMessage.toString());
    }

    private String getCallIdForUser(String username) {
        for (Map.Entry<String, Set<String>> entry : calls.entrySet()) {
            if (entry.getValue().contains(username)) {
                return entry.getKey();
            }
        }
        return null;
    }

    private void broadcastUserListUpdate() {
        logger.debug("Broadcasting user list update");
        JSONObject jsonMessage = new JSONObject();
        jsonMessage.put("type", "user-list-update");
        jsonMessage.put("userList", getUserList());
        customBroadcast(jsonMessage.toString());
    }

    private void broadcastTypingUpdate() {
        logger.debug("Broadcasting typing update");
        JSONObject jsonMessage = new JSONObject();
        jsonMessage.put("type", "typing");
        jsonMessage.put("typingUsers", new JSONArray(typingUsers.keySet()));
        customBroadcast(jsonMessage.toString());
    }

    private void broadcastReadReceiptUpdate(long messageId) {
        logger.debug("Broadcasting read receipt for message {}", messageId);
        JSONObject jsonMessage = new JSONObject();
        jsonMessage.put("type", "read");
        jsonMessage.put("messageId", messageId);
        jsonMessage.put("readBy", new JSONArray(messageReadBy.getOrDefault(messageId, Collections.emptySet())));
        customBroadcast(jsonMessage.toString());
    }

    private JSONArray getUserList() {
        JSONArray userList = new JSONArray();
        for (Map.Entry<WebSocket, String> entry : usernames.entrySet()) {
            JSONObject user = new JSONObject();
            String username = entry.getValue();
            user.put("username", username);
            user.put("avatar", userAvatars.getOrDefault(username, ""));
            userList.put(user);
        }
        return userList;
    }

    private void broadcastMessage(String type, String username, String message, String fileUrl, Object... extras) {
        JSONObject jsonMessage = new JSONObject();
        jsonMessage.put("type", type);
        jsonMessage.put("username", username);
        if (message != null) {
            jsonMessage.put("message", message);
        }
        if (fileUrl != null) {
            jsonMessage.put("fileUrl", fileUrl);
            if (extras.length > 0 && extras[0] instanceof Boolean) {
                jsonMessage.put("isImage", extras[0]);
            }
        }
        if (extras.length > 0) {
            if (extras[0] instanceof Long) {
                jsonMessage.put("messageId", extras[0]);
            } else if (extras[0] instanceof Double) {
                jsonMessage.put("latitude", extras[0]);
                jsonMessage.put("longitude", extras[1]);
            }
        }
        customBroadcast(jsonMessage.toString());
    }

    private void broadcastToOthers(String senderUsername, String message) {
        for (Map.Entry<WebSocket, String> entry : usernames.entrySet()) {
            if (!entry.getValue().equals(senderUsername)) {
                entry.getKey().send(message);
            }
        }
    }

    private void customBroadcast(String message) {
        for (WebSocket conn : usernames.keySet()) {
            if (conn.isOpen()) {
                conn.send(message);
            }
        }
    }

    private void sendError(WebSocket conn, String errorMessage) {
        JSONObject error = new JSONObject();
        error.put("type", "error");
        error.put("message", errorMessage);
        conn.send(error.toString());
    }

    public static void main(String[] args) {
        String host = "localhost";
        int port = 8887;
        try {
            InetSocketAddress address = new InetSocketAddress(host, port);
            ChatServer server = new ChatServer(address);
            server.start();
            logger.info("ChatServer started on ws://{}:{}", host, port);
            // Keep the server running
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    server.stop();
                    logger.info("ChatServer stopped successfully");
                } catch (Exception e) {
                    logger.error("Error stopping ChatServer", e);
                }
            }));
        } catch (Exception e) {
            logger.error("Failed to start ChatServer on ws://{}:{}", host, port, e);
            System.exit(1);
        }
    }
}