# Jat - Java chat 

* Uses NIO ServerSocketChannel, SocketChannel & Selectors
* Both client and server uses selectors
* Both client and server uses 1 thread for communicating 
* Client uses a separate UI thread
* First message sent is used as a name

# Running
* Server: `java ChatServer.java 9091`
* Client: `java -cp jexer-0.3.2.jar ChatClient.java localhost 9091`
