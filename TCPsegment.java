public class TCPSegment {
    private int seqNum;
    private int ackNum;
    private long timestamp;
    private byte[] data;
    private boolean syn;
    private boolean ack;
    private boolean fin;
    private short checksum;

    public TCPSegment(int seqNum, int ackNum, long timestamp, byte[] data, boolean syn, boolean ack, boolean fin) {
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


