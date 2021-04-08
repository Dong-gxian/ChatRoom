package cn.sinjinsong.chat.server;

import cn.sinjinsong.chat.server.exception.handler.InterruptedExceptionHandler;
import cn.sinjinsong.chat.server.handler.message.MessageHandler;
import cn.sinjinsong.chat.server.task.TaskManagerThread;
import cn.sinjinsong.chat.server.util.SpringContextUtil;
import cn.sinjinsong.chat.server.websocket.listener.WebSocketListener;
import cn.sinjinsong.chat.server.websocket.listener.WebSocketListenerImpl;
import cn.sinjinsong.chat.server.websocket.protocol.WSProtocol;
import cn.sinjinsong.chat.server.websocket.session.ClientSession;
import cn.sinjinsong.common.domain.Message;
import cn.sinjinsong.common.domain.Task;
import cn.sinjinsong.common.util.ProtoStuffUtil;
import cn.sinjinsong.common.util.Util;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Scanner;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Created by SinjinSong on 2017/3/25.
 */

/**
 * @Slf4j会为当前类生成一个名为log的日志对象
 * Slf4j可以在打印的字符串中添加占位符，以避免字符串的拼接
 */
@Slf4j
public class ChatServer {
    public static final int DEFAULT_BUFFER_SIZE = 1024;
    public static final int PORT = 9000;
    public static final int WS_PORT = 9001;
    public static final String QUIT = "QUIT";
    private AtomicInteger onlineUsers;

    private ServerSocketChannel serverSocketChannel;
    private ServerSocketChannel ws_serverSocketChannel;
    private Selector selector;
    private Selector ws_selector;

    private ExecutorService readPool;

    private BlockingQueue<Task> downloadTaskQueue;
    private TaskManagerThread taskManagerThread;
    private ListenerThread listenerThread;
    private InterruptedExceptionHandler exceptionHandler;

    private WS_ListenerThread WS_ListenerThread;
    private WebSocketListener socketListener;

    public ChatServer() {
        log.info("正在启动服务器...");
        initServer();
//        log.info("服务器已启动");
    }

    private void initServer() {
        try {
            serverSocketChannel = ServerSocketChannel.open();
            ws_serverSocketChannel = ServerSocketChannel.open();
            //切换为非阻塞模式
            serverSocketChannel.configureBlocking(false);
            ws_serverSocketChannel.configureBlocking(false);
            serverSocketChannel.bind(new InetSocketAddress(PORT));
            ws_serverSocketChannel.bind(new InetSocketAddress(WS_PORT));
            //获得选择器
            selector = Selector.open();
            ws_selector = Selector.open();
            //将channel注册到selector上
            //第二个参数是选择键，用于说明selector监控channel的状态
            //可能的取值：SelectionKey.OP_READ OP_WRITE OP_CONNECT OP_ACCEPT
            //监控的是channel的接收状态
            serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);
            ws_serverSocketChannel.register(ws_selector, SelectionKey.OP_ACCEPT);
            this.readPool = new ThreadPoolExecutor(5, 10, 1000, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<Runnable>(10), new ThreadPoolExecutor.CallerRunsPolicy());
            this.downloadTaskQueue = new ArrayBlockingQueue<>(20);
            this.taskManagerThread = new TaskManagerThread(downloadTaskQueue);
            this.taskManagerThread.setUncaughtExceptionHandler(SpringContextUtil.getBean("taskExceptionHandler"));
            this.listenerThread = new ListenerThread();
            this.WS_ListenerThread = new WS_ListenerThread();
            this.socketListener = new WebSocketListenerImpl();
            this.onlineUsers = new AtomicInteger(0);
            this.exceptionHandler = SpringContextUtil.getBean("interruptedExceptionHandler");
            log.info("服务器已启动："+ serverSocketChannel.getLocalAddress());
            log.info("websocket服务器已启动："+ ws_serverSocketChannel.getLocalAddress());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * 启动方法，线程最好不要在构造函数中启动，应该作为一个单独方法，或者使用工厂方法来创建实例
     * 避免构造未完成就使用成员变量
     */
    public void launch() {
        new Thread(listenerThread).start();
        new Thread(WS_ListenerThread).start();
        new Thread(taskManagerThread).start();
    }

    /**
     * 推荐的结束线程的方式是使用中断
     * 在while循环开始处检查是否中断，并提供一个方法来将自己中断
     * 不要在外部将线程中断
     * <p>
     * 另外，如果要中断一个阻塞在某个地方的线程，最好是继承自Thread，先关闭所依赖的资源，再关闭当前线程
     */
    private class ListenerThread extends Thread {

        @Override
        public void interrupt() {
            try {
                try {
                    selector.close();
//                    ws_selector.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            } finally {
                super.interrupt();
            }
        }

        @Override
        public void run() {
            try {
                //如果有一个及以上的客户端的数据准备就绪
                while (!Thread.currentThread().isInterrupted()) {
                    //当注册的事件到达时，方法返回；否则,该方法会一直阻塞  
                    selector.select();//像是一个关卡，注册事件到达时才会放行（继续往下执行）
                    //获取当前选择器中所有注册的监听事件
                    for (Iterator<SelectionKey> it = selector.selectedKeys().iterator(); it.hasNext(); ) {
                        SelectionKey key = it.next();
                        //删除已选的key,以防重复处理
                        it.remove();//dgx：我怎么感觉这里多余了
                        //如果"接收"事件已就绪
                        if (key.isAcceptable()) {
                            //交由接收事件的处理器处理
                            handleAcceptRequest();
                        } else if (key.isReadable()) {//在这里判断是websocket连接还是来自普通客户端的连接
                            //如果"读取"事件已就绪
                            //取消可读触发标记，本次处理完后才打开读取事件标记
                            key.interestOps(key.interestOps() & (~SelectionKey.OP_READ));

                            SocketChannel socketChannel = (SocketChannel) key.channel();
                            ClientSession session = (ClientSession) key.attachment();
                            //交由读取事件的处理器处理
                            readPool.execute(new ReadEventHandler(key));//这里的Handler是非阻塞的吗
                        }
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        public void shutdown() {
            Thread.currentThread().interrupt();
        }
    }

    private class WS_ListenerThread extends Thread {

        @Override
        public void interrupt() {
            try {
                try {
                    ws_selector.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            } finally {
                super.interrupt();
            }
        }

        @Override
        public void run() {
            try {
                //如果有一个及以上的客户端的数据准备就绪
                while (!Thread.currentThread().isInterrupted()) {
                    //当注册的事件到达时，方法返回；否则,该方法会一直阻塞
                    ws_selector.select();//像是一个关卡，注册事件到达时才会放行（继续往下执行）
                    //获取当前选择器中所有注册的监听事件
                    for (Iterator<SelectionKey> it = ws_selector.selectedKeys().iterator(); it.hasNext(); ) {
                        SelectionKey selectKey = it.next();
                        //删除已选的key,以防重复处理
                        it.remove();//dgx：我怎么感觉这里多余了
                        //如果"接收"事件已就绪
                        if (selectKey.isAcceptable()) {
                            //交由接收事件的处理器处理
                            WS_handleAcceptRequest();
                        } else if (selectKey.isReadable()) {//处理ws连接
                            try {
                                SocketChannel socketChannel = (SocketChannel) selectKey.channel();
                                ClientSession session = (ClientSession) selectKey.attachment();//用前面定义的ClientSession来作为SocketChannel的attach object，方便存储关于SocketChannel的其他信息，容易管理。
                                if (session == null) {//在这里处理握手
                                    //如果SocketChannel还没有被ClientSession绑定，认为这是一个新连接，需要完成握手

                                    byte[] byteArray = Util.readByteArray(socketChannel);
                                    System.out.println(new String(byteArray));
                                    WSProtocol.Header header = WSProtocol.Header.decodeFromString(new String(byteArray));
                                    String receiveKey = header.getHeader("Sec-WebSocket-Key");
                                    String response = WSProtocol.getHandShakeResponse(receiveKey);
                                    socketChannel.write(ByteBuffer.wrap(response.getBytes()));
                                    ClientSession newSession = new ClientSession(socketChannel);
                                    selectKey.attach(newSession);
                                    socketListener.onOpen(newSession);  //握手成功后，打开会话
                                } else {
                                    //收到数据，交给上面定义的接口处理
                                    socketListener.onMessage(session);
                                }
                            }catch (IOException e) {
                                e.printStackTrace();
                                //出现异常，进行一系列处理
                                selectKey.channel().close();
                                selectKey.cancel();

                                ClientSession attSession = (ClientSession) selectKey.attachment();
                                socketListener.onException(attSession, e);  //抛出异常
                                socketListener.onClose(attSession);  //强制关闭抛出异常的连接
                            }
                            //交由读取事件的处理器处理
//                            readPool.execute(new ReadEventHandler(key));//这里的Handler是非阻塞的吗
                        }
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        public void shutdown() {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 关闭服务器
     */
    public void shutdownServer() {
        try {
            taskManagerThread.shutdown();
            listenerThread.shutdown();
            readPool.shutdown();
            serverSocketChannel.close();
            System.exit(0);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * 处理客户端的连接请求
     */
    private void handleAcceptRequest() {
        try {
            SocketChannel client = serverSocketChannel.accept();
            // 接收的客户端也要切换为非阻塞模式
            client.configureBlocking(false);
            // 监控客户端的读操作是否就绪
            client.register(selector, SelectionKey.OP_READ);
            log.info("服务器连接客户端:{}",client);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    private void WS_handleAcceptRequest() {
        try {
            SocketChannel client = ws_serverSocketChannel.accept();
            // 接收的客户端也要切换为非阻塞模式
            client.configureBlocking(false);
            // 监控客户端的读操作是否就绪
            client.register(ws_selector, SelectionKey.OP_READ);
            log.info("WebSocket服务器连接客户端:{}",client);

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * 处于线程池中的线程会随着线程池的shutdown方法而关闭
     */
    private class ReadEventHandler implements Runnable {

        private ByteBuffer buf;
        private SocketChannel client;
        private ByteArrayOutputStream baos;
        private SelectionKey key;

        public ReadEventHandler(SelectionKey key) {
            this.key = key;
            this.client = (SocketChannel) key.channel();
            this.buf = ByteBuffer.allocate(DEFAULT_BUFFER_SIZE);
            this.baos = new ByteArrayOutputStream();
        }

        @Override
        public void run() {
            try {
                int size;
                while ((size = client.read(buf)) > 0) {
                    buf.flip();
                    baos.write(buf.array(), 0, size);//把读出来的内容放到流里
                    buf.clear();
                }
                if (size == -1) {//-1表示连接关闭了
                    return;
                }
                log.info("读取完毕，继续监听");
                //继续监听读取事件
                key.interestOps(key.interestOps() | SelectionKey.OP_READ);
                key.selector().wakeup();//这个地方不太明白:读操作完了就唤醒生产者
                byte[] bytes = baos.toByteArray();
                baos.close();
                Message message = ProtoStuffUtil.deserialize(bytes, Message.class);//反序列化，转换成Message
                MessageHandler messageHandler = SpringContextUtil.getBean("MessageHandler", message.getHeader().getType().toString().toLowerCase());
                try {
                    messageHandler.handle(message, selector, key, downloadTaskQueue, onlineUsers);//交给messagehandler处理
                } catch (InterruptedException e) {
                    log.error("服务器线程被中断");
                    exceptionHandler.handle(client, message);
                    e.printStackTrace();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

    }

    public static void main(String[] args) {
        System.out.println("Initialing...");
        ChatServer chatServer = new ChatServer();
        chatServer.launch();
        Scanner scanner = new Scanner(System.in, "UTF-8");
        while (scanner.hasNext()) {
            String next = scanner.next();
            if (next.equalsIgnoreCase(QUIT)) {
                System.out.println("服务器准备关闭");
                chatServer.shutdownServer();
                System.out.println("服务器已关闭");
            }
        }
    }
}
