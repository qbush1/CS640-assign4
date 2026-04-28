import java.net.DatagramPacket;
import java.net.DatagramSocket;

public class TCPreceiver {

    private DatagramSocket socket;
    private FileOutputStream outputFile;
    private int port;
    private int mtu;
    private int sws;
    private int TCP_HEADER_SIZE;
    private int seqNum;

    public TCPreceiver(int port, String fileName, int mtu, int sws) {
        this.port = port;
        this.mtu = mtu;
        this.sws = sws;
        this.TCP_HEADER_SIZE = 24;
        this.seqNum = 0;
        try {
            socket = new DatagramSocket(port);
            outputFile = new FileOutputStream(fileName);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // 1. open socket / output file
    // 2. wait for handshake
    // 3. receive data packets
    // 4. send ACKs
    // 5. handle teardown
    // 6. print stats
    public void run() {

        establishConnection();
        receiveDataPackets();
        terminateConnection();
    }
    
    public void establishConnection() {
        // Implementation for establishing a TCP connection (e.g., waiting for SYN, sending SYN-ACK, etc.)
        byte[] buffer = new byte[mtu + TCP_HEADER_SIZE];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        try {
            while (true) {
                socket.receive(packet);
                TCPsegment segment = TCPsegment.deserialize(packet.getData());
                if (segment.isSYN()) {
                    // Send SYN-ACK
                    TCPsegment synAck = new TCPsegment(this.seqNum, segment.getSeqNum() + 1, segment.getTimestamp(), null, true, true, false);
                    byte[] synAckBytes = synAck.serialize();
                    socket.send(new DatagramPacket(synAckBytes, synAckBytes.length, packet.getAddress(), packet.getPort()));
                    break;
                }
            }
            while (true) {
                socket.receive(packet);
                TCPsegment segment = TCPsegment.deserialize(packet.getData());
                if (segment.isACK()) {
                    // Once we get ACK, we can proceed
                    break;
                } else {
                    // Do nothing, we just want the ACK to finish 3-way handshake
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    public void receiveDataPackets() {
        // Implementation for receiving data packets
        byte[] buffer = new byte[mtu + TCP_HEADER_SIZE];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        int nextExpectedSeqNum = 1;
        try {
            while (true) {
                socket.receive(packet);
                TCPsegment segment = TCPsegment.deserialize(packet.getData());
                if (segment.getSeqNum() == nextExpectedSeqNum) {
                    // Process the TCP segment (e.g., write to file, send ACK, etc.)
                    if (segment.getData() != null && segment.getData().length > 0) {
                        outputFile.write(segment.getData());
                    }
                    nextExpectedSeqNum = segment.getSeqNum() + segment.getDataLength();
                    TCPsegment ackPacket = new TCPsegment(this.seqNum, nextExpectedSeqNum, segment.getTimestamp(), null, false, true, false);
                    byte[] ackData = ackPacket.serialize();
                    socket.send(new DatagramPacket(ackData, ackData.length, packet.getAddress(), packet.getPort()));
                
                    if (segment.isFIN()) {
                        // Once sender sends FIN need to stop receiving data and handle termination
                        terminateConnection();
                        break;
                    }
                }
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void terminateConnection() {

    }

}