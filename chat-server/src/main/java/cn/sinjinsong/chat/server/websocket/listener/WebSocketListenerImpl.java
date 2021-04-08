package cn.sinjinsong.chat.server.websocket.listener;

import cn.sinjinsong.chat.server.websocket.session.ClientSession;
import cn.sinjinsong.common.util.Util;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
@Slf4j
public class WebSocketListenerImpl implements WebSocketListener {

    private Map<String, ClientSession> connSessionMap = new HashMap<>();

    @Override
    public void onOpen(ClientSession session) throws IOException {
        connSessionMap.put(session.getSessionID(), session);
        sendBoardCast(session.getSocketChannel().socket().getInetAddress().getHostName() + ":" +
                session.getSocketChannel().socket().getPort() + " Join", session);
        log.info("session open: " + session.getSessionID());
    }

    @Override
    public void onMessage(ClientSession session) throws IOException {

        SocketChannel socketChannel = session.getSocketChannel();

        byte[] bytesData = Util.readByteArray(socketChannel);

        //opcode为8，对方主动断开连接
        if ((bytesData[0] & 0xf) == 8) {
            throw new IOException("session disconnect.");
        }

        byte payloadLength = (byte) (bytesData[1] & 0x7f);
        byte[] mask = Arrays.copyOfRange(bytesData, 2, 6);
        byte[] payloadData = Arrays.copyOfRange(bytesData, 6, bytesData.length);
        for (int i = 0; i < payloadData.length; i++) {
            payloadData[i] = (byte) (payloadData[i] ^ mask[i % 4]);
        }

        String echoData =
                "[" + session.getSocketChannel().socket().getInetAddress().getHostAddress() + ":" +
                        session.getSocketChannel().socket().getPort() + "]" +
                        (new String(payloadData));

        sendBoardCast(echoData, session);
    }

    @Override
    public void onException(ClientSession session, Exception ex) {
        log.info("exception catch: " + ex.getMessage());
    }

    @Override
    public void onClose(ClientSession session) throws IOException {
        connSessionMap.remove(session.getSessionID());

        sendBoardCast(session.getSocketChannel().socket().getInetAddress().getHostName() + ":" +
                session.getSocketChannel().socket().getPort() + " Leave", session);

        log.info("closed sessionId = " + session.getSessionID());
    }

    private void sendBoardCast(String message, ClientSession ownSession) throws IOException {
        Iterator<ClientSession> iterator = connSessionMap.values().iterator();
        while (iterator.hasNext()) {
            ClientSession nextSession = iterator.next();
            if (nextSession == ownSession) {
                continue;
            }
            byte[] boardCastData = new byte[2 + message.getBytes().length];
            boardCastData[0] = (byte) 0x81;
            boardCastData[1] = (byte) message.getBytes().length;
            System.arraycopy(message.getBytes(), 0, boardCastData, 2, message.getBytes().length);

            nextSession.getSocketChannel().write(ByteBuffer.wrap(boardCastData));
        }
    }
}
