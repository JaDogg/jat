import static jexer.TCommand.*;
import static jexer.TKeypress.*;
import jexer.*;
import jexer.event.TCommandEvent;
import jexer.layout.StretchLayoutManager;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.net.InetSocketAddress;
import java.nio.*;
import java.nio.channels.*;
import java.util.*;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.ConcurrentLinkedQueue;

public class ChatClient {
    private static final int ERR_EXIT = -1;

    // Message
    // Structure: [SIZE, data....]
    private static class Message {
        public static final int MAX_SIZE = 128;
        private static final Charset DEFAULT_CHARSET = StandardCharsets.UTF_8;

        private final String data;
        private final boolean possiblyCorrupted;

        // Decode mesasge using bytebuffer
        public Message(final ByteBuffer buffer) {
            final byte[] bytes = buffer.array();
            int dataLength = (int)bytes[0];
            if (bytes[0] <= 0) {
                this.data = "";
                this.possiblyCorrupted = true;
                return;
            }
            this.data = new String(bytes, 1, dataLength, DEFAULT_CHARSET);
            this.possiblyCorrupted  = false;
        }

        // Create new message from string
        public Message(final String message) {
            this.data = message;
            this.possiblyCorrupted = false;
        }

        public String getData() {
            return data;
        }

        public boolean exitRequested() {
            return "bye".equalsIgnoreCase(data);
        }

        public boolean isPossiblyCorrupted() {
            return possiblyCorrupted;
        }

        public void writeToBuffer(final ByteBuffer buffer) {
            buffer.clear();
            byte[] bytes = this.data.getBytes(DEFAULT_CHARSET);
            buffer.put((byte)bytes.length);
            buffer.put(bytes);
        }
    }

    private static class ClientThread extends Thread {
        private final SocketChannel clientChannel;
        private final ByteBuffer buf;
        private final ByteBuffer wbuf;
        private boolean timeToExit;
        private final ConcurrentLinkedQueue<String> outgoingQueue;
        private final ConcurrentLinkedQueue<String> incomingQueue;
        private final String host;
        private final int port;
        private final Selector selector;

        public ClientThread(final String host, final int port,
                            final ConcurrentLinkedQueue<String> incomingQueue,
                            final ConcurrentLinkedQueue<String> outgoingQueue) throws IOException {

            super();
            this.host = host;
            this.port = port;
            selector = Selector.open();
            clientChannel = SocketChannel.open();
            clientChannel.configureBlocking(false);
            clientChannel.register(selector, SelectionKey.OP_CONNECT);
            clientChannel.connect(new InetSocketAddress(host, port));
            buf = ByteBuffer.allocate(Message.MAX_SIZE + 1);
            wbuf = ByteBuffer.allocate(Message.MAX_SIZE + 1);
            timeToExit = false;
            this.incomingQueue = incomingQueue;
            this.outgoingQueue = outgoingQueue;
        }

        @Override
        public void run() {
            while (!timeToExit) {
                try {
                    this.select();
                } catch (IOException ex) {
                    ex.printStackTrace();
                }
            }
        }


        private void select() throws IOException {
            final int ready = selector.select();
            if (ready == 0) return;
            Set<SelectionKey> selectedKeys = selector.selectedKeys();
            Iterator<SelectionKey> keyIterator = selectedKeys.iterator();

            while (keyIterator.hasNext()) {
                final SelectionKey key = keyIterator.next();

                if (key.isConnectable()) {
                    SocketChannel channel = (SocketChannel)key.channel();
                    if (channel.finishConnect()) {
                        System.out.println("Connected");
                        key.interestOps(SelectionKey.OP_READ | SelectionKey.OP_WRITE);
                    }
                }
                if (key.isReadable()) {
                    System.out.println("Reading...");
                    SocketChannel channel = (SocketChannel)key.channel();
                    buf.clear();
                    final int bytesRead = channel.read(buf);
                    if (bytesRead != -1) {
                        buf.flip();
                        Message incoming = new Message(buf);
                        if (!incoming.isPossiblyCorrupted()) {
                            this.incomingQueue.offer(incoming.getData());
                        }
                    }
                    buf.clear();
                }
                if (key.isWritable()) {
                    final String outgoingMessageStr = this.outgoingQueue.poll();
                    if (outgoingMessageStr != null) {
                        System.out.println("writing -> "+ outgoingMessageStr);
                        wbuf.clear();
                        Message messageToWrite = new Message(outgoingMessageStr);
                        messageToWrite.writeToBuffer(wbuf);
                        wbuf.flip();
                        while (wbuf.hasRemaining()) {
                            clientChannel.write(wbuf);
                        }
                        // 'bye' is typed, chat client should exit now
                        if (messageToWrite.exitRequested()) {
                            this.timeToExit = true;
                        }
                    }
                }
                keyIterator.remove();
            }
        }
    }

    private static class ChatWindow extends TWindow {
        private final TList messageList;
        private final TField inputBox;
        private final List<String> messages;
        private final ConcurrentLinkedQueue<String> incomingQueue;
        private final ConcurrentLinkedQueue<String> outgoingQueue;

        public ChatWindow(final TApplication parent, ConcurrentLinkedQueue<String> incomingQueue, ConcurrentLinkedQueue<String> outgoingQueue) {
            super(parent, "Chat", 0, 0, 40, 20, CENTERED);
            this.incomingQueue = incomingQueue;
            this.outgoingQueue = outgoingQueue;
            messages = new LinkedList<>();
            messageList = addList(messages, 1, 1, getWidth() - 4, getHeight() - 6);
            inputBox = addField(1, getHeight() - 4, getWidth() - 4, true);
            inputBox.setEnterAction(new TAction() {
                public void DO() {
                    final String myMessage = inputBox.getText();
                    if (myMessage == null || myMessage.isBlank()) {
                        return;
                    }
                    // Reset Inputbox
                    inputBox.setText("");
                    inputBox.home();
                    // add message to list
                    messages.add("[me]:" + myMessage);
                    messageList.setList(messages);
                    messageList.toBottom();
                    // Schedule my message to send
                    outgoingQueue.offer(myMessage);
                    System.out.println("->" + myMessage);
                }
            });
            parent.addTimer(100, true, new TAction() {
                public void DO() {
                    final String incomingMessage = incomingQueue.poll();
                    if (incomingMessage == null || incomingMessage.isBlank()) {
                        return;
                    }

                    messages.add(incomingMessage);
                    messageList.setList(messages);
                    messageList.toBottom();
                }
            });
        }
    }

    private static class ClientApplication extends TApplication {
        public ClientApplication(ConcurrentLinkedQueue<String> incomingQueue, ConcurrentLinkedQueue<String> outgoingQueue) throws Exception {
            super(TApplication.BackendType.SWING);
            addFileMenu();
            new ChatWindow(this, incomingQueue, outgoingQueue);
        }
    }

    public static void main(String[] args) {
        if (args.length != 2) {
            System.err.println("Usage: java ChatClient.java HOST PORT");
            System.err.println("Example: java ChatClient.java localhost 9091");
            System.exit(ERR_EXIT);
        }
        String host = args[0];
        int port;
        try {
            port = Integer.parseInt(args[1]);
        } catch (NumberFormatException ignored) {
            System.err.println("Invalid port");
            System.exit(ERR_EXIT);
            return;
        }
        ConcurrentLinkedQueue<String> incomingQueue = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<String> outgoingQueue = new ConcurrentLinkedQueue<>();
        ClientThread chat;
        try {
            chat = new ClientThread(host, port, incomingQueue, outgoingQueue);
        } catch(IOException ex) {
            System.err.println("Cannot create client");
            ex.printStackTrace();
            System.exit(ERR_EXIT);
            return;
        }
        chat.start();
        try {
            ClientApplication application = new ClientApplication(incomingQueue, outgoingQueue);
            new Thread(application).start();
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        try {
            chat.join();
        } catch (InterruptedException ignored) {}
    }
}
