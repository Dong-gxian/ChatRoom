package cn.sinjinsong.common.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

public class Util {
    public static final int DEFAULT_BUFFER_SIZE = 1024;

    public static byte[] readByteArray(SocketChannel channel){
        int size = -1;
        ByteBuffer buffer = ByteBuffer.allocate(DEFAULT_BUFFER_SIZE);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        while (true){
            try {
                if (!((size = channel.read(buffer)) > 0)) break;
            } catch (IOException e) {
                e.printStackTrace();
            }
            buffer.flip();
            baos.write(buffer.array(), 0, size);
            buffer.clear();
        }
        return baos.toByteArray();
    }
}
