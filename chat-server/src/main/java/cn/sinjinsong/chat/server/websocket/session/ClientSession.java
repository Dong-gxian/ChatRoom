package cn.sinjinsong.chat.server.websocket.session;

import cn.sinjinsong.common.util.LongToByteArray;

import java.math.BigInteger;
import java.nio.channels.SocketChannel;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class ClientSession {
    private SocketChannel socketChannel;
    private String sessionID;

    public ClientSession(SocketChannel channel) {
        this.socketChannel = channel;
        try {
            MessageDigest sha1 = MessageDigest.getInstance("sha1");
            sha1.update(LongToByteArray.convert(System.currentTimeMillis()));
            BigInteger bi = new BigInteger(sha1.digest());
            sessionID = bi.toString(16);
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        }
    }

    public SocketChannel getSocketChannel() {
        return socketChannel;
    }

    public String getSessionID() {
        return sessionID;
    }
}
