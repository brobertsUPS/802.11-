package rf;


import java.io.IOException;
import java.io.PrintWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

//import wifi.Packet;


/**
 * The RF class models a broadcast-based wireless system, but does so on a
 * traditional network by broadcasting a series of UDP packets.
 * The layer models collisions properly (packets involved in collisions
 * are not delivered to the MAC layer), and provides the carrier-sense
 * facility required to implement a CSMA-like system.  The RF layer knows 
 * nothing about addressing, packet structures, etc.  It simply takes a 
 * collection of bytes from the caller and broadcasts them.  Similarly, 
 * incoming transmissions from any and all sources are collected and 
 * delivered, as long as they were not involved in a collision.
 * <p>
 * 
 * Note that many switches and routers are configured <i>not</i> to pass UDP
 * broadcast traffic.  This limits the use of the simulated RF layer to machines
 * within the same physical subnet.  Firewall software can also block the UDP
 * traffic unless it is reconfigured to allow UDP traffic on port 1301.
 * 
 * @author Brad Richards
 * @version v1.7
 */

/*
 * Compilation notes:  For the versions the students use, make sure debug is FALSE,
 * and don't forget that the package line needs to be removed before RF.java is
 * compiled.  (I need to figure out how to do packages in the JNI stuff.)  Theirs
 * also needs to have all traces of Packet removed.  For the versions that run
 * underneath the monitors, add back the Packet references and set debug TRUE.
 */

public class RF implements Runnable {
   /** The SIFS inter-frame spacing time, in milliseconds */
   public static final int aSIFSTime = 100;
   /** The slot time, in milliseconds */
   public static final int aSlotTime = 200;
   /** The minimum size of the collision window */
   public static final int aCWmin    = 3;
   /** The maximum size of the collision window */
   public static final int aCWmax    = 31;   
   /** The maximum number of bytes allowed in an RF packet */
   public static final int aMPDUMaximumLength = 2048;
   /** The maximum nubmer of retransmission attempts */
   public static final int dot11RetryLimit = 5;

   /** Major version number for this RF layer implementation */
   public static final byte VERSION_MAJOR = 1;
   /** Minor version number for this RF layer implementation */
   public static final byte VERSION_MINOR = 7;

   /** The UDP port used for simulated RF traffic. */
   private static final int THE_PORT = 1301;

   /** Ignore this. */
   public static final byte PLEASE_DIE = 1;     // Please die
   /** Ignore this. */
   public static final byte CHECK_VERSION = 2; // Check your version number

   protected static final int HEADER_BYTES = 3;
   protected static final int MS_PER_BYTE = 50;
   protected static final int NUM_BLIPS = 10;
   protected static final long LIFESPAN_IN_MS = 1000 * 60 * 20; // 20 minutes
   protected static PrintWriter out;

   protected DatagramSocket outSock; // The UDP socket used to send outgoing data
   protected DatagramSocket inSock;  // The socket it's using to send data
   protected InetAddress myAddr;    // Local address, so we can filter out loopback  
   protected static InetAddress bcastIP;
   protected InetAddress lastSender; // ID of sender, if just one, or of sender who'll end LAST
   protected boolean collision = false;
   protected boolean inUse = false;
   protected boolean transmitting = false;
   protected long lastUse;
   protected long estEOT;
   protected long clockOffset = 0;  // Random offset from actual timestamp
   // We need two start times to get high-precision timing with a fixed starting point
   // across machines.  The System.currentTimeMillis() call only elapses in 10s of ms,
   // but should be fairly closely synched across machines.  The System.nanoTime() is
   // finer grained, but is only useful for elapsed times.  Thus, we'll use nanoTime to
   // measure elapsed time since a start time provided by currentTimeMillis().
   protected long startTimeMS;   // Absolute time when we started
   protected long startTimeNano; // Start time for elapsed measurements
   protected int nextSeq = 0;


   // TODO FALSE for handing out to students in any form
   protected static final boolean DEBUG = false;
   protected static final boolean BCAST_ASMT = false;  // set to true for bcast assignment

   // Have to wrap our HashSet in a synchronized wrapper so that the checkForEOT and processBlip
   // routines don't clash when modifying it.
   protected Set<InetAddress> colliders = Collections.synchronizedSet(new HashSet<InetAddress>());
   protected BlockingQueue<byte[]> packets = new LinkedBlockingQueue<byte[]>();

   protected void TRACE(String msg) {
      if (out != null) { 
         out.println(msg); 
      } 
   }

   /** 
    * This constructor is for administrative purposes only.  It does not send or receive any
    * actual network traffic.  In other words, do <i>not</i> use it in 802.11~ projects.
    * 
    * @param output  The output stream to be used.
    */
   protected RF(PrintWriter output)
   {
      clockOffset = 0;      
      startTimeMS = System.currentTimeMillis();
      startTimeNano = System.nanoTime();
      out = output;      
   }

   /**
    * The constructor initializes the simulated RF transceiver and prepares it for
    * use.  As it runs, the RF layer will print information about channel use to the 
    * PrintWriter passed in as argument (unless it's null).  An RF instance initializes 
    * its internal clock when created.  Times, reported in milliseconds, are not
    * directly comparable across RF transceivers.  It is <i>not</i> possible to create 
    * more than one RF layer on a single machine, as they must bind to a fixed port and will 
    * conflict.  (Oddly, the bind operation doesn't seem to throw the exception that one 
    * would expect in that case, so two versions may actually <i>seem</i> to coexist on a 
    * single machine though they won't receive properly.)
    * 
    * @param output  If not null, RF layer will print diagnostic information to
    * this PrintWriter.
    * @param bcastAddr  Byte array containing the desired broadcast IP address.  The RF
    * layer defaults to 255.255.255.255 if this argument is null.
    */
   public RF(PrintWriter output, byte[] bcastAddr) {
      // Had to add this to resolve an issue where machines with IPv6 addresses were
      // throwing exceptions in the getLocalHost() call.
      System.setProperty("java.net.preferIPv4Stack", "true");
      /*
       * From http://bugs.sun.com/bugdatabase/view_bug.do?bug_id=4665037:
I've recently come up with a decent workaround that looks like this:

public InetAddress getLocalHost(InetAddress intendedDestination)
   throws SocketException
{
   DatagramSocket sock = new DatagramSocket(RANDOM_PORT);
    sock.connect(intendedDestination, RANDOM_PORT);
    return sock.getLocalAddress();
}
       */

      // Add a random slop factor so that stations have to work to synchronize clocks
      clockOffset = (new Random().nextInt(5000));
      startTimeMS = System.currentTimeMillis() + clockOffset;
      startTimeNano = System.nanoTime();

      out = output;
      try {
         outSock = new DatagramSocket(); // Make a broadcast socket
         outSock.setBroadcast(true);     // Make sure we can broadcast
         // Build the IP broadcast address 255.255.255.255
         if (bcastAddr == null) {
            byte[] bytes = {(byte) 255, (byte) 255, (byte) 255, (byte) 255};
            bcastIP = InetAddress.getByAddress(bytes);
         }
         else
            bcastIP = InetAddress.getByAddress(bcastAddr);

         if (DEBUG) TRACE("Using bcast address: "+bcastIP);

         inSock = new DatagramSocket(null);
         inSock.setReuseAddress(true);
         inSock.bind(new InetSocketAddress(THE_PORT));
         inSock.setSoTimeout(20);  // Wait for at most 20ms before returning
         //myAddr = InetAddress.getLocalHost();
         myAddr = findMyIP();
         if (myAddr == null)
         {
            System.err.println("Unable to determine machine's IP address.");
            System.exit(1);
         }
      } catch (SocketException e) {
         System.out.println("Unable to create socket");
         e.printStackTrace();
         System.exit(1);
      } catch (UnknownHostException e) {
         System.out.println("Java couldn't determine this machine's IP address!");
         e.printStackTrace();
         System.exit(1);
      }

      if (out==null)
         System.out.println("RF layer (v"+VERSION_MAJOR+"."+VERSION_MINOR+") initialized at "+new Date().toString()+
               " ("+myAddr+")");
      else
         TRACE("RF layer (v"+VERSION_MAJOR+"."+VERSION_MINOR+") initialized at "+new Date().toString()+
               " ("+myAddr+")");

      lastUse = clock();
      (new Thread(this)).start();
   }


   
   /*
    * Takes a NetworkInterface and looks through the associated addresses for an
    * IPv4 address that's not link local.  Returns the first such IP address it comes
    * across, or null if one isn't found.
    * @param netint  The network interface whose address we want
    * @return The first IPv4 address for the interface that's not link local
    */
   private InetAddress GetInterfaceIPv4(NetworkInterface netint)
   {
      Enumeration<InetAddress> addrs = netint.getInetAddresses();
      InetAddress ip = null;
      while (addrs.hasMoreElements()) {
         ip = addrs.nextElement();
         if (DEBUG) System.out.println("Considering IP "+ip);
         if (!ip.isLinkLocalAddress() && ip.getAddress().length == 4)
            return ip;
      }
      return null;
   }

   /*
    * We need to find our address so that we can ignore our own broadcasts when filtering
    * blips.  Finding our address is trickier than one might imagine.  I used to do this
    * via "myAddr = InetAddress.getLocalHost();", but it's risky to just take the first
    * address that's returned since a) it could be associated with some non-networking
    * device like a firewire drive, or b) it could be an IPv6 address and currently things
    * go wonky unless I can find and use an IPv4 address.  Different platforms also use
    * very different names for interfaces, so it's not safe to look for "eth0" or something
    * similar.
    * 
    * The code below loops through all interfaces on the machine, looking for those that are
    * both active and not loopback interfaces.  It collects IPv4 addresses from these
    * interfaces, and attempts to find one linked to an interface with a name suggesting
    * it's an ethernet device.  Otherwise, it just returns one of the candidates.
    */  
   private InetAddress findMyIP()
   {
      HashMap<String, InetAddress> addrs = new HashMap<String, InetAddress>();
      try {
         // For each interface ...
         Enumeration<NetworkInterface> nets = NetworkInterface.getNetworkInterfaces();
         for (NetworkInterface netint : Collections.list(nets))
         {
            // Look for an IPv4 if it's active and not a loopback
            String name = netint.getDisplayName();
            if (DEBUG) System.out.println("Inspecting "+name);
            if (netint.isUp() && !netint.isLoopback())
            {
               InetAddress ip = GetInterfaceIPv4(netint);
               // If we find an IPv4 for the interface, add it to the map.  If the
               // interface looks like an ethernet, stop the search and return ip.
               if (ip != null) 
               {
                  if (DEBUG) System.out.println("Found IPv4 for active interface: "+ip);
                  addrs.put(name, ip);
                  if (name.startsWith("et") || name.startsWith("en"))
                     return ip;
               }
            }
         }
         // If we get here, it's because we didn't find exactly what we were looking
         // for:  An IPv4 address associated with an active interface whose name
         // suggests an ethernet device.  We might still have collected some possible
         // IPv4 addresses to consider though.  We could do a more detailed inspection,
         // but for now we'll just return one of them.
         for(String name : addrs.keySet())
         {
            return addrs.get(name);
         }
      }
      catch (Exception e) {
         System.err.println("Exception occurred while looking for IP addresses");
         e.printStackTrace();
      }
      return null;  
   }


   /**
    * This was an attempt to programatically find the network mask.  If we had that, we could 
    * construct a more specific Bcast address that would likely get past the switches and
    * firewalls (though I haven't verified that).  See http://home.cinci.rr.com/estople/udpbrdx/udpbrdx.htm
    * for more info.
    * @return
    *
      private InetAddress findBcastAddr() {
         byte[] bytes = {(byte) 255, (byte) 255, (byte) 255, (byte) 255};
         InetAddress bcastIP = null;

         // Find out who we are
         InetAddress myAddr = null;
         try {
            bcastIP = InetAddress.getByAddress(bytes);
            myAddr = InetAddress.getLocalHost();
         } catch (UnknownHostException e) {
            System.err.println("Unknown Host error while building broadcast address");
            e.printStackTrace();
         }
         NetworkInterface netint = null;
         try {
            netint = NetworkInterface.getByInetAddress(myAddr);
         } catch (SocketException e) {
            e.printStackTrace();
         }
         System.out.println(netint);
         return bcastIP;
      }
    */

   /**
    * Do not use this.  Seriously.
    */
   public void transmitAdmin(byte[] msg)
   {
      /*
       * This routine isn't for general use.  It sends administrative messages to other
       * RF layers.  These messages can be distinguished from virtual data transmissions
       * by a -1 where the blip count would normally be found.
       */
      transmitting = true;
      if (DEBUG) TRACE("Sending administrative message");

      int pktSize = Math.max(HEADER_BYTES, 1+msg.length); // 1 for the -1 byte
      byte[] pkt = new byte[pktSize];
      pkt[0] = -1;      // All admin messages have a -1 where blip # should be
      System.arraycopy(msg, 0, pkt, 1, msg.length); // Copy in remaining message bytes

      DatagramPacket p = new DatagramPacket(pkt, pkt.length, null, THE_PORT);
      p.setAddress(bcastIP);
      try {
         outSock.send(p);
      } catch (IOException e) {
         System.err.println("Send failed");
         e.printStackTrace();
      }
      transmitting = false;
   }


   /**
    * The RF layer's local clock.  Measures time in milliseconds on this machine,
    * but there's no guarantee that the time on this machine is in synch with 
    * the RF clocl values on any other machine.  (In fact, it's almost certainly
    * the case that it's <i>not</i> in synch with other machines.)
    * @return  Current time in milliseconds
    */
   public long clock() { return (System.nanoTime() - startTimeNano)/1000000 + startTimeMS; }  


   /**
    * Inspects the shared channel to see if it's currently being used.  This
    * method does not block, and returns <code>true</code> if at least one
    * host is currently broadcasting, <code>false</code> otherwise.
    * @return  <code>true</code> if the channel is in use
    */
   public boolean inUse() {
      return inUse;
   }

   /**
    * Returns the number of milliseconds elapsed since the last transmission
    * on the shared channel ended.  If the channel is currently in use, this 
    * method returns zero.  Note that the elapsed time reported is the time
    * since the last transmission by <i>any</i> host, and is therefore not
    * necessarily the time since <i>this</i> host last transmitted.
    * @return Number of milliseconds the channel's been idle
    */
   public long getIdleTime() {
      if (inUse)
         return 0;
      else
         return (clock() - lastUse);
   }

   /**
    * This non-blocking method returns <code>true</code> if the RF layer has one or more
    * packets to deliver to the caller.  Since the RF layer is broadcast based,
    * and has no notion of addresses, it will deliver <i>any</i> intact packet that it
    * observes on the channel.  In other words, this method might return <code>true</code>
    * even if the packet wasn't intended for the MAC layer that called <code>dataWaiting()</code>.
    * @return <code>true</code> if the RF layer has accumulated one or more packets 
    */
   public boolean dataWaiting() {
      return packets.size() > 0;
   }

   /**
    * This method blocks until an RF packet arrives, then returns its contents as a byte
    * array.  The RF layer queues arriving packets for delivery, so it's possible that
    * receive will return immediately with the contents of the packet at the head of the
    * queue.  If blocking is unacceptable, only call <code>receive()</code> once
    * <code>dataWaiting()</code> reveals that a packet has arrived.
    * @return the contents of the next RF packet
    */
   public byte[] receive() {
      while (true) {
         try {
            return packets.take();
         } catch (InterruptedException e) {
            // Go back and try again
         }
      }
   }


   /**
    * Starts executing the RF thread, which watches for incoming RF packets and queues
    * them for delivery.  User code should never have to call this explicitly, as the
    * constructor creates a separate thread that invokes run().
    */
   public void run() { 
      // A blank packet that we can use during receipt
      DatagramPacket p = new DatagramPacket(new byte[aMPDUMaximumLength], aMPDUMaximumLength);

      // Run until we exceed the lifetime limit
      while(clock()-startTimeMS < LIFESPAN_IN_MS) {
         /*
          * If the user hasn't asked us to transmit data, then it's ok to look for
          * incoming data.  Otherwise, the transmit routine should be allowed to do
          * all receives so that it detects its collision with any existing use.
          */
         if (!transmitting) {

            // Help detect the end of collisions where the final blip goes missing
            checkForEOT();

            try {               
               // if (DEBUG) TRACE("RF layer's calling UDP receive()");
               inSock.receive(p);
               if (DEBUG) TRACE("RF layer got UDP packet via receive() (from "+p.getAddress()+")");
               // Now we've got a packet that may need processing.  Have to receive
               // it into our dummy packet, p, before we can inspect it.  Only process
               // it if it came from someone other that this "host".
               //    String incoming = new String(p.getData(), 0, p.getLength());
               // System.out.println("Received a packet from "+p.getAddress());
               if (!p.getAddress().equals(myAddr)) {
                  if (DEBUG) TRACE("Calling processBlip because "+p.getAddress()+" != "+myAddr);
                  processBlip(p);
               }
            } catch (SocketTimeoutException e) {
               // This is an expected occurrence -- there was no data to receive
            } catch (IOException e) {
               TRACE("Socket threw an unexpected exception while blocking: "+e);
            }
         }
         else {
            // Need to wait until we're done transmitting.  Could use wait and
            // notify, but I'll just spin with some sleep to improve efficiency
            while (transmitting) {
               try {
                  Thread.sleep(20);
               } catch (InterruptedException e) {
                  // Do nothing if awakened early
               }
            }
         }
      }
      System.err.println("Exceeded the RF layer's lifespan.  So long, cruel world...");
      System.exit(2);
   }


   /*
    * First byte needs to be sequence number, second is simulated payload size.
    */



   //---------------- Stuff from Cybiko.  Note that it is too complex to be correct...

   /**
    * The estimated time for the total transmission is the size times MS_PER_BYTE, and 
    * we alter the delay between blips to achieve this in practice.  The remaining time, 
    * therefore, is (the number of remaining blips / the total number of blips) * total 
    * time.  We also add in a fudge factor so that we overestimate the EOT by a bit.
    */
   private long estimateEOT(int seq, long now, int size)
   {
      long totalTime = (long) (size * (MS_PER_BYTE * 1.0)) + 50;  
      return  (long) (now + totalTime * ((double)(NUM_BLIPS-seq-1) / (NUM_BLIPS-1)));
   }

   private short peekAtSrcAddr(byte[] blip)
   {
      int idx;
      if (BCAST_ASMT)
         idx = HEADER_BYTES;       // Works for RF Bcast assignment
      else
         idx = HEADER_BYTES + 4; // Packet.SRC_OFFSET; // Works for WiFi snooping

      int tmp = ((int)blip[idx]) & 0x00FF;
      tmp = (tmp << 8) | (((int)blip[idx+1]) & 0x00FF);
      return (short)tmp;
   }

   /**
    * The lastSender variable is always set when we set estEOT, so if we've exceeded
    * the time limit, it's safe to assume that lastSender is the (missing) culprit.
    *
    * True, but what about folks who go missing when they're NOT the lastSender?  When
    * do we remove them from the set of colliders?  I guess if we get to the last blip
    * of the lastSender, if there's anybody left in the set it's because they died
    * prematurely, so it's safe to wipe the set!
    * 
    * Also, note that any collections being modified here that are also modified in
    * processBlip are at risk for concurrent access.
    */
   private void checkForEOT() 
   {
      long now = clock();
      if (inUse && ((now-estEOT) > 0))
      {
         colliders.remove(lastSender);
         if (colliders.size() > 0) 
         {
            if (DEBUG) 
               TRACE("Timer expired for "+lastSender+", but others must have died earlier: "+colliders);
            else
               TRACE("Collision ended at local time "+now);
         }
         else
         {
            if (DEBUG) 
               TRACE("Removed "+lastSender+" at "+now+" due to clock expiration.  Was the final sender");
            else
               TRACE("Collision ended at local time "+now);
         }

         lastUse = clock();
         inUse = false;
         collision = false;
         colliders.clear();
      }

   }

   /**
    * Deal with an incoming blip from a remote host.  If we're "transmitting" (sending our
    * own sequence of blips), we discard the incoming blips since we wouldn't hear them on
    * a real RF layer.  Otherwise, we figure out what sort of action is appropriate based
    * on the sequence number of the blip and the current state of the channel.  For example,
    * if this is the first blip from a host, and the channel had been idle, consider the 
    * channel to be in use and watch for more blips from that host.  If the channel's in
    * use by another host when this blip arrives, we're observing a collision, etc.
    * @param p  The UDP packet we're inspecting.
    */
   private void processBlip(DatagramPacket p)
   {
      long tempEOT;
      InetAddress senderIP;    // sender IP of blip we're inspecting (may or may not be "lastSender")
      short senderMAC;
      long now;
      int seq, size;

      now = clock();

      // If this station is transmitting (as it would be if it was jamming), then
      // discard the incoming blip.  Unlikely that we'll end up here, but possible?
      if (transmitting) {
         if (DEBUG) TRACE("Ignoring blip that we wiped out");
         return;
      }

      // Pull out the pieces we need

      senderIP = p.getAddress();
      byte[] data = p.getData();
      if (data.length <= HEADER_BYTES) {
         System.err.println("Received an empty packet!");
         return;
      }

      seq = data[0];

      // Packet is an RF layer administrative command if seq is -1
      if (seq == -1) {
         switch (data[1]) {
         case PLEASE_DIE:
            System.err.println("RF layer is self destructing, as requested by administrator");
            System.exit(2);
            //throw new Error("RF layer is dying, as requested by administrator");
         case CHECK_VERSION:
            byte major = data[2];
            byte minor = data[3];
            if (major > VERSION_MAJOR || (major == VERSION_MAJOR && minor > VERSION_MINOR)) {
               System.err.println("WARNING: RF layer out of date -- v"+
                     VERSION_MAJOR+"."+VERSION_MINOR+" vs v"+major+"."+minor);
            }
            else {
               System.err.println("RF layer is up to date -- v"+VERSION_MAJOR+"."+VERSION_MINOR);
            }
            break;
         default:
            System.err.println("RF layer doesn't understand command: "+data[1]);
         }
         return;
      }

      senderMAC = peekAtSrcAddr(data);
      //    size = data[1];
      size = ((int)data[1]) & 0x00FF;
      size = (size << 8) | (((int)data[2]) & 0x00FF);

      if (DEBUG) TRACE("Got blip #"+seq+" from "+senderIP);

      // Now figure out what to do with it.  First, see if the
      // channel was in use.  If not, this is the first point at
      // which we know it's being used.

      tempEOT = estimateEOT(seq, now, size);

      if (!inUse)
      {
         inUse = true;
         lastSender = senderIP;
         // Set of colliders SHOULD be zero here, but we'll play it safe...
         if (colliders.size() != 0)
            colliders = new HashSet<InetAddress>();
         colliders.add(senderIP);
         if (seq == 0)    // Set start time
         {
            if (DEBUG)
               TRACE("New transmission from " + senderIP + " at " + now + " ("+seq+")");
            else
               TRACE("Tx starting from host "+senderMAC+" at local time "+now);
            collision = false;
         } else { 
            // Sequence number on first blip wasn't zero.  Can happen even on "perfect" NW if
            // someone started transmitting after we did.  We'd consume their blips until we
            // finished, then start seeing them here.
            if (DEBUG)
               TRACE("New partial transmission from " + senderIP + " at " + now + " ("+seq+")");
            else
               TRACE("Tx from host "+senderMAC+" involved in collision at local time "+now+" ("+seq+")");
            collision = true;
         }
         estEOT = tempEOT;      // Estimate end time
         nextSeq = seq+1;
         if (DEBUG) TRACE("Predicting EOT at "+estEOT+", is now "+now);
         return;
      } 

      // If we're here, it's because the channel was already in
      // use.  Take a look at the sequence number and decide whether
      // this was the expected blip, one or more went missing, or
      // an entirely new sequence has begun arriving.

      if ((seq == nextSeq) && (senderIP.equals(lastSender)))
      {
         // We're here because we got the expected blip from the
         // expected sender.  If it's the last blip in the
         // sequence, queue up the data for delivery.  Otherwise,
         // just update EOT.

         if (seq == NUM_BLIPS-1)    // Final blip
         { 
            if (DEBUG) TRACE("Saw final blip from host "+lastSender+" at time "+now+" vs "+estEOT);
            if (colliders.size() != 1)
            {
               colliders.remove(lastSender);    // This one's done
               if (DEBUG) TRACE("Removed "+lastSender+" from melee, others must've died earlier: "+colliders); else
                  TRACE("Tx from host "+senderMAC+" finished at local time "+now+" (others died earlier)");
               colliders.clear();
            }

            // Deposit data for user to collect
            if (!collision) {
               byte[] packet = p.getData();
               int payloadSize = p.getLength() - HEADER_BYTES;
               if (payloadSize < 0)
                  System.err.println("Bad packet received -- negative payload size");
               byte[] payload = new byte[payloadSize];
               for(int i=0; i<payloadSize; i++)
                  payload[i] = packet[i+HEADER_BYTES];
               try {
                  packets.put(payload);
               } catch (InterruptedException e) {
                  System.err.println("Was interrupted while performing PUT on BlockingQueue");
                  e.printStackTrace();
               }
               // TODO Hide Packet reference for first bcast assignment
               if (BCAST_ASMT)
                  TRACE("Uninterrupted Tx from host "+senderMAC+" finished at local time "+now); 
               else {
                  //Packet goodPkt = new Packet(payload);
                  //TRACE("    Received packet at "+now+": "+goodPkt);
                  TRACE("    Received packet at "+now);
               }
            } else
               TRACE("Collision ended at local time "+now);
            nextSeq = 0;      
            inUse = false;
            collision = false;
            colliders.remove(lastSender);
            lastUse = now;
         } 
         else // Got expected blip, but wasn't the final
         {
            estEOT = Math.max(estEOT, tempEOT);
            nextSeq = seq+1;
            if (DEBUG) TRACE("Blip "+seq+" from "+senderIP+" at "+now+" (EOT "+estEOT+")");
         }
      }
      // If we get here, either we got a blip from the expected sender
      // but it wasn't the expected sequence number (one or more went
      // missing), or we're seeing traffic from a different sender.  Either way,
      // it's an indication that we had a collision.  If it's a new sender,
      // we need to see if their transmission will end later than the one
      // we were tracking.  (We could be seeing the tail end of some traffic
      // from an "old" sender that we decided to stop tracking.)

      // It's possible that this blip is the LAST blip in the sequence.  That 
      // means it could both be the first we've seen, and the final blip in a
      // collision.  Regardless of whether this is the sender we're tracking or
      // not, we remove them from the group if this is the last blip.  The only
      // way it could be the end of the collision as a whole is if it's got the
      // largest EOT.  Can I just look at the size of the collision group
      // instead?  Remove this sender and, if they're the last, the collision's 
      // over.

      else
      {  
         collision = true;
         if (seq == NUM_BLIPS-1) {
            // This is the last in this sender's sequence.  We know we've had a
            // collision, so we just drop it and remove the sender from the group.
            // If the group size goes to zero, then the collision's over.
            colliders.remove(senderIP);    
            if (colliders.isEmpty()) {
               nextSeq = 0;      
               inUse = false;
               collision = false;
               lastUse = now;
               if (DEBUG) TRACE("Next blip after gap was the LAST, and ended collision at "+tempEOT);
            }
            else 
               if (DEBUG) TRACE("Next blip after gap was LAST but didn't end collision ("+tempEOT+")");

            // If it was the last blip, whether it ended the collision or not there's
            // no point tracking the EOT, etc.  We don't need it, since we KNOW that
            // this sender either ended the collision or is irrelevant
            return;
         }

         // If it wasn't a final blip...

         if (senderIP.equals(lastSender))
         {
            estEOT = Math.max(estEOT, tempEOT);
            if (DEBUG) TRACE("Missing blip -- still tuned to "+senderIP+" (got "+seq+" w/EOT "+tempEOT+")");
            nextSeq = seq+1;
         }
         else { 
            // Could be from a new collider, or someone we already know about
            if (!colliders.contains(senderIP))
            {
               colliders.add(senderIP);
               if (DEBUG) TRACE("Saw a new collider: "+senderIP);
            }
            else {
               if (DEBUG) TRACE("Seeing a known collider: "+senderIP);
            }

            // Either way, see if this sender will "outlast" the others
            if (tempEOT > estEOT) {
               nextSeq = seq+1;
               estEOT = tempEOT;
               if (DEBUG && (lastSender != senderIP))
                  TRACE("Tracking a new sender: "+senderIP+" w/EOT "+estEOT);                     
               lastSender = senderIP;
            }
            else if (DEBUG)
               TRACE("Ignoring colliding blip from "+senderIP);
         } 
      }

   }


   /**
    * This routine takes data from the MAC layer and broadcasts it to all units
    * on the same subnet.  It returns the number of bytes transmitted.
    * @return number of bytes sent
    */

   public int transmit(byte[] data)
   {
      /* transmit sends NUM_BLIPS copies of the data, with a delay between
       * copies (blips).  This ensures that we can detect an in-use channel, and that
       * actual collisions won't wipe out all traces of channel use.
       *     Between sends, we discard any incoming blips.  This helps us detect 
       * collisions, as otherwise we might discover messages from another host after our 
       * send was complete, and not realize that our send overlapped and was therefore a
       * collision.
       */
      if (DEBUG) TRACE("Sending packet");
      transmitting = true;

      int dataSize = data.length;
      long start = clock();
      if (dataSize > aMPDUMaximumLength) 
         dataSize = aMPDUMaximumLength;

      long delay = MS_PER_BYTE * dataSize / (NUM_BLIPS-1);
      byte[] payload = new byte[dataSize + HEADER_BYTES];
      payload[1] = (byte)((dataSize >>> 8) & 0x00FF);
      payload[2] = (byte)(dataSize & 0x00FF);
      for(int i=0; i<dataSize; i++)
         payload[i+HEADER_BYTES] = data[i];

      for (int i=0; i<NUM_BLIPS; i++)
      {
         payload[0] = (byte)i;
         DatagramPacket p = new DatagramPacket(payload, payload.length, null, THE_PORT);
         p.setAddress(bcastIP);
         try {
            if (DEBUG) TRACE("Sending blip "+i+" at time "+clock());
            outSock.send(p);
         } catch (IOException e) {
            System.err.println("Send failed");
            e.printStackTrace();
         }


         // Consume messages to discard accumulated blips -- we wouldn't
         // have heard them anyway, and this'll keep us from getting confused.
         // It's a little iffy to consume the queued blips AFTER the last
         // transmission though -- if a station was jamming the network, we
         // might get tricked into continually consuming those messages.
         try {
            inSock.setSoTimeout(1);
         } catch (SocketException e1) {
            System.err.println("Warning: failed to set socket timeout of 1ms");
         }

         // Now we need to sleep until nextTx, assuming there IS a next blip
         if (i < NUM_BLIPS-1) {
            long nextTx = (delay * (i+1)) + start;
            while((nextTx - clock()) > 10) {
               try {
                  Thread.sleep(nextTx-clock());
               } catch (InterruptedException e) {
                  // Do nothing if awakened early
               }
            }

            boolean drained = false;
            while (!drained) {
               try {
                  inSock.receive(p);
                  //TRACE("Discarding accumulated blip...");
               } catch (IOException e) {
                  drained = true;
               }
            }
         }
      }
      if (DEBUG) TRACE("Done sending");
      transmitting = false;

      lastUse = clock();
      start = lastUse - start;
      if (DEBUG) TRACE("Sends took "+start+" ms vs the expected "+(MS_PER_BYTE * dataSize));
      return dataSize;
   }

}