import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.net.InetSocketAddress;
import java.nio.*;
import java.nio.channels.*;
import java.util.*;

public class ChatServer {
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


    private static class ClientData {
        private String name;
        private boolean active;
		private final Queue<String> messageQueue;

		public ClientData() {
			messageQueue = new LinkedList<>();
		} 

        public void setName(final String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }

        public void setActive(final boolean active) {
            this.active = active;
        }

        public boolean isActive() {
            return active;
        }

		public void add(String message) {
			messageQueue.add(message);
		}

		public String poll() {
			if (messageQueue.isEmpty()) return null;
			return messageQueue.poll();
		}
    }

    private static class ChatService extends Thread {
        private final ServerSocketChannel serverChannel;
        private final Selector selector;
        private final ByteBuffer buf;
        private final ByteBuffer wbuf;
		private final Map<String, ClientData> clients;

        public ChatService(int port) throws IOException {
            super();
            serverChannel = ServerSocketChannel.open();
            serverChannel.socket().bind(new InetSocketAddress(port));
            serverChannel.configureBlocking(false);
            selector = Selector.open();
            serverChannel.register(selector, SelectionKey.OP_ACCEPT);
            buf = ByteBuffer.allocate(Message.MAX_SIZE + 1);
            wbuf = ByteBuffer.allocate(Message.MAX_SIZE + 1);
			clients = new LinkedHashMap<>();
        }

        @Override
        public void run() {
            while (true) {
                try {
                    this.select();
                } catch (IOException ex) {
					ex.printStackTrace();
                }
            }
        }

        private void select() throws IOException {
            final int ready = selector.selectNow();
            if (ready == 0) return;
            Set<SelectionKey> selectedKeys = selector.selectedKeys();
            Iterator<SelectionKey> keyIterator = selectedKeys.iterator();

            while (keyIterator.hasNext()) {
                final SelectionKey key = keyIterator.next();
                // Only server can accept connections
                // Now we can use serverChannel.accept() to get client channel
                // We should register this to select so we can read using same selector
                if (key.isAcceptable()) {
                    onAcceptClient(key);
                }
                // A client is readable
                // Now we can read what they send us
                if (key.isReadable()) {
                    onMessageReceived(key);
                }
				// Client is writable
				// Now we can get a message from message queue and send it
				if (key.isWritable()) {
					sendMessage(key);
				}
                keyIterator.remove();
            }
        }

        private void onAcceptClient(final SelectionKey key) throws IOException {
            final SocketChannel client = serverChannel.accept();
            client.configureBlocking(false);
            client.register(selector, SelectionKey.OP_READ | SelectionKey.OP_WRITE);
			System.out.println("Client connected");
        }

		private void sendMessage(final SelectionKey key) throws IOException {
			final ClientData clientData  = (ClientData)key.attachment();
            final SocketChannel channel = (SocketChannel)key.channel();
			if (clientData == null) return;
			final String messageStr = clientData.poll();
			if (messageStr == null) {
				return;
			}
			wbuf.clear();
			final Message message = new Message(messageStr);
			message.writeToBuffer(wbuf);
			wbuf.flip();
			while (wbuf.hasRemaining()) {
				channel.write(wbuf);
			}
			System.out.printf("-> %s << %s%n", clientData.getName(), messageStr);
		}

        private void onMessageReceived(final SelectionKey key) throws IOException {
            ClientData clientData = (ClientData)key.attachment();
            final SocketChannel channel = (SocketChannel)key.channel();
            final int bytesRead = channel.read(buf);

            if (bytesRead != -1) {
                buf.flip();  // make buffer ready for read
                final Message message = new Message(buf);
                if (message.isPossiblyCorrupted()) {
                    buf.clear();
                    return;
                }
                if (clientData == null) {
                    // First message is the name
                    // We will set it to clientData
					clientData = new ClientData();
                    clientData.setName(message.getData());
                    clientData.setActive(true);
					key.attach(clientData);
					System.out.printf("'%s' Logged in %n", clientData.getName());
					clients.put(message.getData(), clientData);
                } else if (clientData != null && clientData.isActive()) {
                    // Message from this client is received.
					String receivedMessage = message.getData();
					String clientName = clientData.getName();
                    System.out.printf(
                        "%s: %s%n",
                        clientName,
                       	receivedMessage 
                    );
                    if (message.exitRequested()) {
                        clientData.setActive(false);
						this.clients.remove(clientData.getName());
						channel.close();
                    }
					for (Map.Entry<String, ClientData> clientEntry: clients.entrySet()) {
						if (!clientEntry.getKey().equals(clientName)) {
							clientEntry.getValue().add(clientName + ":" + receivedMessage);
						}	
					}
				}
                buf.clear(); // make buffer ready for writing
            }
        }
    }

    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("Usage: java ChatServer.java PORT");
            System.err.println("Example: java ChatServer.java 9091");
            System.exit(ERR_EXIT);
        }
        int port;
        try {
            port = Integer.parseInt(args[0]);
        } catch (NumberFormatException ignored) {
            System.err.println("Invalid port");
            System.exit(ERR_EXIT);
            return;
        }
        ChatService chat;
        try {
            chat = new ChatService(port);
        } catch(IOException ignored) {
            System.err.println("Cannot create service");
            System.exit(ERR_EXIT);
            return;
        }
        chat.start();
        try {
            chat.join();
        } catch (InterruptedException ignored) {}
    }
}
