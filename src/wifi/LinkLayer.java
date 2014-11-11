package wifi;
import java.util.concurrent.*;
import java.io.PrintWriter;
import java.util.ArrayDeque;
import java.util.Arrays;

import rf.RF;

/**
 * Use this layer as a starting point for your project code.  See {@link Dot11Interface} for more
 * details on these routines.
 * @author richards
 */
public class LinkLayer implements Dot11Interface {
   private RF theRF;           // You'll need one of these eventually
   private short ourMAC;       // Our MAC address
   private PrintWriter output; // The output stream we'll write to
   private ArrayDeque senderBuf;
   private ArrayBlockingQueue<Packet> receiverBuf;

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
      senderBuf= new ArrayDeque<Packet>(); //TEMPORARILY SET TO 10
      receiverBuf = new ArrayBlockingQueue<Packet>(10); //TEMPORARILY SET TO 10
      
      Thread sender  = new Thread(new Sender(theRF, senderBuf));
      Thread receiver = new Thread(new Receiver(theRF, senderBuf, receiverBuf));
      
      sender.start();
      receiver.start();
      
      try{
    	  sender.join();
    	  receiver.join();
      }
      catch(InterruptedException e){
    	  System.err.println("Thread was interrupted!" + e);
      }
   }

   /**
    * Send method takes a destination, a buffer (array) of data, and the number
    * of bytes to send.  See docs for full description.
    */
   public int send(short dest, byte[] data, int len) {
	  Packet packet = new Packet((short)0, dest, ourMAC, data);
      output.println("LinkLayer: Sending "+len+" bytes to "+dest);
      
      System.out.println(Arrays.toString(packet.toBytes()));
      
      theRF.transmit(data);
      return len;
   }

   /**
    * Recv method blocks until data arrives, then writes it an address info into
    * the Transmission object.  See docs for full description.
    */
   public int recv(Transmission t) {
      output.println("LinkLayer: Pretending to block on recv()");
      //checks receiver array
      	//if so it takes it and hands it to transmission and returns number of bytes received
      	//else just waits
      Packet packet;
      try{
    	  packet = receiverBuf.take();
    	  byte[] dataBuf = packet.getDatabuf();
    	  t.setBuf(dataBuf);
    	  t.setSourceAddr(packet.getSourceAddr());
    	  t.setDestAddr(ourMAC);
    	  return dataBuf.length+10;
      }catch(InterruptedException e){
    	  System.err.println("Receiver interrupted!");
      }
      
      return -1;
     
      
     
      //Commented out because the ArrayBlockingQueue does this with take() and put()
//      while(receiverBuf.isEmpty()){ 
//    	 try{
//    	  wait(1000);
//    	 }catch(InterruptedException e){
//    		 System.err.println("Receiver interrupted!");
//    	 }
//      }
      
      //return 0;
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
