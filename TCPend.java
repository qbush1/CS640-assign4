public class TCPend {

    static int port = 0;
    static String remoteIP = null;
    static int remotePort = 0;
    static String fileName = "";
    static int mtu = 0;
    static int sws = 0;

    public static void main(String[] args) {
        parseArguments(args);

        if (remoteIP != null) {
            // Sender mode: java TCPend -p <port> -s <remote IP> -a <remote port> -f <file name> -m <mtu> -c <sws>
            TCPsender sender = new TCPsender(port, remoteIP, remotePort, fileName, mtu, sws);
            sender.run();
        } else {
            // Receiver mode: java TCPend -p <port> -m <mtu> -c <sws> -f <file name>
            TCPreceiver receiver = new TCPreceiver(port, fileName, mtu, sws);
            receiver.run();
        }
    }

    // Args: java TCPend -p <port> -s <remote IP> -a <remote port> -f <file name> -m <mtu> -c <sws>
    //       java TCPend -p <port> -m <mtu> -c <sws> -f <file name>
    private static void parseArguments(String[] args) {
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-p":
                    port = Integer.parseInt(args[++i]);
                    break;
                case "-s":
                    remoteIP = args[++i];
                    break;
                case "-a":
                    remotePort = Integer.parseInt(args[++i]);
                    break;
                case "-f":
                    fileName = args[++i];
                    break;
                case "-m":
                    mtu = Integer.parseInt(args[++i]);
                    break;
                case "-c":
                    sws = Integer.parseInt(args[++i]);
                    break;
                default:
                    System.out.println("Unknown argument: " + args[i]);
                    break;
            }
        }
    }
}
