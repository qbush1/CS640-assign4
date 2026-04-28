import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.DatagramPacket;
import java.utils.Arrays;

public class TCPsender {

    private DatagramSocket socket;
    private InetAddress remoteIP;
    private int mtu;
    private int sws;
    private int remotePort;
    private Socket socket;
    private byte[] fileData;
    private int fileSize;

    private double ERTT = 0;
    private double EDEV = 0;

    private int lastAckNum = 0;
    private int dupAckCount = 0;

    private SlidingWindow window;

    public TCPsender(int port, InetAddress remoteIP, int remotePort, String filename, int mtu, int sws) {
        this.mtu = mtu;
        this.sws = sws;
        this.remoteIP = remoteIP;
        this.remotePort = remotePort;

        this.socket = new DatagramSocket(port);
        socket.setSoTimer(10);
        fileData = loadFile(filename);
        fileSize = fileData.length;
        window = new SlidingWindow(sws);
    }

    public void run() throws IOException {
        establishConnection();
        sendData();
        terminateConnection();
        printStatistics();
    }

    public void establishConnection() {
        // send SYN
        TCPsegment syn = new TCPsegment(0, 0, System.currentTimeMillis(), null, true, false, false);
        sendSegment(syn);

        // wait for SYN+ACK
        TCPsegment synAck = receiveSegment();
        while(synAck == null || !synAck.isSYN() || !synAck.isACK()) {
            synAck = receiveSegment();
        }

        // send ACK
        TCPsegment ack = new TCPsegment(1, synAck.getSeqNum() + 1, System.currentTimeMillis(), null, false, true, false);
        sendSegment(ack);
    }

    public void sendData() {
        // Implementation for sending data segments, handling acknowledgments, retransmissions, etc.
        int bytesSent = 0;
        while (bytesSent < fileSize) {
            int chunkSize = Math.min(mtu - 24, fileSize - bytesSent);
            byte[] chunk = Arrays.copyOfRange(fileData, bytesSent, bytesSent + chunkSize);

            TCPsegment segment = new TCPsegment(bytesSent + 1, 0, System.currentTimeMillis(), chunk, false, true, false);
            sendSegment(segment);
            bytesSent += chunkSize;
    }

    private void sendSegment(TCPsegment segment) {
        // Implementation for sending a TCP segment over the network
        byte[] bytes = segment.serialize();
        DatagramPacket packet = new DatagramPacket(bytes, bytes.length, remoteIP, remotePort);
        socket.send(packet);
    }

    private TCPsegment receiveSegment() {
        // Implementation for receiving a TCP segment from the network
        byte[] buffer = new byte[mtu + 24];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        socket.receive(packet);
        return TCPsegment.deserialize(packet.getData());
    }

    public void processAck(int ackNum) {
        // Implementation for processing received ACKs, updating the sender's state, etc.
        int ackNum = ack.getAckNum();

    }

    
    public void retransmitWindow(int seqNum) {
        // Implementation for retransmitting a segment with the given sequence number
    }

    public void terminateConnection() {
        // Implementation for terminating a TCP connection (e.g., sending FIN, waiting for ACK, etc.)
    }
    
    protected void updateTimeout(long rtt) {
        // Implementation for updating the timeout value based on RTT measurements

    }

    protected void printStatistics() {
        // Implementation for printing final statistics (e.g., total segments sent, retransmissions, etc.)
    }
}