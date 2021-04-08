package cn.sinjinsong.chat.server.websocket.protocol;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

public class WSProtocol {

    public static class Header {
        private Map<String, String> headers = new HashMap<>();

        public String getHeader(String key) {
            return headers.get(key);
        }

        public static Header decodeFromString(String headers) {
            Header header = new Header();

            Map<String, String> headerMap = new HashMap<>();
            String[] headerArray = headers.split("\r\n");
            for (String headerLine : headerArray) {
                if (headerLine.contains(":")) {
                    int splitPos = headerLine.indexOf(":");
                    String key = headerLine.substring(0, splitPos);
                    String value = headerLine.substring(splitPos + 1).trim();
                    headerMap.put(key, value);
                }
            }
            header.headers = headerMap;
            return header;
        }
    }

    public static String getHandShakeResponse(String receiveKey) {
        String keyOrigin = receiveKey + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
        MessageDigest sha1;
        String accept = null;
        try {
            sha1 = MessageDigest.getInstance("sha1");
            sha1.update(keyOrigin.getBytes());
            accept = new String(Base64.getEncoder().encode(sha1.digest()));
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        }
        String echoHeader = "";
        echoHeader += "HTTP/1.1 101 Switching Protocols\r\n";
        echoHeader += "Upgrade: websocket\r\n";
        echoHeader += "Connection: Upgrade\r\n";
        echoHeader += "Sec-WebSocket-Accept: " + accept + "\r\n";
        echoHeader += "\r\n";

        return echoHeader;
    }
}