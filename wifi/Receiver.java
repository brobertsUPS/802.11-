package wifi;

import rf.RF;

import java.util.ArrayDeque;
import java.util.concurrent.*;

/**
 * 
 * Threaded Receiver that continually watches the RF layer for incoming information
 * @author Brandon Roberts
 * @author Nate Olderman
 *
 */
public class Receiver implements Runnable {

	private RF rf;
	//private long recvTime;//currently not used
	private ArrayDeque<Packet> senderBuf;
	private ArrayBlockingQueue<Packet> receiverBuf;
	
	/**
	* Makes a new Receiver object that watches the RF layer for incoming information
	* @param theRF			the RF layer to receive from
	* @param senderBuffer 	the queue of packets needing to be sent (use in Reciever to confirm ACK)
	* @param receiverBuffer	the que of received packets
	*/
	public Receiver(RF theRF, ArrayDeque<Packet> senderBuffer, ArrayBlockingQueue<Packet> receiverBuffer){
		rf = theRF;
		senderBuf = senderBuffer;
		receiverBuf = receiverBuffer;
	}
	
	/**
	 * Begins waiting for the rf layer to receive and puts it in the receiverBuf
	 */
	public void run() {
		while(true){
			//rf.receive waits for message on channel
			//receiverBuf.put waits for something to put on the Queue
			try {
				receiverBuf.put(new Packet(rf.receive()));
			} catch (InterruptedException e) {
				System.err.println("Receiver interrupted!");
			}
		}
		
	}

}
