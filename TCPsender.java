import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.DatagramPacket;
import java.utils.Arrays;
import java.utils.List;

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
    private boolean firstAck = true;

    // constructor for TCPsender
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
        TCPsegment syn = new TCPsegment(0, 0, System.nanoTime(), null, true, false, false);
        sendSegment(syn);

        // wait for SYN+ACK
        TCPsegment synAck = receiveSegment();
        while(synAck == null || !synAck.isSYN() || !synAck.isACK()) {
            synAck = receiveSegment();
        }

        // send ACK
        TCPsegment ack = new TCPsegment(1, synAck.getSeqNum() + 1, System.nanoTime(), null, false, true, false);
        sendSegment(ack);
    }

    public void sendData() {
        // Implementation for sending data segments, handling acknowledgments, retransmissions, etc.
        int bytesSent = 0;
        while (bytesSent < fileSize) {
            // fill window
            while (window.canSend() && bytesSent < fileSize) {
                int chunkSize = Math.min(mtu - 24, fileSize - bytesSent);
                byte[] chunk = Arrays.copyOfRange(fileData, bytesSent, bytesSent + chunkSize);
                TCPsegment segment = new TCPsegment(bytesSent + 1, 0, System.nanoTime(), chunk, false, false, false);
                sendSegment(segment);
                window.markSent(segment.getSeqNum, segment);
                bytesSent += chunkSize;
            }  

            // try to receive ACK
            TCPsegment ack = receiveSegment();
            if(ack != null && ack.isValidChecksum()) {
                processAck(ack);
            }

            // check timeout
            if(window.isExpired(currentTimeout)) {
                retransmitWindow();
                window.resetWindowTimer();
            }
    }

    private void sendSegment(TCPsegment segment) {
        // Implementation for sending a TCP segment over the network
        byte[] bytes = segment.serialize();
        DatagramPacket packet = new DatagramPacket(bytes, bytes.length, remoteIP, remotePort);
        socket.send(packet);
    }

    private TCPsegment receiveSegment() {
        // Implementation for receiving a TCP segment from the network
        try {
            byte[] buffer = new byte[mtu + 24];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            socket.receive(packet);
            return TCPsegment.deserialize(packet.getData());
        } catch (SocketTimeoutException e) {
            return null;
        } catch (IOException e) {
            return null;
        }
    }

    public void processAck(TCPsegment ack) {
        // Implementation for processing received ACKs, updating the sender's state, etc.
        int ackNum = ack.getAckNum();

        if(!window.wasRetransmitted(ackNum)) {
            updateTimeout(ack.getTimestamp());
        }

        if(ackNum == lastAckNum) {
            dupAckCount++;
            if(dupAckCount >= 3) {
                // fast retransmit
                retransmitWindow();
                window.resetWindowTimer();
                dupAckCount = 0;
            }
        } else {
            lastAckNum = ackNum;
            dupAckCount = 0;
            window.advance(ackNum);
        }

    }

    
    public void retransmitWindow() {
        // Implementation for retransmitting a segment with the given sequence number
        List<TCPsegment> segs = window.getSegments();

        for (TCPsegment seg : segs) {
            if (window.maxRetransmitsReached(seg.getSeqNum())) {
                System.err.println("Max retransmissions reached, giving up");
                System.exit(1);
            }
            // rebuild segment with fresh timestamp for RTT measurement
            TCPsegment resend = new TCPsegment(seg.getSeqNum(), seg.getAckNum(),
                                               System.nanoTime(), seg.getData(),
                                               seg.isSYN(), seg.isACK(), seg.isFIN());
            sendSegment(resend);
            window.markRetransmitted(seg.getSeqNum());
            window.incrementRetransmitCount(seg.getSeqNum());
            retransmissions++;
        }
    }

    public void terminateConnection() {
        // Implementation for terminating a TCP connection (e.g., sending FIN, waiting for ACK, etc.)
        TCPsegment fin = new TCPsegment(filesize + 1, 0, System.nanoTime(), null, false, false, true);
        sendSegment(fin);
        window.markSent(fin.getSeqNum(), fin);

        TCPsegment ack = receiveSegment();
        while(ack == null || !ack.isAck()) {
            ack = receiveSegment();
        }
        window.advance(ack.getAckNum());

        TCPsegment rcvFin = receiveSegment();
        while(rcvFin == null || !rcvFin.isFin()) {
            rcvFin = receiveSegment();
        }

        TCPsegment finalAck = new TCPsegment(fileSize + 2, rcvFin.getSeqNum() + 1, System.nanoTime(), null, false, true, false);
        sendSegment(finalAck);

    }
    
    private void updateTimeout(long rtt) {
        // Implementation for updating the timeout value based on RTT measurements

        double SRTT = System.nanoTime() - timestamp;
        if(firstAck) {
            ERTT = SRTT;
            EDEV = 0;
            currentTimeout = (long)(2 * ERTT);
            firstAck = false;
        } else {
            double SDEV = Math.abs(SRTT - ERTT);
            ERTT = 0.875 * ERTT + 0.125 * SRTT;
            EDEV = 0.75 * EDEV + 0.25 * SDEV;
            currentTimeout = (long)(ERTT + 4 * EDEV);
        }

    }

    private void printStatistics() {
        // Implementation for printing final statistics (e.g., total segments sent, retransmissions, etc.)
    }

    private class SlidingWindow {
        private class WindowSlot {
            TCPsegment segment;
            int retransmitCount;
            int wasRetransmitted;
        }
        private WindowSlot[] slots;
        private int windowSize;
        private long windowSendTime;
        private int base;
        private int nextSlot;
        private int inFlight;

        SlidingWindow(int windowSize) {
            this.windowSize = windowSize;
            this.slots = new WindowSlot[windowSize];
            this.base = 0;
            this.nextSlot = 0;
            this.inFlight = 0;
            this.windowSendTime = System.nanoTime();
        }

        void markSent(int seqNum, TCPsegment segment) {
            WindowSlot = new WindowSlot();
            slot.segment = segment;
            slot.retransmitCount = 0;
            slot.wasRetransmitted = false;
            slots[nextSlot % windowSize] = slot;
            nextSlot++;
            inFlight++;
            windowSendTime = System.nanoTime();
        }

        boolean canSend() {
            return inFlight < windowSize;
        }

        void advance(int ackNum) {
            while(base < nextSlot && 
                slots[base % windowSize] != null && 
                slots[base % windowSize].segment.getSeqNum() < ackNum) {

                slots[base % windowSize] == null;
                base++;
                inFlight--;
            }
        }

        boolean isExpired() {
            if(inFlight == 0) return false;
            return System.nanoTime() - windowSendTime > timeout;
        }

        List<TCPsegment> getSegments() {
            List<TCPsegment> segs = new ArrayList<>();
            for (int i = base; i < nextSlot; i++) {
                WindowSlot slot = slots[i % windowSize];
                if (slot != null) segs.add(slot.segment);
            }
            return segs;
        }

        void resetWindowTimer() {
            windowSendTime = System.nanoTime();
        }

        void markRetransmitted(int seqNum) {
            for (int i = base; i < nextSlot; i++) {
                WindowSlot slot = slots[i % windowSize];
                if (slot != null && slot.segment.getSeqNum() == seqNum) {
                    slot.wasRetransmitted = true;
                    return;
                }
            }
        }

        void incrementRetransmitCount(int seqNum) {
            for (int i = base; i < nextSlot; i++) {
                WindowSlot slot = slots[i % windowSize];
                if (slot != null && slot.segment.getSeqNum() == seqNum) {
                    slot.retransmitCount++;
                    return;
                }
            }
        }

        boolean maxRetransmitsReached(int seqNum) {
            for (int i = base; i < nextSlot; i++) {
                WindowSlot slot = slots[i % windowSize];
                if (slot != null && slot.segment.getSeqNum() == seqNum) {
                    return slot.retransmitCount >= 16;
                }
            }
            return false;
        }

        boolean wasRetransmitted(int ackNum) {
            for (int i = base; i < nextSlot; i++) {
                WindowSlot slot = slots[i % windowSize];
                if (slot != null && slot.segment.getSeqNum() < ackNum) {
                    return slot.wasRetransmitted;
                }
            }
            return false;
        }

    }

}