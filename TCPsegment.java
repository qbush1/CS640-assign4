public class TCPsegment {
    private int seqNum;
    private int ackNum;
    private long timestamp;
    private byte[] data;
    private boolean syn;
    private boolean ack;
    private boolean fin;
    private short checksum;

    public TCPsegment(int seqNum, int ackNum, long timestamp, byte[] data, boolean syn, boolean ack, boolean fin) {
        this.seqNum = seqNum;
        this.ackNum = ackNum;
        this.timestamp = timestamp;
        this.data = data;
        this.syn = syn;
        this.ack = ack;
        this.fin = fin;
        this.checksum = computeChecksum();
    }
}

protected short computeChecksum(byte[] bytes) {
    int sum = 0;

    for(int i = 0; i < bytes.length; i += 2) {
        int word = ((bytes[i] & 0xFF) << 8) | (bytes[i + 1] & 0xFF);
        sum += word;

        if((sum & 0x10000) != 0) {
            sum = (sum & 0xFFFF) + 1; // wrap around carry
        }
    }

    if(bytes.length % 2 != 0) {
        int word = (bytes[bytes.length - 1] & 0xFF) << 8; // pad last byte
        sum += word;

        if((sum & 0x10000) != 0) {
            sum = (sum & 0xFFFF) + 1;
        }
    }
    return (short) ~sum; // one's complement
}

protected byte[] serialize() {
    return new byte[0]; // Placeholder implementation
}

protected static TCPsegment deserialize(byte[] bytes) {
    return new TCPsegment(0, 0, 0, new byte[0], false, false, false); // Placeholder implementation
}

protected boolean isValidChecksum() {
    byte[] segmentBytes = serialize();
    short computedChecksum = computeChecksum(segmentBytes);
    return computedChecksum == 0; // valid if checksum is 0 after including the checksum field
}

public boolean isSYN() {
    return syn;
}

public boolean isACK() {
    return ack;
}
