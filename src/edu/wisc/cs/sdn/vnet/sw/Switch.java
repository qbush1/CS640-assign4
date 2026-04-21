package edu.wisc.cs.sdn.vnet.sw;
import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.packet.MACAddress;

import java.util.HashMap;

import edu.wisc.cs.sdn.vnet.Device;
import edu.wisc.cs.sdn.vnet.DumpFile;
import edu.wisc.cs.sdn.vnet.Iface;

/**
 * @author Aaron Gember-Jacobson
 */
public class Switch extends Device
{	
	//Private static class to help create MAC forwarding table
	private static class ForwardingEntry {
		private Iface iface;
		private long lastAccessTime;

		public ForwardingEntry(Iface iface) {
			this.iface = iface;
			this.lastAccessTime = System.currentTimeMillis();
		}
		public Iface getIface() {
			return this.iface;
		}
		public void setIface(Iface iface) {
			this.iface = iface;
		}
		public void updateLastAccessTime() {
			this.lastAccessTime = System.currentTimeMillis();
		}
		public boolean isValid() {
			return System.currentTimeMillis() - this.lastAccessTime < 15000;
		}
	}
	// private class variables
	private HashMap<MACAddress, ForwardingEntry> forwardingTable;

	/**
	 * Creates a router for a specific host.
	 * @param host hostname for the router
	 */
	public Switch(String host, DumpFile logfile)
	{
		super(host,logfile);
		forwardingTable = new HashMap<>();
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
		/* Handle packets                                             */
		
		// First get source and destination MAC addresses
		MACAddress srcAddress = etherPacket.getSourceMAC();
		MACAddress dstAddress = etherPacket.getDestinationMAC();
		// Check if source address already in table, if it is update lastAccessTime and Iface
		if (forwardingTable.containsKey(srcAddress)) {
			forwardingTable.get(srcAddress).setIface(inIface);
			forwardingTable.get(srcAddress).updateLastAccessTime();
		}
		else {
			// Add this source address with Iface into our forwarding table
			ForwardingEntry newEntry = new ForwardingEntry(inIface);
			forwardingTable.put(srcAddress, newEntry);
		}

		// Look up destination (null if no entry for that address)
		ForwardingEntry dstEntry = forwardingTable.get(dstAddress);

		// If we have an entry but it's expired, treat as unknown (remove + flood)
		if (dstEntry != null && !dstEntry.isValid()) {
			forwardingTable.remove(dstAddress);
			dstEntry = null;
		}

		if (dstEntry == null) {
			// Unknown destination -> flood out all ports except incoming
			for (Iface iface : getInterfaces().values()) {
				if (iface == inIface) continue;
				sendPacket(etherPacket, iface);
			}
			return;
		}

		// Known destination -> forward out learned port (unless same as incoming)
		Iface outIface = dstEntry.getIface();
		if (outIface == inIface) {
			// Same incoming/outgoing port -> drop
			return;
		}

		sendPacket(etherPacket, outIface);
		return;
	}
}


