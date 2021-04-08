package cn.sinjinsong.chat.server.websocket.listener;

import cn.sinjinsong.chat.server.websocket.session.ClientSession;

import java.io.IOException;

public interface WebSocketListener {
    void onOpen(ClientSession session) throws IOException;

    void onMessage(ClientSession session) throws IOException;

    void onException(ClientSession session, Exception ex);

    void onClose(ClientSession session) throws IOException;
}