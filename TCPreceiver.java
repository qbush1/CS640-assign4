import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;

public class TCPreceiver {

    private DatagramSocket socket;
    private FileOutputStream outputFile;
    private int mtu;
    private int TCP_HEADER_SIZE;
    private int seqNum;
    private int nextExpectedSeqNum;
    private InetAddress senderAddress;
    private int senderPort;
    private long startTime;

    // stats
    private int incorrectChecksum;
    private int outOfSeqPackets;
    private int dataReceived;
    private int packetsReceived;
    private int retransmissions;

    public TCPreceiver(int port, String fileName, int mtu, int sws) {
        this.mtu = mtu;
        this.TCP_HEADER_SIZE = 24;
        this.seqNum = 0;
        this.startTime = System.nanoTime();
        try {
            socket = new DatagramSocket(port);
            outputFile = new FileOutputStream(fileName);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void log(String direction, TCPsegment seg) {
        double time = (System.nanoTime() - startTime) / 1e9;
        String s = seg.isSYN() ? "S" : "-";
        String a = seg.isACK() ? "A" : "-";
        String f = seg.isFIN() ? "F" : "-";
        String d = seg.getDataLength() > 0 ? "D" : "-";
        System.out.printf("%s %.3f %s %s %s %s %d %d %d%n",
            direction, time, s, a, f, d,
            seg.getSeqNum(), seg.getDataLength(), seg.getAckNum());
    }

    private void printStats() {
        System.out.printf("%db %d %d %d %d %d%n",
            dataReceived, packetsReceived, outOfSeqPackets,
            incorrectChecksum, retransmissions, 0);
    }

    public void run() {
        establishConnection();
        receiveDataPackets();
        terminateConnection();
        printStats();
    }

    public void establishConnection() {
        byte[] buffer = new byte[mtu + TCP_HEADER_SIZE];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        try {
            // Wait for SYN (block indefinitely - sender initiates)
            while (true) {
                socket.receive(packet);
                TCPsegment segment = TCPsegment.deserialize(packet.getData());
                if (!segment.isValidChecksum()) { incorrectChecksum++; continue; }
                if (segment.isSYN()) {
                    log("rcv", segment);
                    packetsReceived++;
                    break;
                }
            }
            TCPsegment syn = TCPsegment.deserialize(packet.getData());

            // Save sender's address for use in terminateConnection
            senderAddress = packet.getAddress();
            senderPort = packet.getPort();

            // Build SYN-ACK once, reuse on retransmits
            TCPsegment synAck = new TCPsegment(0, syn.getSeqNum() + 1, syn.getTimestamp(), null, true, true, false);
            byte[] synAckBytes = synAck.serialize();

            // Send SYN-ACK, retransmit if final ACK doesn't arrive
            int retries = 0;
            socket.setSoTimeout(5000);
            while (retries < 16) {
                socket.send(new DatagramPacket(synAckBytes, synAckBytes.length, senderAddress, senderPort));
                log("snd", synAck);
                if (retries > 0) retransmissions++;
                try {
                    socket.receive(packet);
                    TCPsegment response = TCPsegment.deserialize(packet.getData());
                    if (!response.isValidChecksum()) { incorrectChecksum++; continue; }
                    if (response.isACK()) {
                        log("rcv", response);
                        packetsReceived++;
                        break;
                    }
                } catch (SocketTimeoutException e) {
                    retries++;
                }
            }
            socket.setSoTimeout(0); // restore blocking mode for data phase

            if (retries == 16) {
                System.err.println("Handshake failed after 16 retransmissions");
                System.exit(1);
            }

            this.seqNum = 1; // SYN-ACK consumed sequence number 0

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void receiveDataPackets() {
        byte[] buffer = new byte[mtu + TCP_HEADER_SIZE];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        nextExpectedSeqNum = 1;
        try {
            while (true) {
                socket.receive(packet);
                TCPsegment segment = TCPsegment.deserialize(packet.getData());

                if (!segment.isValidChecksum()) {
                    incorrectChecksum++;
                    continue;
                }

                boolean isData = segment.getDataLength() > 0 && segment.isACK();
                boolean isFin  = segment.isFIN();
                if (!isData && !isFin) continue; // ignore unexpected control packets

                if (segment.getSeqNum() == nextExpectedSeqNum) {
                    // Count received packets, don't count ones that are dropped
                    log("rcv", segment);
                    packetsReceived++;
                    
                    if (segment.getData() != null && segment.getData().length > 0) {
                        outputFile.write(segment.getData());
                        dataReceived += segment.getDataLength();
                    }
                    nextExpectedSeqNum = segment.getSeqNum() + segment.getDataLength();
                    if (segment.isFIN()) nextExpectedSeqNum++; // FIN consumes 1 seq number

                    TCPsegment ackPacket = new TCPsegment(this.seqNum, nextExpectedSeqNum, segment.getTimestamp(), null, false, true, false);
                    byte[] ackData = ackPacket.serialize();
                    socket.send(new DatagramPacket(ackData, ackData.length, packet.getAddress(), packet.getPort()));
                    log("snd", ackPacket);

                    if (segment.isFIN()) return;
                } else {
                    // Out of order — drop and let sender retransmit via timeout
                    outOfSeqPackets++;
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void terminateConnection() {
        // receiveDataPackets already handled: receive sender FIN, send ACK
        // Here we send our own FIN and wait for the sender's final ACK
        byte[] buffer = new byte[mtu + TCP_HEADER_SIZE];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        try {
            TCPsegment fin = new TCPsegment(this.seqNum, nextExpectedSeqNum, System.nanoTime(), null, false, true, true);
            byte[] finBytes = fin.serialize();

            int retries = 0;
            socket.setSoTimeout(5000);
            while (retries < 16) {
                socket.send(new DatagramPacket(finBytes, finBytes.length, senderAddress, senderPort));
                log("snd", fin);
                
                if (retries > 0) retransmissions++;
                try {
                    socket.receive(packet);
                    TCPsegment response = TCPsegment.deserialize(packet.getData());
                    if (!response.isValidChecksum()) { incorrectChecksum++; continue; }
                    if (response.isACK()) {
                        log("rcv", response);
                        packetsReceived++;
                        break;
                    }
                } catch (SocketTimeoutException e) {
                    retries++;
                }
            }

            if (retries == 16)
                System.err.println("Teardown: no ACK received after 16 retransmissions");

            this.seqNum++; // FIN consumed one sequence number

            outputFile.close();
            socket.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
