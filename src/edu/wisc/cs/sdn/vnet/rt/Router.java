package edu.wisc.cs.sdn.vnet.rt;

import edu.wisc.cs.sdn.vnet.Device;
import edu.wisc.cs.sdn.vnet.DumpFile;
import edu.wisc.cs.sdn.vnet.Iface;

import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.packet.RIPv2;
import net.floodlightcontroller.packet.RIPv2Entry;
import net.floodlightcontroller.packet.IPv4;
import net.floodlightcontroller.packet.UDP;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

/**
 * @author Aaron Gember-Jacobson and Anubhavnidhi Abhashkumar
 */
public class Router extends Device
{	
	/** Routing table for the router */
	private RouteTable routeTable;
	
	/** ARP cache for the router */
	private ArpCache arpCache;

	private Map<Integer, Integer> ripCosts = new HashMap<>();

	private Map<Integer, Long> ripTimestamps = new HashMap<>();
	private Timer ripTimer = new Timer();

	private Map<Integer, Integer> ripMasks = new HashMap<>();
	
	/**
	 * Creates a router for a specific host.
	 * @param host hostname for the router
	 */
	public Router(String host, DumpFile logfile)
	{
		super(host,logfile);
		this.routeTable = new RouteTable();
		this.arpCache = new ArpCache();
	}
	
	/**
	 * @return routing table for the router
	 */
	public RouteTable getRouteTable()
	{ return this.routeTable; }
	
	/**
	 * Load a new routing table from a file.
	 * @param routeTableFile the name of the file containing the routing table
	 */
	public void loadRouteTable(String routeTableFile)
	{
		if (!routeTable.load(routeTableFile, this))
		{
			System.err.println("Error setting up routing table from file "
					+ routeTableFile);
			System.exit(1);
		}
		
		System.out.println("Loaded static route table");
		System.out.println("-------------------------------------------------");
		System.out.print(this.routeTable.toString());
		System.out.println("-------------------------------------------------");
	}
	
	/**
	 * Load a new ARP cache from a file.
	 * @param arpCacheFile the name of the file containing the ARP cache
	 */
	public void loadArpCache(String arpCacheFile)
	{
		if (!arpCache.load(arpCacheFile))
		{
			System.err.println("Error setting up ARP cache from file "
					+ arpCacheFile);
			System.exit(1);
		}
		
		System.out.println("Loaded static ARP cache");
		System.out.println("----------------------------------");
		System.out.print(this.arpCache.toString());
		System.out.println("----------------------------------");
	}

	private void sendRipResponse(Iface outIface, int dstIp, byte[] dstMac) {
		RIPv2 ripResponse = new RIPv2();
		ripResponse.setCommand(RIPv2.COMMAND_RESPONSE);

		// Add directly connected subnets (cost 1)
		for (Iface iface : this.getInterfaces().values()) {
			int subnet = iface.getIpAddress() & iface.getSubnetMask();
			RIPv2Entry ripEntry = new RIPv2Entry(subnet, iface.getSubnetMask(), 1);
			ripEntry.setNextHopAddress(iface.getIpAddress());
			ripResponse.addEntry(ripEntry);
		}
		// Add learned routes from ripCosts
		for (int dest : ripCosts.keySet()) {
			int mask = ripMasks.get(dest);
			int cost = ripCosts.get(dest);
			RIPv2Entry ripEntry = new RIPv2Entry(dest, mask, cost);
			ripResponse.addEntry(ripEntry);
		}

		UDP udpPacket = new UDP();
		udpPacket.setSourcePort(UDP.RIP_PORT);
		udpPacket.setDestinationPort(UDP.RIP_PORT);
		udpPacket.setPayload(ripResponse);

		IPv4 ipPacket = new IPv4();
		ipPacket.setProtocol(IPv4.PROTOCOL_UDP);
		ipPacket.setTtl((byte) 64);
		ipPacket.setSourceAddress(outIface.getIpAddress());
		ipPacket.setDestinationAddress(dstIp);
		ipPacket.setPayload(udpPacket);

		Ethernet etherPacket = new Ethernet();
		etherPacket.setEtherType(Ethernet.TYPE_IPv4);
		etherPacket.setSourceMACAddress(outIface.getMacAddress().toBytes());
		etherPacket.setDestinationMACAddress(dstMac);
		etherPacket.setPayload(ipPacket);

		sendPacket(etherPacket, outIface);
	}

	public void startRIP() {
		for (Iface iface : this.getInterfaces().values()) {
			int subnet = iface.getIpAddress() & iface.getSubnetMask();
			// send RIP request at initialization
			RIPv2 ripRequest = new RIPv2();
			ripRequest.setCommand(RIPv2.COMMAND_REQUEST);
			RIPv2Entry entry = new RIPv2Entry();
			ripRequest.addEntry(entry);

			UDP udpPacket = new UDP();
			IPv4 ipPacket = new IPv4();
			Ethernet etherPacket = new Ethernet();

			udpPacket.setSourcePort((short) UDP.RIP_PORT);
			udpPacket.setDestinationPort((short) UDP.RIP_PORT);
			udpPacket.setPayload(ripRequest);

			ipPacket.setProtocol(IPv4.PROTOCOL_UDP);
			ipPacket.setTtl((byte) 64);
			ipPacket.setSourceAddress(iface.getIpAddress());
			ipPacket.setDestinationAddress("224.0.0.9");
			ipPacket.setPayload(udpPacket);

			etherPacket.setEtherType(Ethernet.TYPE_IPv4);
			etherPacket.setSourceMACAddress(iface.getMacAddress().toBytes());
			etherPacket.setDestinationMACAddress("FF:FF:FF:FF:FF:FF");
			etherPacket.setPayload(ipPacket);

			sendPacket(etherPacket, iface);
			// insert all reachable routers into routing table
			this.routeTable.insert(subnet, 0, iface.getSubnetMask(), iface);

			
		}

		// schedule unsolicited RIP response every 10 seconds
		ripTimer.scheduleAtFixedRate(new TimerTask() {
			@Override
			public void run() {
				for (Iface iface : Router.this.getInterfaces().values()) {
					sendRipResponse(iface, IPv4.toIPv4Address("224.0.0.9"),
							new byte[]{(byte)0xFF,(byte)0xFF,(byte)0xFF,(byte)0xFF,(byte)0xFF,(byte)0xFF});
				}
			}
		}, 10000, 10000);


		// schedule RIP entry expiration every 30 seconds

		ripTimer.scheduleAtFixedRate(new TimerTask() {
    		@Override
    		public void run() {
        		long now = System.currentTimeMillis();
        		for(int dest : new HashSet<>(ripTimestamps.keySet())) {
            		if(now - ripTimestamps.get(dest) >= 30000) {
                		routeTable.remove(dest, ripMasks.get(dest));
                		ripCosts.remove(dest);
                		ripMasks.remove(dest);
                		ripTimestamps.remove(dest);
            		}
        		}
    		}
		}, 1000, 1000);

	}

	/**
	 * Handle an Ethernet packet received on a specific interface.
	 * @param etherPacket the Ethernet packet that was received
	 * @param inIface the interface on which the packet was received
	 */
	public void handlePacket(Ethernet etherPacket, Iface inIface)
	{
		System.out.println("*** -> Received packet: " +
				etherPacket.toString().replace("\n", "\n\t"));

		/********************************************************************/
		if(etherPacket.getEtherType() != 0x0800) {
			return;
		}
		
		IPv4 packet = (IPv4) etherPacket.getPayload();

		if(packet.getProtocol() == IPv4.PROTOCOL_UDP) {
			UDP udpPacket = (UDP) packet.getPayload();
			if(udpPacket.getDestinationPort() == 520) {
				RIPv2 ripPacket = (RIPv2) udpPacket.getPayload();

				// If this is a request, just send back a response
				if (ripPacket.getCommand() == RIPv2.COMMAND_REQUEST) {
					sendRipResponse(inIface, packet.getSourceAddress(),
							etherPacket.getSourceMACAddress());
					return;
				}

				// Handle RIP response — update route table
				for(RIPv2Entry entry : ripPacket.getEntries()) {
					int dest = entry.getAddress();
					int mask = entry.getSubnetMask();
					int cost = entry.getMetric();
					int nextHop = packet.getSourceAddress();
					int newCost = Math.min(cost + 1, 16);

					boolean directlyConnected = false;
					for(Iface iface : this.getInterfaces().values()) {
						int subnet = iface.getIpAddress() & iface.getSubnetMask();
						if(subnet == (dest & mask)) {
							directlyConnected = true;
							break;
						}
					}

					if(directlyConnected) {
						continue;
					}

					RouteEntry re = this.routeTable.lookup(dest);

					if(re == null) {
						if (newCost < 16) {
							this.routeTable.insert(dest, nextHop, mask, inIface);
							ripCosts.put(dest, newCost);
							ripTimestamps.put(dest, System.currentTimeMillis());
							ripMasks.put(dest, mask);
						}
					} else {
						int oldCost = ripCosts.getOrDefault(dest, 16);
						int oldNextHop = re.getGatewayAddress();
						if(oldNextHop == nextHop) {
							// Same next hop — refresh timestamp, update cost
							ripCosts.put(dest, newCost);
							ripTimestamps.put(dest, System.currentTimeMillis());
							ripMasks.put(dest, mask);
							if (newCost >= 16) {
								routeTable.remove(dest, mask);
								ripCosts.remove(dest);
								ripMasks.remove(dest);
								ripTimestamps.remove(dest);
							}
						} else if(newCost < oldCost) {
							this.routeTable.update(dest, mask, nextHop, inIface);
							ripCosts.put(dest, newCost);
							ripTimestamps.put(dest, System.currentTimeMillis());
							ripMasks.put(dest, mask);
						}
					}
				}
				return;
			}
		}


		short origChecksum = packet.getChecksum();
		packet.setChecksum((short) 0);
		byte[] data = packet.serialize();

		data[10] = 0;
		data[11] = 0;

		int accumulation = 0;

		ByteBuffer bb = ByteBuffer.wrap(data);

		for(int i = 0; i < packet.getHeaderLength() * 2; i++) {
			accumulation += 0xffff & bb.getShort();
		}

		accumulation = ((accumulation >> 16) & 0xffff) + (accumulation & 0xffff);

		short checkSum = (short) (~accumulation & 0xffff);

		if(checkSum != origChecksum) { 
			return; 
		}

		if(packet.getTtl() <= 1) { 
			return; 
		}


		packet.setTtl((byte) (packet.getTtl() - 1));
		packet.setChecksum((short) 0);
		byte[] newHeaderBytes = packet.serialize();
		data[10] = 0;
		data[11] = 0;

		bb = ByteBuffer.wrap(newHeaderBytes);
		accumulation = 0;
		for(int i = 0; i < packet.getHeaderLength() * 2; i++) {
			accumulation += 0xffff & bb.getShort();
		}
		accumulation = ((accumulation >> 16) & 0xffff) + (accumulation & 0xffff);
		packet.setChecksum((short) (~accumulation & 0xffff));
	
		for(Iface elem : this.getInterfaces().values()) {
			if(elem.getIpAddress() == packet.getDestinationAddress()) { return; }
		}

		RouteEntry re = routeTable.lookup(packet.getDestinationAddress()); 
		if(re == null) { 
			return; 
		}

		if (re.getInterface() == inIface) { 
			return; 
		}

		int nextHop = re.getGatewayAddress();
		if(nextHop == 0) {
			nextHop = packet.getDestinationAddress();
		}
		ArpEntry arpEntry = arpCache.lookup(nextHop);
		if(arpEntry == null) {
			return; 
		}
		etherPacket.setDestinationMACAddress(arpEntry.getMac().toBytes());
		etherPacket.setSourceMACAddress(re.getInterface().getMacAddress().toBytes());
		sendPacket(etherPacket, re.getInterface());

		
			


		/********************************************************************/
	}
}
