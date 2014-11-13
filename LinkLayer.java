import wifi.*;
import rf.RF;

import java.util.concurrent.*;
import java.io.PrintWriter;
import java.util.ArrayDeque;
import java.util.Arrays;


/**
 * Use this layer as a starting point for your project code.  See {@link Dot11Interface} for more
 * details on these routines.
 * @author richards
 * @author Nate Olderman
 * @author Brandon Roberts
 *
 */
public class LinkLayer implements Dot11Interface {
   private RF theRF;          
   private short ourMAC;                            //Our MAC address
   private PrintWriter output;                      //The output stream we'll write to
   private ArrayDeque<Packet> senderBuf;            //the buffer for sending packets
   private ArrayBlockingQueue<Packet> receiverBuf;  //the buffer for receiving packets


  /**
  * Constructor takes a MAC address and the PrintWriter to which our output will
  * be written.
  * @param ourMAC  MAC address
  * @param output  Output stream associated with GUI
  */
  public LinkLayer(short ourMAC, PrintWriter output){
    this.ourMAC = ourMAC;
    this.output = output;      
    theRF = new RF(null, null);
    output.println("LinkLayer: Constructor ran.");
    senderBuf = new ArrayDeque<Packet>(); //TEMPORARILY SET TO 10
    receiverBuf = new ArrayBlockingQueue<Packet>(10); //TEMPORARILY SET TO 10
    
    Thread sender  = new Thread(new Sender(theRF, senderBuf));
    Thread receiver = new Thread(new Receiver(theRF, senderBuf, receiverBuf));
    
    sender.start();
    receiver.start();
  }


  /**
  * Send method takes a destination, a buffer (array) of data, and the number
  * of bytes to send.  See docs for full description.
  */
  public int send(short dest, byte[] data, int len) {
    Packet packet = new Packet((short)0, dest, ourMAC, data);

    output.println("LinkLayer: Sending " + len + " bytes to " + dest);

    senderBuf.push(packet); //have to fill senderBuf with the data from packet
    return len;
  }


  /**
  * Recv method blocks until data arrives, then writes it an address info into
  * the Transmission object.  See docs for full description.
  */
  public int recv(Transmission t) {
    output.println("LinkLayer: Pretending to block on recv()");
    
    Packet packet;
    try{
      //receive the packet
  	  packet = receiverBuf.take();
  	  byte[] packetData = packet.getPacket();

      //put information in the transmission
  	  t.setBuf(packetData);
  	  t.setSourceAddr(packet.getSourceAddr());
  	  t.setDestAddr(ourMAC);

  	  return packetData.length;

    } catch(InterruptedException e){
  	  System.err.println("Receiver interrupted!");
    }
    
    return -1;
  }


  /**
  * Returns a current status code.  See docs for full description.
  */
  public int status() {
    output.println("LinkLayer: Faking a status() return value of 0");
    return 0;
  }


  /**
  * Passes command info to your link layer.  See docs for full description.
  */
  public int command(int cmd, int val) {
    output.println("LinkLayer: Sending command "+cmd+" with value "+val);
    return 0;
  }
}
