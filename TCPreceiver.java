import java.net.DatagramPacket;

public class TCPreceiver {

    private DatagramSocket socket;
    private FileOutputStream outputFile;
    private int port;
    private int mtu;
    private int sws;
    private int TCP_HEADER_SIZE;

    public TCPreceiver(int port, String fileName, int mtu, int sws) {
        this.port = port;
        this.mtu = mtu;
        this.sws = sws;
        this.TCP_HEADER_SIZE = 24; 
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


    }
    
    public void establishConnection() {
        // Implementation for establishing a TCP connection (e.g., waiting for SYN, sending SYN-ACK, etc.)

    }

    public void receiveDataPackets() {
        // Implementation for receiving data packets
        byte[] buffer = new byte[mtu + TCP_HEADER_SIZE];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        try {
            socket.receive(packet); //blocks until something arrives
            TCPsegment segment = TCPsegment.deserialize(packet.getData());
            // Process the TCP segment (e.g., write to file, send ACK, etc.)
            if (segment.getType() == TCPsegment.SegmentType.DATA) {
                outputFile.write(segment.getData());
                sendACK(segment.getSequenceNumber());
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}