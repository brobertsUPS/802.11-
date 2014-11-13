import wifi.*;
import java.util.ArrayDeque;

import rf.RF;

/**
 * 
 * Threaded sender that continually checks the channel for idle time to send packets
 * @author Brandon Roberts
 * @author Nate Olderman
 *
 */
public class Sender implements Runnable{

	private RF rf;
	//private long sendTime; //currently not used
	private ArrayDeque<Packet> senderBuf;
	
	/**
	* Makes a new Sender object that continually checks the channel for idle time to send packets
	* @param theRF 			the RF layer to send packets out through
	* @param senderBuffer	the queue of packets needing to be sent
	*/
	public Sender(RF theRF, ArrayDeque<Packet> senderBuffer){
		rf = theRF;
		senderBuf = senderBuffer;
	}
	
	/**
	 * Waits to see if RF channel is idle, and sends when it has information
	 */
	public void run() {
		while(true){
			while(rf.inUse()){
				try{
					wait(10);
				}catch(InterruptedException e){
					System.err.println("Sender interrupted!");
				}
			}
			if(!senderBuf.isEmpty()) //only send if we have something in the ArrayDeque
				rf.transmit(senderBuf.poll().toBytes());
		}
	}

}
