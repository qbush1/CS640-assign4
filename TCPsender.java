import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.DatagramPacket;
import java.util.Arrays;
import java.util.List;
import java.util.ArrayList;
import java.net.SocketTimeoutException;
import java.io.IOException;
import java.io.File;
import java.io.FileInputStream;

public class TCPsender {

    private DatagramSocket socket;
    private InetAddress remoteIP;
    private int port;
    private int mtu;
    private int sws;
    private int remotePort;
    private byte[] fileData;
    private int fileSize;

    private double ERTT = 0;
    private double EDEV = 0;

    private int lastAckNum = 0;
    private int dupAckCount = 0;
    private int receiverNextSeq = 0; // receiver's next expected seq, used as ackNum in data packets

    private SlidingWindow window;
    private boolean firstAck = true;
    private int outOfSeqPackets;
    private int dataSent;
    private int packetsSent;
    private int numRetransmissions;
    private int totalDupAcks;
    private long currentTimeout = 5_000_000_000L;
    private long startTime;

    // constructor for TCPsender
    public TCPsender(int port, InetAddress remoteIP, int remotePort, String filename, int mtu, int sws) throws IOException {
        this.port = port;
        this.mtu = mtu;
        this.sws = sws;
        this.remoteIP = remoteIP;
        this.remotePort = remotePort;
        this.startTime = System.nanoTime();

        this.socket = new DatagramSocket(port);
        socket.setSoTimeout(10);
        fileData = loadFile(filename);
        fileSize = fileData.length;
        window = new SlidingWindow(sws);
    }

    public void run() throws IOException {
        establishConnection();
        sendData();
        terminateConnection();
        printStats();
    }

    public void establishConnection() throws IOException {
        // send SYN
        TCPsegment syn = new TCPsegment(0, 0, System.nanoTime(), null, true, false, false);
        sendSegment(syn);
        window.markSent(syn);

        // wait for SYN+ACK
        TCPsegment synAck = receiveSegment();
        while(synAck == null || !(synAck.isSYN() && synAck.isACK())) {
            synAck = receiveSegment();

            if(window.isExpired(currentTimeout)) {
                sendSegment(syn);
                window.resetWindowTimer();
            }
        }
        log("rcv", synAck);

        // track receiver's seqNum so data packets can ACK it correctly
        receiverNextSeq = synAck.getSeqNum() + 1;

        // send ACK
        TCPsegment ack = new TCPsegment(1, receiverNextSeq, System.nanoTime(), null, false, true, false);
        sendSegment(ack);

        // clear SYN from window so it isn't retransmitted during data phase
        window.advance(1);
    }

    public void sendData() throws IOException {
        // Implementation for sending data segments, handling acknowledgments, retransmissions, etc.
        int bytesSent = 0;
        while (bytesSent < fileSize || window.inFlightCount() > 0) {
            // fill window
            while (window.canSend() && bytesSent < fileSize) {
                int chunkSize = Math.min(mtu - 24, fileSize - bytesSent);
                byte[] chunk = Arrays.copyOfRange(fileData, bytesSent, bytesSent + chunkSize);
                TCPsegment segment = new TCPsegment(bytesSent + 1, receiverNextSeq, System.nanoTime(), chunk, false, true, false);
                sendSegment(segment);
                window.markSent(segment);
                bytesSent += chunkSize;
                dataSent += chunkSize;
                packetsSent++;
            }  

            // try to receive ACK
            TCPsegment ack = receiveSegment();
            if(ack != null && ack.isValidChecksum()) {
                log("rcv", ack);
                processAck(ack);
            }

            // check timeout
            if(window.isExpired(currentTimeout)) {
                retransmitWindow();
                window.resetWindowTimer();
            }
        }
    }

    private void sendSegment(TCPsegment segment) throws IOException {
        // Implementation for sending a TCP segment over the network
        byte[] bytes = segment.serialize();
        DatagramPacket packet = new DatagramPacket(bytes, bytes.length, remoteIP, remotePort);
        socket.send(packet);
        log("snd", segment);
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

    public void processAck(TCPsegment ack) throws IOException {
        // Implementation for processing received ACKs, updating the sender's state, etc.
        int ackNum = ack.getAckNum();

        if(!window.wasRetransmitted(ackNum)) {
            updateTimeout(ack.getTimestamp());
        }

        if(ackNum == lastAckNum) {
            dupAckCount++;
            totalDupAcks++;
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

    
    public void retransmitWindow() throws IOException {
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
            numRetransmissions++;
        }
    }

    public void terminateConnection() throws IOException{
        // Implementation for terminating a TCP connection (e.g., sending FIN, waiting for ACK, etc.)
        TCPsegment fin = new TCPsegment(fileSize + 1, 0, System.nanoTime(), null, false, false, true);
        sendSegment(fin);
        window.markSent(fin);

        TCPsegment ack = receiveSegment();
        while(ack == null || !ack.isACK()) {
            ack = receiveSegment();
            if(window.isExpired(currentTimeout)) {
                sendSegment(fin);
                window.resetWindowTimer();
            }
        }
        log("rcv", ack);
        window.advance(ack.getAckNum());

        TCPsegment rcvFin = receiveSegment();
        while(rcvFin == null || !rcvFin.isFIN()) {
            rcvFin = receiveSegment();
        }
        log("rcv", rcvFin);

        TCPsegment finalAck = new TCPsegment(fileSize + 2, rcvFin.getSeqNum() + 1, System.nanoTime(), null, false, true, false);
        sendSegment(finalAck);

        // TIME_WAIT: if receiver retransmits FIN (our ACK was dropped), resend ACK
        socket.setSoTimeout(5000);
        try {
            while (true) {
                try {
                    byte[] buf = new byte[mtu + 24];
                    DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                    socket.receive(pkt);
                    TCPsegment seg = TCPsegment.deserialize(pkt.getData());
                    if (seg.isFIN()) {
                        sendSegment(finalAck);
                    }
                } catch (SocketTimeoutException e) {
                    break; // no retransmit within 5s, receiver got the ACK
                }
            }
        } finally {
            socket.setSoTimeout(10);
        }
    }
    
    private void updateTimeout(long rtt) {
        // Implementation for updating the timeout value based on RTT measurements

        double SRTT = System.nanoTime() - rtt;
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

    private byte[] loadFile(String fileName) throws IOException {
        File file = new File(fileName);
        byte[] data = new byte[(int) file.length()];
        try (FileInputStream inputStream = new FileInputStream(file)) {
            inputStream.read(data);
        }
        return data;
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
            dataSent, packetsSent, 0,
            0, numRetransmissions, totalDupAcks);
    }

    private class SlidingWindow {
        private class WindowSlot {
            TCPsegment segment;
            int retransmitCount;
            boolean wasRetransmitted;
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

        void markSent(TCPsegment segment) {
            WindowSlot slot = new WindowSlot();
            slot.segment = segment;
            slot.retransmitCount = 0;
            slot.wasRetransmitted = false;
            slots[nextSlot % windowSize] = slot;

            if (inFlight == 0) {
                windowSendTime = System.nanoTime();
            }

            nextSlot++;
            inFlight++;
        }

        boolean canSend() {
            return inFlight < windowSize;
        }

        void advance(int ackNum) {
            boolean moved = false;
            while(base < nextSlot &&
                slots[base % windowSize] != null &&
                slots[base % windowSize].segment.getSeqNum() + slots[base % windowSize].segment.getDataLength() <= ackNum) {

                slots[base % windowSize] = null;
                base++;
                inFlight--;
                moved = true;
            }
            if (moved && inFlight > 0) {
                windowSendTime = System.nanoTime();
            }
        }

        boolean isExpired(long timeout) {
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

        int inFlightCount() {
            return inFlight;
        }

    }

}