import java.nio.ByteBuffer;

public class TCPsegment {
    private static final int HEADER_SIZE = 24;

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
        this.data = data != null ? data : new byte[0];
        this.syn = syn;
        this.ack = ack;
        this.fin = fin;
        this.checksum = 0;
    }

    public byte[] serialize() {
        int dataLen = (data != null) ? data.length : 0;
        byte[] bytes = new byte[HEADER_SIZE + dataLen];
        ByteBuffer bb = ByteBuffer.wrap(bytes);

        // flags packed into lowest 3 bits of the length+flags word
        int flags = (syn ? 4 : 0) | (fin ? 2 : 0) | (ack ? 1 : 0);
        int lengthAndFlags = (dataLen << 3) | flags;

        bb.putInt(this.seqNum);
        bb.putInt(this.ackNum);
        bb.putLong(this.timestamp);
        bb.putInt(lengthAndFlags);
        bb.putShort((short) 0);       // 16 zero bits
        bb.putShort((short) 0);       // checksum placeholder (0 for computation)
        if (dataLen > 0)
            bb.put(this.data);

        // compute checksum over entire packet with checksum field = 0
        short computed = computeChecksum(bytes);
        bb.putShort(22, computed);    // checksum lives at byte offset 22
        this.checksum = computed;

        return bytes;
    }

    protected static TCPsegment deserialize(byte[] bytes) {
        ByteBuffer bb = ByteBuffer.wrap(bytes);

        int seqNum    = bb.getInt();
        int ackNum    = bb.getInt();
        long timestamp = bb.getLong();
        int lengthAndFlags = bb.getInt();

        int dataLen = (lengthAndFlags >> 3) & 0x1FFFFFFF;
        boolean syn = (lengthAndFlags & 4) != 0;
        boolean fin = (lengthAndFlags & 2) != 0;
        boolean ack = (lengthAndFlags & 1) != 0;

        bb.getShort(); // skip 16 zero bits
        short checksum = bb.getShort();

        byte[] data = new byte[dataLen];
        if (dataLen > 0)
            bb.get(data, 0, dataLen);

        TCPsegment seg = new TCPsegment(seqNum, ackNum, timestamp, data, syn, ack, fin);
        seg.checksum = checksum;
        return seg;
    }

    private short computeChecksum(byte[] bytes) {
        int sum = 0;
        for (int i = 0; i < bytes.length - 1; i += 2) {
            int word = ((bytes[i] & 0xFF) << 8) | (bytes[i + 1] & 0xFF);
            sum += word;
            if ((sum & 0x10000) != 0)
                sum = (sum & 0xFFFF) + 1;
        }
        if (bytes.length % 2 != 0) {
            sum += (bytes[bytes.length - 1] & 0xFF) << 8;
            if ((sum & 0x10000) != 0)
                sum = (sum & 0xFFFF) + 1;
        }
        return (short) ~sum;
    }

    public boolean isValidChecksum() {
        // Re-running checksum over packet (including the stored checksum) should yield 0
        return computeChecksum(serialize()) == 0;
    }

    public int getSeqNum()       { return seqNum; }
    public int getAckNum()       { return ackNum; }
    public long getTimestamp()   { return timestamp; }
    public byte[] getData()      { return data; }
    public boolean isSYN()       { return syn; }
    public boolean isACK()       { return ack; }
    public boolean isFIN()       { return fin; }
    public short getChecksum()   { return checksum; }
    public int getDataLength()   { return data != null ? data.length : 0; }
}
