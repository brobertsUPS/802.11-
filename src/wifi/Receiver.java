package wifi;
import java.util.ArrayDeque;
import java.util.concurrent.*;

import rf.RF;

/**
 * 
 * @author Brandon Roberts
 *
 */
public class Receiver implements Runnable {

	private RF rf;
	private long recvTime;
	private ArrayDeque<Packet> senderBuf;
	private ArrayBlockingQueue<Packet> receiverBuf;
	
	public Receiver(RF theRF, ArrayDeque<Packet> senderBuffer, ArrayBlockingQueue<Packet> receiverBuffer){
		rf = theRF;
		senderBuf = senderBuffer;
		receiverBuf = receiverBuffer;
	}
	
	/**
	 * Begins waiting for the rf layer to receive and puts it in the receiverBuf
	 */
	public void run() {
		//rf.receive waits for message on channel
		//receiverBuf.put waits for something to put on the Queue
		try {
			receiverBuf.put(new Packet(rf.receive()));
		} catch (InterruptedException e) {
			System.err.println("Receiver interrupted!");
		}
		
//		while(true){
//			try{
//				wait();
//			}catch(InterruptedException e){
//				System.err.println("Thread was woken!");
//			}
//		}
		
	}

}
